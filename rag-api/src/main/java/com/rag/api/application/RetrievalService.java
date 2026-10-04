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

    public record Citation(int seq, long chunkId, Long parentChunkId, long documentId,
                           String documentName, int page, String sectionPath, String contentType,
                           String previewUrl, String content) {
        public String material() {
            StringBuilder sb = new StringBuilder("[").append(seq).append("] （")
                    .append(documentName).append(" 第").append(page + 1).append("页");
            if (sectionPath != null && !sectionPath.isBlank()) {
                sb.append(" · 章节：").append(sectionPath);
            }
            return sb.append("）\n").append(content).toString();
        }
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
        int usedTokens = 0;
        Set<Long> citedUnits = new HashSet<>();
        for (Long id : topIds) {
            if (citations.size() >= rc.finalContextTopK || usedTokens >= rc.maxContextTokens) {
                break;
            }
            ChunkEntity child = chunks.get(id);
            if (child == null) {
                continue;
            }
            long unitKey = child.getParentChunkId() == null ? id : child.getParentChunkId();
            ChunkEntity unit = all.get(unitKey);
            if (unit == null || "DELETED".equals(unit.getStatus())) {
                unit = child; // parent 缺失时回退 child 自身
            }
            // 失效 parent 的 children 仍是独立检索单元，不能用旧 parentId 合并。
            unitKey = unit.getId();
            if (citedUnits.contains(unitKey)) {
                continue;
            }
            DocumentEntity doc = docs.get(unit.getDocumentId());
            String sectionPath = child.getSectionPath();
            if (sectionPath == null || sectionPath.isBlank()) {
                sectionPath = child.getSectionTitle();
            }
            if (sectionPath == null || sectionPath.isBlank()) {
                sectionPath = unit.getSectionPath();
            }
            Citation citation = new Citation(seq, id,
                    unit.getId().equals(child.getId()) ? null : unit.getId(), unit.getDocumentId(),
                    doc == null ? "" : doc.getFileName(),
                    child.getPage() == null ? 0 : child.getPage(), sectionPath, child.getContentType(),
                    doc == null ? null : minio.presignUrl(doc.getObjectKey()),
                    unit.getContent());
            int remaining = rc.maxContextTokens - usedTokens;
            if (countTokens(citation.material()) > remaining && unit != child) {
                citation = new Citation(seq, id, null, child.getDocumentId(), citation.documentName(),
                        citation.page(), sectionPath, child.getContentType(), citation.previewUrl(),
                        child.getContent());
                unitKey = child.getId();
            }
            if (countTokens(citation.material()) > remaining) {
                int contentBudget = remaining - countTokens(citation.material().substring(0,
                        citation.material().length() - citation.content().length()));
                if (contentBudget <= 0) {
                    continue;
                }
                String bounded = truncateToTokens(citation.content(), contentBudget);
                citation = new Citation(seq, id, citation.parentChunkId(), citation.documentId(),
                        citation.documentName(), citation.page(), citation.sectionPath(),
                        citation.contentType(), citation.previewUrl(), bounded);
            }
            int citationTokens = countTokens(citation.material());
            if (citation.content().isBlank() || citationTokens > remaining) {
                continue;
            }
            citations.add(citation);
            citedUnits.add(unitKey);
            usedTokens += citationTokens;
            seq++;
        }
        return new RetrievalResult(citations, bestScore);
    }

    /** 与 Worker 的切片计数保持同一近似口径。 */
    static int countTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int tokens = 0;
        int asciiRun = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isAsciiAlnum(c)) {
                asciiRun++;
                continue;
            }
            if (asciiRun > 0) {
                tokens += (asciiRun + 3) / 4;
                asciiRun = 0;
            }
            if (!Character.isWhitespace(c)) {
                tokens++;
            }
        }
        return tokens + (asciiRun + 3) / 4;
    }

    private static String truncateToTokens(String text, int maxTokens) {
        int used = 0;
        int asciiRun = 0;
        int end = 0;
        for (int i = 0; i < text.length();) {
            int codePoint = text.codePointAt(i);
            int next = i + Character.charCount(codePoint);
            int cost = isAsciiAlnum(codePoint) ? (asciiRun++ % 4 == 0 ? 1 : 0)
                    : Character.isWhitespace(codePoint) ? 0 : 1;
            if (!isAsciiAlnum(codePoint)) {
                asciiRun = 0;
            }
            if (used + cost > maxTokens) {
                break;
            }
            used += cost;
            end = next;
            i = next;
        }
        return text.substring(0, end).stripTrailing();
    }

    private static boolean isAsciiAlnum(int c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9';
    }
}
