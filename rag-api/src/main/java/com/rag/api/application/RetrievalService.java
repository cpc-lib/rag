package com.rag.api.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rag.api.common.BizException;
import com.rag.api.common.ErrorCode;
import com.rag.api.common.TenantContext;
import com.rag.api.infrastructure.etcd.EtcdService;
import com.rag.api.infrastructure.llm.LlmClient;
import com.rag.api.infrastructure.persistence.entity.ChunkEntity;
import com.rag.api.infrastructure.persistence.entity.DocumentEntity;
import com.rag.api.infrastructure.persistence.entity.KnowledgeBaseEntity;
import com.rag.api.infrastructure.persistence.entity.ModelEntity;
import com.rag.api.infrastructure.persistence.mapper.ChunkMapper;
import com.rag.api.infrastructure.persistence.mapper.DocumentMapper;
import com.rag.api.infrastructure.persistence.mapper.KnowledgeBaseMapper;
import com.rag.api.infrastructure.search.EsSearchClient;
import com.rag.api.infrastructure.search.MilvusClientWrapper;
import com.rag.api.infrastructure.storage.MinioStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 混合检索（spec 4.2）：Milvus 余弦 + ES BM25 双路召回 → RRF(k) 融合 → Top-K。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

    public record Citation(int seq, long chunkId, long documentId, String documentName, int page,
                           String previewUrl, String content) {
    }

    public record RetrievalResult(List<Citation> citations, double bestVectorScore) {
    }

    private final KnowledgeBaseMapper kbMapper;
    private final ModelService modelService;
    private final ChunkMapper chunkMapper;
    private final DocumentMapper documentMapper;
    private final LlmClient llmClient;
    private final MilvusClientWrapper milvus;
    private final EsSearchClient es;
    private final MinioStorage minio;
    private final EtcdService etcdService;

    public RetrievalResult retrieve(TenantContext.Session session, long kbId, String question) {
        KnowledgeBaseEntity kb = kbMapper.selectById(kbId);
        if (kb == null) {
            throw BizException.notFound("知识库不存在");
        }
        if (!session.isPlatformAdmin() && !kb.getTenantId().equals(session.tenantId())) {
            throw BizException.forbidden("无权访问该知识库");
        }
        ModelEntity emb = modelService.requireEnabled(kb.getTenantId(), ModelService.EMBEDDING);
        if (emb.getModel() == null) {
            throw BizException.badRequest("启用的向量模型缺少模型名称");
        }
        EtcdService.RetrievalConfig rc = etcdService.retrievalConfig();

        // 1. 向量召回
        float[] vector = llmClient.embed(emb.getBaseUrl(), emb.getApiKey(),
                emb.getModel(), List.of(question)).get(0);
        List<MilvusClientWrapper.MilvusHit> vectorHits = List.of();
        try {
            vectorHits = milvus.search(kb.getMilvusCollection(), vector, rc.vectorTopN);
        } catch (Exception e) {
            log.warn("向量检索失败（可能尚未建索引）kb={}: {}", kbId, e.getMessage());
        }
        // 2. 关键词召回
        List<Long> keywordIds = List.of();
        try {
            keywordIds = es.search(kb.getEsIndex(), question, rc.keywordTopN);
        } catch (Exception e) {
            log.warn("关键词检索失败（可能尚未建索引）kb={}: {}", kbId, e.getMessage());
        }
        double bestScore = vectorHits.stream().mapToDouble(MilvusClientWrapper.MilvusHit::score)
                .max().orElse(0.0);

        // 3. RRF 融合
        Map<Long, Double> rrf = new HashMap<>();
        for (int i = 0; i < vectorHits.size(); i++) {
            rrf.merge(vectorHits.get(i).chunkId(), 1.0 / (rc.rrfK + i + 1), Double::sum);
        }
        for (int i = 0; i < keywordIds.size(); i++) {
            rrf.merge(keywordIds.get(i), 1.0 / (rc.rrfK + i + 1), Double::sum);
        }
        List<Long> topIds = rrf.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(rc.topK)
                .map(Map.Entry::getKey)
                .toList();

        // 4. child → parent 扩展：同 parent 去重（保留最高排名），parent 内容作为引用上下文
        Map<Long, ChunkEntity> chunks = topIds.isEmpty() ? Map.of()
                : chunkMapper.selectBatchIds(topIds).stream()
                .filter(c -> !"DELETED".equals(c.getStatus()))
                .collect(Collectors.toMap(ChunkEntity::getId, Function.identity(), (a, b) -> a));
        Set<Long> needLoad = new HashSet<>(chunks.keySet());
        chunks.values().forEach(c -> {
            if (c.getParentChunkId() != null) {
                needLoad.add(c.getParentChunkId());
            }
        });
        Map<Long, ChunkEntity> all = needLoad.equals(chunks.keySet()) ? chunks
                : chunkMapper.selectBatchIds(needLoad).stream()
                .collect(Collectors.toMap(ChunkEntity::getId, Function.identity(), (a, b) -> a));

        Set<Long> docIds = new HashSet<>();
        Set<Long> emittedUnits = new HashSet<>();
        for (Long id : topIds) {
            ChunkEntity child = chunks.get(id);
            if (child == null) {
                continue;
            }
            long unitKey = child.getParentChunkId() == null ? id : child.getParentChunkId();
            if (!emittedUnits.add(unitKey)) {
                continue;
            }
            ChunkEntity unit = all.get(unitKey);
            docIds.add((unit != null ? unit : child).getDocumentId());
        }
        Map<Long, DocumentEntity> docs = docIds.isEmpty() ? Map.of()
                : documentMapper.selectBatchIds(docIds).stream()
                .collect(Collectors.toMap(DocumentEntity::getId, Function.identity()));

        List<Citation> citations = new ArrayList<>();
        int seq = 1;
        Set<Long> citedUnits = new HashSet<>();
        for (Long id : topIds) {
            ChunkEntity child = chunks.get(id);
            if (child == null) {
                continue;
            }
            long unitKey = child.getParentChunkId() == null ? id : child.getParentChunkId();
            // 同 parent 仅保留排名最高的 child
            if (!citedUnits.add(unitKey)) {
                continue;
            }
            ChunkEntity unit = all.get(unitKey);
            if (unit == null || "DELETED".equals(unit.getStatus())) {
                unit = child; // parent 缺失时回退 child 自身
            }
            DocumentEntity doc = docs.get(unit.getDocumentId());
            citations.add(new Citation(seq++, id, unit.getDocumentId(),
                    doc == null ? "" : doc.getFileName(),
                    child.getPage() == null ? 0 : child.getPage(),
                    doc == null ? null : minio.presignUrl(doc.getObjectKey()),
                    unit.getContent()));
        }
        return new RetrievalResult(citations, bestScore);
    }
}
