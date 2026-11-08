package com.rag.api.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.api.common.BizException;
import com.rag.api.common.ErrorCode;
import com.rag.api.common.TenantContext;
import com.rag.api.infrastructure.persistence.entity.ChunkEntity;
import com.rag.api.infrastructure.persistence.entity.DocumentEntity;
import com.rag.api.infrastructure.persistence.entity.KnowledgeBaseEntity;
import com.rag.api.infrastructure.persistence.entity.PipelineTaskEntity;
import com.rag.api.infrastructure.persistence.mapper.ChunkMapper;
import com.rag.api.infrastructure.persistence.mapper.DocumentMapper;
import com.rag.api.infrastructure.persistence.mapper.KnowledgeBaseMapper;
import com.rag.api.infrastructure.persistence.mapper.PipelineTaskMapper;
import com.rag.api.infrastructure.mq.IngestPublisher;
import com.rag.api.infrastructure.search.EsSearchClient;
import com.rag.api.infrastructure.search.IndexNames;
import com.rag.api.infrastructure.search.MilvusClientWrapper;
import com.rag.api.infrastructure.storage.MinioStorage;
import com.rag.api.interfaces.dto.Dtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final PipelineTaskMapper pipelineTaskMapper;
    private final IngestPublisher publisher;

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
        kb.setIndexVersion(0);
        kb.setIndexState("IDLE");
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
        // 跨租户统一返回"不存在"，不泄露资源存在性（AGENTS §8.3 / RAG-010）
        if (!s.isPlatformAdmin() && !kb.getTenantId().equals(s.tenantId())) {
            throw BizException.notFound("知识库不存在");
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

    /** 级联删除：切片/文档/流水线任务/提示词/授权行 + MinIO 前缀 + Milvus 集合 + ES 索引。 */
    @Transactional
    public void delete(long kbId) {
        KnowledgeBaseEntity kb = getOwned(kbId);
        if (TenantContext.require().userType() != 1) {
            throw BizException.forbidden("仅租户管理员可删除知识库");
        }
        chunkMapper.logicDeleteByKbId(kbId);
        // 先收集文档 id，用于级联清理文件库条目（MinIO 对象由下方 deletePrefix 删除）
        List<Long> docIds = documentMapper.selectIdsByKbId(kbId);
        documentMapper.logicDeleteByKbId(kbId);
        // 流水线任务（与单文档删除 DocumentAppService.delete 保持一致，避免残留 PENDING/SUCCESS 任务）
        pipelineTaskMapper.logicDeleteByKbId(kbId);
        promptTemplateService.deleteByKb(kbId);
        userManageService.onKbDeleted(kbId);
        minio.deletePrefix(kb.getTenantId() + "/" + kbId + "/");
        fileLibraryService.removeByDocuments(docIds);
        // 别名级双版本：读别名 + 各版本物理索引/集合一并清理
        milvus.deleteAcrossVersions(kb.getMilvusCollection(),
                kb.getIndexVersion(), kb.getIndexTargetVersion());
        es.deleteAcrossVersions(kb.getEsIndex(),
                kb.getIndexVersion(), kb.getIndexTargetVersion());
        kbMapper.logicDeleteById(kbId);
    }

    /**
     * 知识库级索引重建（RAG-003 别名级双版本）：
     * Worker 写新版本物理索引，完成后原子切换读别名；重建期间旧版本持续可读。
     */
    public Dtos.KbRebuildView rebuildIndex(long kbId) {
        KnowledgeBaseEntity kb = getOwned(kbId);
        if (TenantContext.require().userType() != 1) {
            throw BizException.forbidden("仅租户管理员可重建知识库索引");
        }
        // 未启用向量模型时快速失败，不投递必然失败的任务
        modelService.requireEnabled(kb.getTenantId(), ModelService.EMBEDDING);

        int targetVersion = (kb.getIndexVersion() == null ? 0 : kb.getIndexVersion()) + 1;
        if (kbMapper.markIndexBuilding(kbId, targetVersion) != 1) {
            throw BizException.badRequest("知识库索引重建已在进行中，请稍后重试");
        }

        PipelineTaskEntity task = new PipelineTaskEntity();
        task.setTenantId(kb.getTenantId());
        task.setKbId(kbId);
        // 知识库级任务无关联文档，document_id 使用哨兵 0（该列 NOT NULL，无外键）
        task.setDocumentId(0L);
        task.setType("REBUILD_KB");
        task.setStatus("PENDING");
        task.setRetryCount(0);
        try {
            pipelineTaskMapper.insert(task);
            var msg = new IngestPublisher.IngestMessage(task.getId(), "REBUILD_KB", kb.getTenantId(),
                    kbId, 0L, null, false);
            task.setPayload(objectMapper.writeValueAsString(msg));
            pipelineTaskMapper.updateById(task);
            publisher.publish(msg);
        } catch (Exception e) {
            // 投递失败必须复位，否则知识库永久卡在 BUILDING 无法再次发起
            kbMapper.resetIndexState(kbId);
            throw new IllegalStateException("索引重建任务投递失败: " + e.getMessage(), e);
        }
        log.info("知识库索引重建任务已投递 kb={} task={} targetVersion={}", kbId, task.getId(), targetVersion);
        return new Dtos.KbRebuildView(task.getId(), targetVersion);
    }

    /** 基础设施准备失败不阻塞建库（可延后到首次入库）。 */
    private void provisionSafely(KnowledgeBaseEntity kb) {
        try {
            var emb = modelService.findEnabled(kb.getTenantId(), ModelService.EMBEDDING);
            int dim = (emb == null || emb.getEmbeddingDim() == null) ? 1024 : emb.getEmbeddingDim();
            String milvusPhysical = IndexNames.physical(kb.getMilvusCollection(), kb.getIndexVersion());
            String esPhysical = IndexNames.physical(kb.getEsIndex(), kb.getIndexVersion());
            milvus.ensureCollection(milvusPhysical, dim);
            es.ensureIndex(esPhysical);
            // 读别名指向当前版本，检索始终通过别名（未建立时读取会回退基名）
            milvus.switchAlias(kb.getMilvusCollection(), milvusPhysical);
            es.ensureAlias(kb.getEsIndex(), esPhysical);
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
