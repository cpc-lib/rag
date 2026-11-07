package com.rag.api.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.api.application.RetrievalService;
import org.junit.jupiter.api.Test;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG-009 固定评测集基线：离线复用生产 RRF 排序，覆盖普通问答、相似段落、精确编号、
 * 跨文档、多跳、表格、图片、无答案八类场景。每次运行输出
 * {@code target/eval/retrieval-baseline.json}，并与数据集内基线比较，关键指标回退时失败。
 *
 * <p>本基线只校验召回侧（RRF 融合与指标口径），不调用 ES / Milvus / LLM；
 * 答案级指标（Faithfulness、Answer Relevance 等）需模型在线评测，按需执行。</p>
 */
class RetrievalBaselineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void fixedDatasetBaselineIsReproducible() throws Exception {
        Dataset dataset = load();
        assertEquals(Set.of("普通问答", "相似段落", "精确编号", "跨文档", "多跳推理", "表格", "图片", "无答案"),
                dataset.cases().stream().map(Case::category).collect(Collectors.toCollection(LinkedHashSet::new)),
                "评测集分类必须完整");

        List<Double> recalls = new ArrayList<>();
        List<Double> precisions = new ArrayList<>();
        List<Double> mrrs = new ArrayList<>();
        List<Double> ndcgs = new ArrayList<>();
        List<Map<String, Object>> caseReports = new ArrayList<>();
        for (Case evalCase : dataset.cases()) {
            List<Long> ranked = RetrievalService.rrfFuse(
                    evalCase.vector(), evalCase.keyword(), dataset.rrfK(), dataset.topK());
            assertEquals(ranked, RetrievalService.rrfFuse(
                    evalCase.vector(), evalCase.keyword(), dataset.rrfK(), dataset.topK()),
                    "同输入 RRF 结果必须可复现");

            Set<Long> relevant = evalCase.relevant().stream()
                    .map(Gain::id).collect(Collectors.toSet());
            Map<Long, Double> gains = evalCase.relevant().stream()
                    .collect(Collectors.toMap(Gain::id, g -> (double) g.gain()));
            double recall = RetrievalMetrics.recallAtK(ranked, relevant, dataset.topK());
            double precision = RetrievalMetrics.precisionAtK(ranked, relevant, dataset.topK());
            double mrr = RetrievalMetrics.mrrAtK(ranked, relevant, dataset.topK());
            double ndcg = RetrievalMetrics.ndcgAtK(ranked, gains, dataset.topK());

            if (relevant.isEmpty()) {
                assertTrue(evalCase.expectNoRelevant(), evalCase.id() + " 缺少相关标注");
                assertEquals(0.0, recall, 1e-9, "无答案问题不应产生召回");
            } else {
                recalls.add(recall);
                precisions.add(precision);
                mrrs.add(mrr);
                ndcgs.add(ndcg);
            }

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("id", evalCase.id());
            report.put("category", evalCase.category());
            report.put("query", evalCase.query());
            report.put("ranked", ranked);
            report.put("relevant", relevant);
            report.put("recallAt5", recall);
            report.put("precisionAt5", precision);
            report.put("mrrAt5", mrr);
            report.put("ndcgAt5", ndcg);
            caseReports.add(report);
        }

        double avgRecall = RetrievalMetrics.average(recalls);
        double avgPrecision = RetrievalMetrics.average(precisions);
        double avgMrr = RetrievalMetrics.average(mrrs);
        double avgNdcg = RetrievalMetrics.average(ndcgs);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("version", dataset.version());
        report.put("dataset", "classpath:eval/retrieval-dataset.json");
        report.put("rrfK", dataset.rrfK());
        report.put("topK", dataset.topK());
        report.put("embeddingModel", dataset.embeddingModel());
        report.put("rerank", dataset.rerank());
        report.put("evaluatedCases", recalls.size());
        report.put("recallAt5", avgRecall);
        report.put("precisionAt5", avgPrecision);
        report.put("mrrAt5", avgMrr);
        report.put("ndcgAt5", avgNdcg);
        report.put("cases", caseReports);
        Path out = Path.of("target", "eval", "retrieval-baseline.json");
        Files.createDirectories(out.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), report);

        Baseline baseline = dataset.baseline();
        assertTrue(avgRecall + 1e-9 >= baseline.recallAt5(), "Recall@5 相对基线回退");
        assertTrue(avgPrecision + 1e-9 >= baseline.precisionAt5(), "Precision@5 相对基线回退");
        assertTrue(avgMrr + 1e-9 >= baseline.mrrAt5(), "MRR@5 相对基线回退");
        assertTrue(avgNdcg + 1e-9 >= baseline.ndcgAt5(), "nDCG@5 相对基线回退");
    }

    @Test
    void multiQueryRrfMergesChannelsAndDeduplicates() {
        List<Long> vector1 = List.of(1L, 2L);
        List<Long> keyword1 = List.of(2L, 3L);
        List<Long> vector2 = List.of(1L, 4L);
        List<Long> keyword2 = List.of(4L, 2L);
        List<List<Long>> rankedLists = List.of(vector1, keyword1, vector2, keyword2);

        List<Long> fused = RetrievalService.rrfFuse(rankedLists, 60, 10);

        assertEquals(Set.of(1L, 2L, 3L, 4L), Set.copyOf(fused), "多 Query 多通道结果必须去重合并");
        assertEquals(fused, RetrievalService.rrfFuse(rankedLists, 60, 10), "同输入排序必须可复现");
        assertEquals(List.of(2L, 1L, 4L, 3L), fused, "多路 RRF 应按累计名次分数排序");
    }

    private Dataset load() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/eval/retrieval-dataset.json")) {
            assertNotNull(in, "缺少固定评测集 /eval/retrieval-dataset.json");
            return MAPPER.readValue(in, Dataset.class);
        }
    }

    record Dataset(String version, int rrfK, int topK, String embeddingModel, String rerank,
                   List<Case> cases, Baseline baseline) {
    }

    record Case(String id, String category, String query, List<Long> vector, List<Long> keyword,
                List<Gain> relevant, boolean expectNoRelevant) {
    }

    record Gain(long id, int gain) {
    }

    record Baseline(double recallAt5, double precisionAt5, double mrrAt5, double ndcgAt5) {
    }
}
