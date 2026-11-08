package com.rag.api.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

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
 * RAG-009 答案指标评测：Faithfulness、Answer Relevance、Citation Precision、拒答正确率。
 *
 * <p>离线部分用固定快照计算确定性指标并输出 {@code target/eval/answer-baseline.json}；
 * 语义指标需在线 CHAT 模型，设置 {@code RAG_EVAL_ANSWER=true} 且提供
 * {@code RAG_EVAL_BASE_URL / RAG_EVAL_API_KEY / RAG_EVAL_MODEL} 后按需执行，
 * 结果同样落盘并与数据集基线比较。</p>
 */
class AnswerBaselineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path REPORT = Path.of("target", "eval", "answer-baseline.json");

    @Test
    void offlineMetricsAndReportAreReproducible() throws Exception {
        Dataset dataset = load();
        assertEquals(Set.of("普通问答", "相似段落", "精确编号", "跨文档", "多跳推理", "表格", "图片", "无答案"),
                dataset.cases().stream().map(Case::category).collect(Collectors.toCollection(LinkedHashSet::new)),
                "评测集分类必须完整");

        List<Double> citations = new ArrayList<>();
        List<Boolean> predictedRefusal = new ArrayList<>();
        List<Boolean> expectedRefusal = new ArrayList<>();
        List<Map<String, Object>> caseReports = new ArrayList<>();
        for (Case evalCase : dataset.cases()) {
            boolean refusal = AnswerMetrics.isRefusal(evalCase.answer());
            predictedRefusal.add(refusal);
            expectedRefusal.add(evalCase.expectRefusal());
            // 拒答样例没有引用，不参与引用精确率
            Double citation = evalCase.expectRefusal() ? null
                    : AnswerMetrics.citationPrecision(evalCase.citedChunkIds(),
                    Set.copyOf(evalCase.retrievedChunkIds()));
            if (citation != null) {
                citations.add(citation);
            }
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("id", evalCase.id());
            report.put("category", evalCase.category());
            report.put("query", evalCase.query());
            report.put("refusal", refusal);
            report.put("expectRefusal", evalCase.expectRefusal());
            report.put("citationPrecision", citation);
            caseReports.add(report);
        }

        double citationPrecision = AnswerMetrics.average(citations);
        double refusalAccuracy = AnswerMetrics.refusalAccuracy(predictedRefusal, expectedRefusal);
        assertEquals(0.9375, citationPrecision, 1e-9, "ans-09 含一条无效引用，引用精确率应为 0.9375");
        assertEquals(1.0, refusalAccuracy, 1e-9, "拒答样例与正常回答应全部判定正确");

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("version", dataset.version());
        report.put("mode", "offline");
        report.put("note", "Faithfulness / Answer Relevance 需在线判分（RAG_EVAL_ANSWER=true）");
        report.put("evaluatedCases", dataset.cases().size());
        report.put("citationPrecision", citationPrecision);
        report.put("refusalAccuracy", refusalAccuracy);
        report.put("cases", caseReports);
        write(report);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RAG_EVAL_ANSWER", matches = "true")
    void onlineJudgeReportMeetsBaseline() throws Exception {
        String baseUrl = System.getenv("RAG_EVAL_BASE_URL");
        String apiKey = System.getenv("RAG_EVAL_API_KEY");
        String model = System.getenv().getOrDefault("RAG_EVAL_MODEL", load().judgeModel());
        assertTrue(baseUrl != null && !baseUrl.isBlank(), "缺少 RAG_EVAL_BASE_URL");
        assertTrue(apiKey != null && !apiKey.isBlank(), "缺少 RAG_EVAL_API_KEY");
        assertTrue(model != null && !model.isBlank(), "缺少 RAG_EVAL_MODEL");

        Dataset dataset = load();
        AnswerJudge judge = new OpenAiAnswerJudge(baseUrl, apiKey, model, 60);

        List<Double> faithfulness = new ArrayList<>();
        List<Double> relevance = new ArrayList<>();
        List<Double> citations = new ArrayList<>();
        List<Boolean> predictedRefusal = new ArrayList<>();
        List<Boolean> expectedRefusal = new ArrayList<>();
        List<Map<String, Object>> caseReports = new ArrayList<>();
        for (Case evalCase : dataset.cases()) {
            // 拒答样例由 refusalAccuracy 单独衡量；语义指标只评有答案的样例，
            // 避免正确拒答被判为“答非所问”而拉低相关性
            double faith = evalCase.expectRefusal() ? Double.NaN
                    : judge.faithfulness(evalCase.query(), evalCase.answer(), evalCase.contexts());
            double rel = evalCase.expectRefusal() ? Double.NaN
                    : judge.answerRelevance(evalCase.query(), evalCase.answer());
            faithfulness.add(faith);
            relevance.add(rel);
            boolean refusal = AnswerMetrics.isRefusal(evalCase.answer());
            predictedRefusal.add(refusal);
            expectedRefusal.add(evalCase.expectRefusal());
            Double citation = evalCase.expectRefusal() ? null
                    : AnswerMetrics.citationPrecision(evalCase.citedChunkIds(),
                    Set.copyOf(evalCase.retrievedChunkIds()));
            if (citation != null) {
                citations.add(citation);
            }

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("id", evalCase.id());
            report.put("category", evalCase.category());
            report.put("faithfulness", Double.isNaN(faith) ? null : faith);
            report.put("answerRelevance", Double.isNaN(rel) ? null : rel);
            report.put("citationPrecision", citation);
            report.put("refusal", refusal);
            report.put("expectRefusal", evalCase.expectRefusal());
            caseReports.add(report);
        }

        double avgFaith = AnswerMetrics.average(faithfulness);
        double avgRel = AnswerMetrics.average(relevance);
        double avgCitation = AnswerMetrics.average(citations);
        double refusalAccuracy = AnswerMetrics.refusalAccuracy(predictedRefusal, expectedRefusal);
        assertTrue(!Double.isNaN(avgFaith), "忠真度判分全部失败，请检查模型与网络");
        assertTrue(!Double.isNaN(avgRel), "相关性判分全部失败，请检查模型与网络");

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("version", dataset.version());
        report.put("mode", "online");
        report.put("judgeModel", model);
        report.put("evaluatedCases", dataset.cases().size());
        report.put("faithfulness", avgFaith);
        report.put("answerRelevance", avgRel);
        report.put("citationPrecision", avgCitation);
        report.put("refusalAccuracy", refusalAccuracy);
        report.put("cases", caseReports);
        write(report);

        Baseline baseline = dataset.baseline();
        assertTrue(avgFaith + 1e-9 >= baseline.faithfulness(), "Faithfulness 相对基线回退: " + avgFaith);
        assertTrue(avgRel + 1e-9 >= baseline.answerRelevance(), "Answer Relevance 相对基线回退: " + avgRel);
        assertTrue(avgCitation + 1e-9 >= baseline.citationPrecision(),
                "Citation Precision 相对基线回退: " + avgCitation);
        assertTrue(refusalAccuracy + 1e-9 >= baseline.refusalAccuracy(),
                "拒答正确率相对基线回退: " + refusalAccuracy);
    }

    private void write(Map<String, Object> report) throws Exception {
        Files.createDirectories(REPORT.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(REPORT.toFile(), report);
    }

    private Dataset load() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/eval/answer-dataset.json")) {
            assertNotNull(in, "缺少固定评测集 /eval/answer-dataset.json");
            return MAPPER.readValue(in, Dataset.class);
        }
    }

    record Dataset(String version, String judgeModel, String note, List<Case> cases, Baseline baseline) {
    }

    record Case(String id, String category, String query, String answer, List<String> contexts,
                List<Long> citedChunkIds, List<Long> retrievedChunkIds, boolean expectRefusal) {
    }

    record Baseline(double faithfulness, double answerRelevance,
                    double citationPrecision, double refusalAccuracy) {
    }
}
