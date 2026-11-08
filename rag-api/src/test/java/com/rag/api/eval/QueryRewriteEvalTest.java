package com.rag.api.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rag.api.application.QueryRewriteService;
import com.rag.api.application.RetrievalService;
import com.rag.api.infrastructure.llm.LlmClient;
import com.rag.api.infrastructure.persistence.entity.ModelEntity;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RAG-013 Query 改写前后对比：固定多轮指代 / 歧义评测集上，比较仅用原始问题检索
 * 与「原始问题 + 改写问题」多查询 RRF 融合的 Recall@K、MRR、nDCG、引用精确率、
 * 拒答正确率与延迟，输出 {@code target/eval/rewrite-compare.json}。
 *
 * <p>改写阶段用桩模型返回数据集中的标准改写结果，保证指标可复现；同时真实调用
 * {@link QueryRewriteService#expand} 覆盖开关、解析与回退逻辑。脚本记录的改写
 * 延迟为服务处理开销，模型网络耗时依部署环境而异。</p>
 */
class QueryRewriteEvalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void rewriteBeforeAfterComparisonIsReproducible() throws Exception {
        Dataset dataset = load();
        LlmClient llmClient = mock(LlmClient.class);
        QueryRewriteService rewriteService = new QueryRewriteService(llmClient, MAPPER);
        ReflectionTestUtils.setField(rewriteService, "enabled", true);
        ReflectionTestUtils.setField(rewriteService, "maxQueries", 3);
        ReflectionTestUtils.setField(rewriteService, "timeoutSeconds", 8);
        ModelEntity cfg = new ModelEntity();
        cfg.setBaseUrl("http://stub-model");
        cfg.setApiKey("stub");
        cfg.setModel("stub");

        List<Double> beforeRecall = new ArrayList<>();
        List<Double> afterRecall = new ArrayList<>();
        List<Double> beforeMrr = new ArrayList<>();
        List<Double> afterMrr = new ArrayList<>();
        List<Double> beforeNdcg = new ArrayList<>();
        List<Double> afterNdcg = new ArrayList<>();
        List<Double> beforeCitation = new ArrayList<>();
        List<Double> afterCitation = new ArrayList<>();
        List<Boolean> beforeRefusal = new ArrayList<>();
        List<Boolean> afterRefusal = new ArrayList<>();
        List<Boolean> expectedRefusal = new ArrayList<>();
        List<Long> rewriteLatencies = new ArrayList<>();
        List<Long> beforeRetrievalLatencies = new ArrayList<>();
        List<Long> afterRetrievalLatencies = new ArrayList<>();
        List<Map<String, Object>> caseRows = new ArrayList<>();

        for (RewriteCase evalCase : dataset.cases()) {
            stubRewrite(llmClient, evalCase.rewritten());
            List<LlmClient.LlmMessage> history = evalCase.history().stream()
                    .map(h -> new LlmClient.LlmMessage(h.role(), h.content())).toList();

            long start = System.nanoTime();
            List<String> rewritten = rewriteService.expand(cfg, history, evalCase.question());
            rewriteLatencies.add((System.nanoTime() - start) / 1_000_000);
            assertEquals(List.of(evalCase.rewritten()), rewritten, "改写结果应被解析: " + evalCase.id());

            long beforeStart = System.nanoTime();
            List<Long> beforeRanked = RetrievalService.rrfFuse(
                    evalCase.before().vector(), evalCase.before().keyword(),
                    dataset.rrfK(), dataset.candidateK());
            beforeRetrievalLatencies.add((System.nanoTime() - beforeStart) / 1_000_000);

            // 改写后：原始问题 + 改写问题四路召回统一 RRF 融合
            long afterStart = System.nanoTime();
            List<Long> afterRanked = RetrievalService.rrfFuse(List.of(
                    evalCase.before().vector(), evalCase.before().keyword(),
                    evalCase.after().vector(), evalCase.after().keyword()),
                    dataset.rrfK(), dataset.candidateK());
            afterRetrievalLatencies.add((System.nanoTime() - afterStart) / 1_000_000);

            Set<Long> relevant = evalCase.relevant().stream().map(Gain::id)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Map<Long, Double> gains = evalCase.relevant().stream()
                    .collect(Collectors.toMap(Gain::id, g -> (double) g.gain()));
            int k = dataset.topK();
            beforeRecall.add(RetrievalMetrics.recallAtK(beforeRanked, relevant, k));
            afterRecall.add(RetrievalMetrics.recallAtK(afterRanked, relevant, k));
            beforeMrr.add(RetrievalMetrics.mrrAtK(beforeRanked, relevant, k));
            afterMrr.add(RetrievalMetrics.mrrAtK(afterRanked, relevant, k));
            beforeNdcg.add(RetrievalMetrics.ndcgAtK(beforeRanked, gains, k));
            afterNdcg.add(RetrievalMetrics.ndcgAtK(afterRanked, gains, k));

            Set<Long> beforeRetrieved = Set.copyOf(beforeRanked.subList(0, Math.min(k, beforeRanked.size())));
            Set<Long> afterRetrieved = Set.copyOf(afterRanked.subList(0, Math.min(k, afterRanked.size())));
            beforeCitation.add(AnswerMetrics.citationPrecision(
                    evalCase.before().citedChunkIds(), beforeRetrieved));
            afterCitation.add(AnswerMetrics.citationPrecision(
                    evalCase.after().citedChunkIds(), afterRetrieved));
            beforeRefusal.add(AnswerMetrics.isRefusal(evalCase.before().answer()));
            afterRefusal.add(AnswerMetrics.isRefusal(evalCase.after().answer()));
            expectedRefusal.add(evalCase.expectRefusal());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", evalCase.id());
            row.put("category", evalCase.category());
            row.put("question", evalCase.question());
            row.put("rewritten", rewritten);
            row.put("beforeOrder", beforeRanked);
            row.put("afterOrder", afterRanked);
            row.put("beforeRecall", round(beforeRecall.get(beforeRecall.size() - 1)));
            row.put("afterRecall", round(afterRecall.get(afterRecall.size() - 1)));
            row.put("beforeNdcg", round(beforeNdcg.get(beforeNdcg.size() - 1)));
            row.put("afterNdcg", round(afterNdcg.get(afterNdcg.size() - 1)));
            caseRows.add(row);
        }

        Map<String, Object> before = metrics(beforeRecall, beforeMrr, beforeNdcg, beforeCitation,
                AnswerMetrics.refusalAccuracy(beforeRefusal, expectedRefusal), beforeRetrievalLatencies);
        Map<String, Object> after = metrics(afterRecall, afterMrr, afterNdcg, afterCitation,
                AnswerMetrics.refusalAccuracy(afterRefusal, expectedRefusal), afterRetrievalLatencies);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("dataset", "rag-api/src/test/resources/eval/rewrite-dataset.json");
        report.put("rrfK", dataset.rrfK());
        report.put("topK", dataset.topK());
        report.put("rewriteModel", "stub（离线标准改写，覆盖 QueryRewriteService 解析/回退）");
        report.put("rewriteLatencyMs", Map.of(
                "avg", avg(rewriteLatencies), "max", max(rewriteLatencies)));
        report.put("before", before);
        report.put("after", after);
        report.put("cases", caseRows);
        Path out = Path.of("target", "eval", "rewrite-compare.json");
        Files.createDirectories(out.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), report);

        // 改写应带来召回提升，且答案侧离线指标不退化
        assertTrue((double) after.get("recall") >= (double) before.get("recall"),
                "改写后 Recall 不应下降");
        assertTrue((double) after.get("ndcg") >= (double) before.get("ndcg"),
                "改写后 nDCG 不应下降");
        assertTrue((double) after.get("citationPrecision") >= (double) before.get("citationPrecision"),
                "改写后引用精确率不应下降");
        assertTrue((double) after.get("refusalAccuracy") >= (double) before.get("refusalAccuracy"),
                "改写后拒答正确率不应下降");
        assertFalse(caseRows.isEmpty(), "评测集不应为空");
        assertTrue(rewriteLatencies.stream().allMatch(v -> v >= 0));
    }

    private Map<String, Object> metrics(List<Double> recall, List<Double> mrr, List<Double> ndcg,
                                        List<Double> citation, double refusalAccuracy,
                                        List<Long> retrievalLatencies) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("recall", round(RetrievalMetrics.average(recall)));
        map.put("mrr", round(RetrievalMetrics.average(mrr)));
        map.put("ndcg", round(RetrievalMetrics.average(ndcg)));
        map.put("citationPrecision", round(AnswerMetrics.average(citation)));
        map.put("refusalAccuracy", round(refusalAccuracy));
        map.put("retrievalLatencyMs", Map.of(
                "avg", avg(retrievalLatencies), "max", max(retrievalLatencies)));
        return map;
    }

    private void stubRewrite(LlmClient llmClient, String rewritten) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("content", "{\"queries\":[\"" + rewritten + "\"]}");
        when(llmClient.chatOnce(anyString(), anyString(), anyString(), anyList(), isNull(), any(), anyInt()))
                .thenReturn(node);
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private double avg(List<Long> values) {
        return Math.round(values.stream().mapToLong(Long::longValue).average().orElse(0) * 100.0) / 100.0;
    }

    private long max(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).max().orElse(0);
    }

    private Dataset load() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/eval/rewrite-dataset.json")) {
            assertNotNull(in, "缺少 /eval/rewrite-dataset.json");
            return MAPPER.readValue(in, Dataset.class);
        }
    }

    record Dataset(String version, int rrfK, int candidateK, int topK, String description,
                   List<RewriteCase> cases) {
    }

    record RewriteCase(String id, String category, List<History> history, String question,
                       String rewritten, Snapshot before, Snapshot after,
                       List<Gain> relevant, boolean expectRefusal) {
    }

    record History(String role, String content) {
    }

    record Snapshot(List<Long> vector, List<Long> keyword, String answer, List<Long> citedChunkIds) {
    }

    record Gain(long id, int gain) {
    }
}
