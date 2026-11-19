package com.rag.api.application;

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
import com.rag.api.infrastructure.storage.ObjectStorage;
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
 * 混合检索（spec 4.2）：Milvus 余弦 + ES BM25 双路召回 → RRF(k) 融合 → Top-K → 可选 Rerank 重排。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

    public record Citation(int seq, long chunkId, Long parentChunkId, long documentId,
                           String documentName, int page, String sectionPath, String contentType,
                           String previewUrl, String content, String imageUrl) {
        public String material() {
            StringBuilder sb = new StringBuilder("[").append(seq).append("] （")
                    .append(documentName).append(" 第").append(page + 1).append("页");
            if (sectionPath != null && !sectionPath.isBlank()) {
                sb.append(" · 章节：").append(sectionPath);
            }
            if (imageUrl != null) {
                sb.append(" · 图片");
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
    private final ObjectStorage minio;
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
        long vecStart = System.currentTimeMillis();
        try {
            vectorHits = milvus.search(kb.getMilvusCollection(), vector, rc.vectorTopN);
            log.info("向量检索（Milvus）kb={} collection={} topN={} 命中={}条 耗时={}ms",
                    kbId, kb.getMilvusCollection(), rc.vectorTopN, vectorHits.size(),
                    System.currentTimeMillis() - vecStart);
        } catch (Exception e) {
            log.warn("向量检索失败（可能尚未建索引）kb={} collection={} 耗时={}ms: {}",
                    kbId, kb.getMilvusCollection(), System.currentTimeMillis() - vecStart, e.getMessage());
        }
        // 2. 关键词召回
        List<Long> keywordIds = List.of();
        long kwStart = System.currentTimeMillis();
        try {
            keywordIds = es.search(kb.getEsIndex(), question, rc.keywordTopN);
            log.info("关键词检索（ES BM25）kb={} index={} topN={} 命中={}条 耗时={}ms",
                    kbId, kb.getEsIndex(), rc.keywordTopN, keywordIds.size(),
                    System.currentTimeMillis() - kwStart);
        } catch (Exception e) {
            log.warn("关键词检索失败（可能尚未建索引）kb={} index={} 耗时={}ms: {}",
                    kbId, kb.getEsIndex(), System.currentTimeMillis() - kwStart, e.getMessage());
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
        Set<Long> vectorIds = vectorHits.stream().map(MilvusClientWrapper.MilvusHit::chunkId).collect(Collectors.toSet());
        long overlap = keywordIds.stream().filter(vectorIds::contains).count();
        List<Long> topIds = rrf.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(rc.topK)
                .map(Map.Entry::getKey)
                .toList();
        log.info("RRF 融合 kb={} rrfK={} 向量={}条 关键词={}条 双路重叠={}条 融合去重={}条 入选topK={}条 ids={}",
                kbId, rc.rrfK, vectorHits.size(), keywordIds.size(), overlap, rrf.size(), topIds.size(), topIds);

        // 4. 拉取候选切片（Rerank 需要正文，parent 扩展同样需要）
        Map<Long, ChunkEntity> chunks = topIds.isEmpty() ? Map.of()
                : chunkMapper.selectByIds(topIds).stream()
                .filter(c -> !"DELETED".equals(c.getStatus()))
                .collect(Collectors.toMap(ChunkEntity::getId, Function.identity(), (a, b) -> a));

        // 5. 可选 Rerank：RRF 之后、parent 扩展之前重排（未配置/失败静默降级，沿用 RRF 序）
        List<Long> orderedIds = rerank(kb, question, topIds, chunks);

        // 6. child → parent 扩展：同 parent 去重（保留最高排名），parent 内容作为引用上下文
        Set<Long> needLoad = new HashSet<>(chunks.keySet());
        chunks.values().forEach(c -> {
            if (c.getParentChunkId() != null) {
                needLoad.add(c.getParentChunkId());
            }
        });
        Map<Long, ChunkEntity> all = needLoad.equals(chunks.keySet()) ? chunks
                : chunkMapper.selectByIds(needLoad).stream()
                .collect(Collectors.toMap(ChunkEntity::getId, Function.identity(), (a, b) -> a));

        Set<Long> docIds = new HashSet<>();
        Set<Long> emittedUnits = new HashSet<>();
        for (Long id : orderedIds) {
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
                : documentMapper.selectByIds(docIds).stream()
                .collect(Collectors.toMap(DocumentEntity::getId, Function.identity()));

        List<Citation> citations = new ArrayList<>();
        int seq = 1;
        int usedTokens = 0;
        Set<Long> citedUnits = new HashSet<>();
        for (Long id : orderedIds) {
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
                    unit.getContent(), imageUrl(child));
            int remaining = rc.maxContextTokens - usedTokens;
            if (countTokens(citation.material()) > remaining && unit != child) {
                citation = new Citation(seq, id, null, child.getDocumentId(), citation.documentName(),
                        citation.page(), sectionPath, child.getContentType(), citation.previewUrl(),
                        child.getContent(), citation.imageUrl());
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
                        citation.contentType(), citation.previewUrl(), bounded, citation.imageUrl());
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

    /** IMAGE 切片返回稳定的图片访问路径（走接口鉴权，避免预签名 URL 过期导致历史会话缺图）。 */
    private static String imageUrl(ChunkEntity chunk) {
        if (!"IMAGE".equals(chunk.getContentType())
                || chunk.getImageKey() == null || chunk.getImageKey().isBlank()) {
            return null;
        }
        return "/api/v1/chunks/" + chunk.getId() + "/image";
    }

    /**
     * 可选 Rerank：取租户启用的 RERANK 模型，对 RRF 候选按相关度重排。
     * 未配置模型、配置不完整或上游调用失败时静默降级，沿用 RRF 顺序。
     */
    private List<Long> rerank(KnowledgeBaseEntity kb, String question, List<Long> ids,
                              Map<Long, ChunkEntity> chunks) {
        if (ids.size() < 2) {
            return ids;
        }
        ModelEntity model = modelService.findEnabled(kb.getTenantId(), ModelService.RERANK);
        if (model == null) {
            return ids;
        }
        if (model.getBaseUrl() == null || model.getBaseUrl().isBlank()
                || model.getModel() == null || model.getModel().isBlank()) {
            log.warn("Rerank 模型配置不完整（缺少 Base URL 或模型名称），跳过重排 modelId={}", model.getId());
            return ids;
        }
        List<Long> candidates = ids.stream()
                .filter(id -> {
                    ChunkEntity c = chunks.get(id);
                    return c != null && c.getContent() != null && !c.getContent().isBlank();
                })
                .toList();
        if (candidates.size() < 2) {
            return ids;
        }
        List<String> documents = candidates.stream().map(id -> chunks.get(id).getContent()).toList();
        long start = System.currentTimeMillis();
        try {
            List<Integer> order = llmClient.rerank(model.getBaseUrl(), model.getApiKey(),
                    model.getModel(), question, documents);
            List<Long> reranked = new ArrayList<>(ids.size());
            for (int index : order) {
                reranked.add(candidates.get(index));
            }
            // 无正文候选取不到分数，保持原位置追加，避免丢失
            Set<Long> rerankedIds = new HashSet<>(reranked);
            ids.stream().filter(id -> !rerankedIds.contains(id)).forEach(reranked::add);
            log.info("Rerank 重排 kb={} model={} 候选={}条 耗时={}ms 新序={}",
                    kb.getId(), model.getModel(), candidates.size(),
                    System.currentTimeMillis() - start, reranked);
            return reranked;
        } catch (Exception e) {
            log.warn("Rerank 调用失败，沿用 RRF 顺序 kb={} model={} 耗时={}ms: {}",
                    kb.getId(), model.getModel(), System.currentTimeMillis() - start, e.getMessage());
            return ids;
        }
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
