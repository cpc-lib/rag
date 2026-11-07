package com.rag.api.application;

import com.rag.api.common.BizException;
import com.rag.api.common.ErrorCode;
import com.rag.api.common.TenantContext;
import com.rag.api.infrastructure.etcd.EtcdService;
import com.rag.api.infrastructure.llm.LlmClient;
import com.rag.api.infrastructure.llm.RerankClient;
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
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
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

    /** vectorAvailable / keywordAvailable 表示各召回通道本次是否真正执行成功，用于降级观测。 */
    public record RetrievalResult(List<Citation> citations, double bestVectorScore,
                                  boolean vectorAvailable, boolean keywordAvailable) {
        public boolean degraded() {
            return !vectorAvailable || !keywordAvailable;
        }
    }

    private final KnowledgeBaseMapper kbMapper;
    private final ModelService modelService;
    private final ChunkMapper chunkMapper;
    private final DocumentMapper documentMapper;
    private final LlmClient llmClient;
    private final RerankClient rerankClient;
    private final MilvusClientWrapper milvus;
    private final EsSearchClient es;
    private final MinioStorage minio;
    private final EtcdService etcdService;
    private final MeterRegistry meterRegistry;

    public RetrievalResult retrieve(TenantContext.Session session, long kbId, String question) {
        long retrievalStart = System.currentTimeMillis();
        KnowledgeBaseEntity kb = kbMapper.selectById(kbId);
        if (kb == null) {
            throw BizException.notFound("知识库不存在");
        }
        // 跨租户统一返回"不存在"，不泄露资源存在性（AGENTS §8.3 / RAG-010）
        if (!session.isPlatformAdmin() && !kb.getTenantId().equals(session.tenantId())) {
            throw BizException.notFound("知识库不存在");
        }
        EtcdService.RetrievalConfig rc = etcdService.retrievalConfig();

        // 1. 向量通道：Query Embedding + Milvus。任一环节失败只关闭向量通道，不影响关键词通道。
        boolean vectorAvailable = true;
        List<MilvusClientWrapper.MilvusHit> vectorHits = List.of();
        ModelEntity emb = modelService.findEnabled(kb.getTenantId(), ModelService.EMBEDDING);
        if (emb == null || emb.getModel() == null) {
            vectorAvailable = false;
            log.warn("向量通道不可用（租户未启用向量模型）kb={} tenant={}", kbId, kb.getTenantId());
        } else {
            long vecStart = System.currentTimeMillis();
            try {
                float[] vector = llmClient.embed(emb.getBaseUrl(), emb.getApiKey(),
                        emb.getModel(), List.of(question)).get(0);
                vectorHits = milvus.search(kb.getMilvusCollection(), vector, rc.vectorTopN);
                log.info("向量检索（Milvus）kb={} collection={} topN={} 命中={}条 耗时={}ms",
                        kbId, kb.getMilvusCollection(), rc.vectorTopN, vectorHits.size(),
                        System.currentTimeMillis() - vecStart);
            } catch (Exception e) {
                vectorAvailable = false;
                log.warn("向量检索失败，降级为仅关键词检索 kb={} collection={} 耗时={}ms: {}",
                        kbId, kb.getMilvusCollection(), System.currentTimeMillis() - vecStart, e.getMessage());
            }
        }

        // 2. 关键词通道：ES BM25。失败只关闭关键词通道，保留向量结果。
        boolean keywordAvailable = true;
        List<Long> keywordIds = List.of();
        long kwStart = System.currentTimeMillis();
        try {
            keywordIds = es.search(kb.getEsIndex(), question, rc.keywordTopN);
            log.info("关键词检索（ES BM25）kb={} index={} topN={} 命中={}条 耗时={}ms",
                    kbId, kb.getEsIndex(), rc.keywordTopN, keywordIds.size(),
                    System.currentTimeMillis() - kwStart);
        } catch (Exception e) {
            keywordAvailable = false;
            log.warn("关键词检索失败，保留向量结果 kb={} index={} 耗时={}ms: {}",
                    kbId, kb.getEsIndex(), System.currentTimeMillis() - kwStart, e.getMessage());
        }
        // 双通道均不可用属于基础设施故障，必须显式报错，不能伪装成"知识库无匹配"
        if (!vectorAvailable && !keywordAvailable) {
            meterRegistry.counter("rag.retrieval.channel_unavailable", "channel", "all").increment();
            throw new BizException(ErrorCode.UPSTREAM,
                    "向量检索与关键词检索均不可用，请检查向量模型与 Elasticsearch 配置");
        }
        if (!vectorAvailable) {
            meterRegistry.counter("rag.retrieval.channel_unavailable", "channel", "vector").increment();
        }
        if (!keywordAvailable) {
            meterRegistry.counter("rag.retrieval.channel_unavailable", "channel", "keyword").increment();
        }
        double bestScore = vectorHits.stream().mapToDouble(MilvusClientWrapper.MilvusHit::score)
                .max().orElse(0.0);

        // 3. RRF 融合 → candidateTopK 候选 → 可选 Rerank → topK
        List<Long> vectorIds = vectorHits.stream()
                .map(MilvusClientWrapper.MilvusHit::chunkId).toList();
        Map<Long, Double> rrf = rrfScores(vectorIds, keywordIds, rc.rrfK);
        Set<Long> vectorIdSet = new HashSet<>(vectorIds);
        long overlap = keywordIds.stream().filter(vectorIdSet::contains).count();
        int candidateLimit = Math.max(rc.topK, rc.candidateTopK);
        List<Long> candidateIds = rrfFuse(vectorIds, keywordIds, rc.rrfK, candidateLimit);
        log.info("RRF 融合 kb={} rrfK={} 向量={}条 关键词={}条 双路重叠={}条 融合去重={}条 候选={}条 vectorAvailable={} keywordAvailable={}",
                kbId, rc.rrfK, vectorHits.size(), keywordIds.size(), overlap, rrf.size(),
                candidateIds.size(), vectorAvailable, keywordAvailable);
        List<Long> topIds = rerankIfEnabled(kb, question, candidateIds, rc.topK);
        log.info("检索最终排序 kb={} topK={} ids={}", kbId, topIds.size(), topIds);

        // 4. child → parent 扩展：同 parent 去重（保留最高排名），parent 内容作为引用上下文
        Map<Long, ChunkEntity> chunks = topIds.isEmpty() ? Map.of()
                : chunkMapper.selectByIds(topIds).stream()
                .filter(c -> !"DELETED".equals(c.getStatus()))
                .collect(Collectors.toMap(ChunkEntity::getId, Function.identity(), (a, b) -> a));
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
                : documentMapper.selectByIds(docIds).stream()
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
        RetrievalResult result = new RetrievalResult(citations, bestScore, vectorAvailable, keywordAvailable);
        recordMetrics(result, System.currentTimeMillis() - retrievalStart);
        return result;
    }

    /**
     * RRF 融合分数：score = Σ 1/(k + rank)。LinkedHashMap 保序，
     * 分数相同时按首次出现顺序，保证检索与离线评测结果可复现。
     */
    static Map<Long, Double> rrfScores(List<Long> vectorIds, List<Long> keywordIds, int rrfK) {
        Map<Long, Double> scores = new LinkedHashMap<>();
        for (int i = 0; i < vectorIds.size(); i++) {
            scores.merge(vectorIds.get(i), 1.0 / (rrfK + i + 1), Double::sum);
        }
        for (int i = 0; i < keywordIds.size(); i++) {
            scores.merge(keywordIds.get(i), 1.0 / (rrfK + i + 1), Double::sum);
        }
        return scores;
    }

    /** RRF 排序（稳定排序，同分保持向量通道优先的首次出现顺序），检索与离线评测共用。 */
    public static List<Long> rrfFuse(List<Long> vectorIds, List<Long> keywordIds, int rrfK, int limit) {
        List<Map.Entry<Long, Double>> entries = new ArrayList<>(
                rrfScores(vectorIds, keywordIds, rrfK).entrySet());
        entries.sort(Map.Entry.<Long, Double>comparingByValue().reversed());
        return entries.stream().limit(Math.max(0, limit)).map(Map.Entry::getKey).toList();
    }

    /** 检索指标（ISSUE RAG-004/RAG-011）：延迟、降级率、空召回率，通过 /actuator/metrics 暴露。 */
    private void recordMetrics(RetrievalResult result, long costMs) {
        String degraded = Boolean.toString(result.degraded());
        meterRegistry.timer("rag.retrieval.duration", "degraded", degraded)
                .record(Duration.ofMillis(Math.max(0, costMs)));
        meterRegistry.counter("rag.retrieval.total", "degraded", degraded).increment();
        if (result.citations().isEmpty()) {
            meterRegistry.counter("rag.retrieval.empty", "degraded", degraded).increment();
        }
    }

    /**
     * 可选二阶段精排：租户启用 RERANK 模型时，对 RRF 候选做 query + child chunk 精排。
     * 模型未配置、返回空、超时或任意异常均静默降级为 RRF 原排序，不阻断问答主链路。
     */
    private List<Long> rerankIfEnabled(KnowledgeBaseEntity kb, String question,
                                       List<Long> candidateIds, int topK) {
        List<Long> rrfOrdered = candidateIds.stream().limit(topK).toList();
        if (candidateIds.size() <= 1) {
            return rrfOrdered;
        }
        ModelEntity rerank = modelService.findEnabled(kb.getTenantId(), ModelService.RERANK);
        if (rerank == null || rerank.getModel() == null) {
            meterRegistry.counter("rag.rerank.outcome", "outcome", "not_configured").increment();
            return rrfOrdered;
        }
        Map<Long, ChunkEntity> candidates = chunkMapper.selectByIds(candidateIds).stream()
                .filter(c -> !"DELETED".equals(c.getStatus()))
                .collect(Collectors.toMap(ChunkEntity::getId, Function.identity(), (a, b) -> a));
        List<Long> validIds = candidateIds.stream().filter(candidates::containsKey).toList();
        if (validIds.isEmpty()) {
            meterRegistry.counter("rag.rerank.outcome", "outcome", "no_candidate").increment();
            return List.of();
        }
        List<String> documents = validIds.stream().map(id -> candidates.get(id).getContent()).toList();
        try {
            long start = System.currentTimeMillis();
            List<RerankClient.RerankHit> hits = rerankClient.rerank(
                    rerank.getBaseUrl(), rerank.getApiKey(), rerank.getModel(), question, documents, topK);
            if (hits.isEmpty()) {
                meterRegistry.counter("rag.rerank.outcome", "outcome", "empty").increment();
                log.warn("Rerank 返回空结果，降级使用 RRF 排序 kb={}", kb.getId());
                return rrfOrdered;
            }
            List<Long> ordered = new ArrayList<>();
            for (RerankClient.RerankHit hit : hits) {
                if (hit.index() >= 0 && hit.index() < validIds.size()) {
                    Long id = validIds.get(hit.index());
                    if (!ordered.contains(id)) {
                        ordered.add(id);
                    }
                }
            }
            if (ordered.isEmpty()) {
                meterRegistry.counter("rag.rerank.outcome", "outcome", "invalid").increment();
                log.warn("Rerank 结果索引全部越界，降级使用 RRF 排序 kb={}", kb.getId());
                return rrfOrdered;
            }
            // 精排只返回部分候选时，按 RRF 顺序补齐，避免最终上下文数量不足
            for (Long id : validIds) {
                if (ordered.size() >= topK) {
                    break;
                }
                if (!ordered.contains(id)) {
                    ordered.add(id);
                }
            }
            List<Long> result = ordered.stream().limit(topK).toList();
            long cost = System.currentTimeMillis() - start;
            meterRegistry.counter("rag.rerank.outcome", "outcome", "applied").increment();
            meterRegistry.timer("rag.rerank.duration").record(Duration.ofMillis(cost));
            log.info("Rerank 精排完成 kb={} 候选={}条 返回={}条 耗时={}ms ids={}",
                    kb.getId(), validIds.size(), hits.size(), cost, result);
            return result;
        } catch (Exception e) {
            meterRegistry.counter("rag.rerank.outcome", "outcome", "error").increment();
            log.warn("Rerank 调用失败，降级使用 RRF 排序 kb={}: {}", kb.getId(), e.getMessage());
            return rrfOrdered;
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
