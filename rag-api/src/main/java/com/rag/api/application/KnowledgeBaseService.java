package com.rag.api.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.api.common.BizException;
import com.rag.api.common.ErrorCode;
import com.rag.api.common.TenantContext;
import com.rag.api.infrastructure.persistence.entity.ChunkEntity;
import com.rag.api.infrastructure.persistence.entity.DocumentEntity;
import com.rag.api.infrastructure.persistence.entity.KnowledgeBaseEntity;
import com.rag.api.infrastructure.persistence.mapper.ChunkMapper;
import com.rag.api.infrastructure.persistence.mapper.DocumentMapper;
import com.rag.api.infrastructure.persistence.mapper.KnowledgeBaseMapper;
import com.rag.api.infrastructure.search.EsSearchClient;
import com.rag.api.infrastructure.search.MilvusClientWrapper;
import com.rag.api.infrastructure.storage.MinioStorage;
import com.rag.api.interfaces.dto.Dtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识库管理：创建即绑定 kb_{id} 的 Milvus 集合与 ES 索引（spec 3.2）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final KnowledgeBaseMapper kbMapper;
    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;
    private final ModelService modelService;
    private final MilvusClientWrapper milvus;
    private final EsSearchClient es;
    private final MinioStorage minio;
    private final ObjectMapper objectMapper;
    private final UserManageService userManageService;
    private final PromptTemplateService promptTemplateService;
    private final FileLibraryService fileLibraryService;

    public KnowledgeBaseEntity create(Dtos.KbCreateReq req) {
        TenantContext.Session s = TenantContext.require();
        if (s.userType() != 1) {
            throw BizException.forbidden("仅租户管理员可创建知识库");
        }
        validateStrategy(req.parentChunkSize(), req.childChunkSize(), req.childOverlap());
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setTenantId(s.tenantId());
        kb.setName(req.name());
        kb.setDescription(req.description());
        kb.setParentChunkSize(req.parentChunkSize() == null ? 2000 : req.parentChunkSize());
        kb.setChildChunkSize(req.childChunkSize() == null ? 500 : req.childChunkSize());
        kb.setChildOverlap(req.childOverlap() == null ? 80 : req.childOverlap());
        kb.setChunkStrategy(normalizeStrategy(req.chunkStrategy()));
        kb.setSeparators(toSeparatorsJson(req.separators()));
        kbMapper.insert(kb);
        kb.setMilvusCollection("kb_" + kb.getId());
        kb.setEsIndex("kb_" + kb.getId());
        kbMapper.updateById(kb);
        promptTemplateService.seedDefault(s.tenantId(), kb.getId());
        provisionSafely(kb);
        return kb;
    }

    public List<KnowledgeBaseEntity> list() {
        return userManageService.visibleKbs();
    }

    public KnowledgeBaseEntity getOwned(long kbId) {
        TenantContext.Session s = TenantContext.require();
        KnowledgeBaseEntity kb = kbMapper.selectById(kbId);
        if (kb == null) {
            throw BizException.notFound("知识库不存在");
        }
        if (!s.isPlatformAdmin() && !kb.getTenantId().equals(s.tenantId())) {
            throw BizException.forbidden("无权访问该知识库");
        }
        return kb;
    }

    public KnowledgeBaseEntity update(long kbId, Dtos.KbUpdateReq req) {
        KnowledgeBaseEntity kb = getOwned(kbId);
        if (TenantContext.require().userType() != 1) {
            throw BizException.forbidden("仅租户管理员可修改知识库");
        }
        if (req.name() != null && !req.name().isBlank()) {
            kb.setName(req.name());
        }
        if (req.description() != null) {
            kb.setDescription(req.description());
        }
        validateStrategy(req.parentChunkSize(), req.childChunkSize(), req.childOverlap());
        if (req.parentChunkSize() != null) {
            kb.setParentChunkSize(req.parentChunkSize());
        }
        if (req.childChunkSize() != null) {
            kb.setChildChunkSize(req.childChunkSize());
        }
        if (req.childOverlap() != null) {
            kb.setChildOverlap(req.childOverlap());
        }
        if (req.chunkStrategy() != null) {
            kb.setChunkStrategy(normalizeStrategy(req.chunkStrategy()));
        }
        if (req.separators() != null && !req.separators().isEmpty()) {
            kb.setSeparators(toSeparatorsJson(req.separators()));
        }
        kbMapper.updateById(kb);
        return kb;
    }

    /** 级联删除：切片/文档行 + MinIO 前缀 + Milvus 集合 + ES 索引。 */
    public void delete(long kbId) {
        KnowledgeBaseEntity kb = getOwned(kbId);
        if (TenantContext.require().userType() != 1) {
            throw BizException.forbidden("仅租户管理员可删除知识库");
        }
        chunkMapper.delete(new QueryWrapper<ChunkEntity>().eq("kb_id", kbId));
        // 先收集文档 id，用于级联清理文件库条目（MinIO 对象由下方 deletePrefix 删除）
        List<Long> docIds = documentMapper.selectObjs(new QueryWrapper<DocumentEntity>()
                        .select("id").eq("kb_id", kbId)).stream()
                .filter(java.util.Objects::nonNull)
                .map(o -> ((Number) o).longValue())
                .toList();
        documentMapper.delete(new QueryWrapper<DocumentEntity>().eq("kb_id", kbId));
        promptTemplateService.deleteByKb(kbId);
        userManageService.onKbDeleted(kbId);
        minio.deletePrefix(kb.getTenantId() + "/" + kbId + "/");
        fileLibraryService.removeByDocuments(docIds);
        try {
            if (kb.getMilvusCollection() != null && milvus.hasCollection(kb.getMilvusCollection())) {
                milvus.dropCollection(kb.getMilvusCollection());
            }
        } catch (Exception e) {
            log.warn("Milvus 集合删除失败 kb={}: {}", kbId, e.getMessage());
        }
        try {
            if (kb.getEsIndex() != null) {
                es.deleteIndex(kb.getEsIndex());
            }
        } catch (Exception e) {
            log.warn("ES 索引删除失败 kb={}: {}", kbId, e.getMessage());
        }
        kbMapper.deleteById(kbId);
    }

    /** 基础设施准备失败不阻塞建库（可延后到首次入库）。 */
    private void provisionSafely(KnowledgeBaseEntity kb) {
        try {
            var emb = modelService.findEnabled(kb.getTenantId(), ModelService.EMBEDDING);
            int dim = (emb == null || emb.getEmbeddingDim() == null) ? 1024 : emb.getEmbeddingDim();
            milvus.ensureCollection(kb.getMilvusCollection(), dim);
            es.ensureIndex(kb.getEsIndex());
        } catch (Exception e) {
            log.warn("知识库索引预创建失败（首次入库时重试）kb={}: {}", kb.getId(), e.getMessage());
        }
    }

    private void validateStrategy(Integer parentSize, Integer childSize, Integer overlap) {
        if (parentSize != null && (parentSize < 200 || parentSize > 8000)) {
            throw BizException.badRequest("parent_chunk_size 取值 200~8000");
        }
        if (childSize != null && (childSize < 100 || childSize > 2000)) {
            throw BizException.badRequest("child_chunk_size 取值 100~2000");
        }
        if (overlap != null && (overlap < 0 || overlap > 500)) {
            throw BizException.badRequest("child_overlap 取值 0~500");
        }
        int p = parentSize == null ? 2000 : parentSize;
        int c = childSize == null ? 500 : childSize;
        int o = overlap == null ? 80 : overlap;
        if (c >= p) {
            throw BizException.badRequest("child_chunk_size 必须小于 parent_chunk_size");
        }
        if (o >= c) {
            throw BizException.badRequest("child_overlap 必须小于 child_chunk_size");
        }
    }

    private static final java.util.Set<String> STRATEGY_NAMES = java.util.Arrays.stream(
                    new String[]{"FIXED_SIZE", "RECURSIVE", "PARAGRAPH", "SENTENCE", "SEMANTIC",
                            "STRUCTURE", "MARKDOWN", "HTML", "PDF_LAYOUT", "TABLE", "QA",
                            "PARENT_CHILD", "SLIDING_WINDOW", "CODE", "AUTO"})
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private String normalizeStrategy(String name) {
        if (name == null || name.isBlank()) {
            return "AUTO";
        }
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        if (!STRATEGY_NAMES.contains(upper)) {
            throw BizException.badRequest("未知切片策略: " + name);
        }
        return upper;
    }

    private String toSeparatorsJson(List<String> separators) {
        try {
            if (separators == null || separators.isEmpty()) {
                return null;
            }
            return objectMapper.writeValueAsString(separators);
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "separators 序列化失败");
        }
    }
}
