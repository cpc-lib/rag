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
 * RAG-014 分层评测：按「中文自然语言 / 英文自然语言 / 数字 / 订单号 / 接口名」五层
 * 分别统计 Recall@5、MRR@5、nDCG@5，避免单一平均值掩盖某一类 Query 的回退。
 *
 * <p>数据集中的 vector / keyword 两路名次是离线固化样本：keyword 路已体现
 * {@code content.exact}（boost 2.0）对整串命中的加权效果——订单号、错误码、接口路径
 * 命中时位于关键词通道首位。评测只复用生产 {@link RetrievalService#rrfFuse} 的排序，
 * 不调用 ES / Milvus / LLM，可在 CI 稳定复现；真实语料调参仍需在线集。</p>
 *
 * <p>同时输出通道预算（vectorTopN / keywordTopN）敏感性报告：长尾样本的相关块位于
 * 关键词通道 rank 14、向量通道 rank 6，用于验证当前默认预算 20 是否覆盖，
 * 预算收紧到 10 时召回会掉，这一对比把「预算不足导致召回丢失」显式量化。</p>
 */
class RetrievalLayeredEvalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 分层评测必须覆盖的五类 Query。 */
    private static final Set<String> REQUIRED_LAYERS = Set.of(
            "中文自然语言", "英文自然语言", "数字", "订单号", "接口名");

    @Test
    void layeredMetricsMeetBaseline() throws Exception {
        Dataset dataset = load();
        assertEquals(REQUIRED_LAYERS,
                dataset.cases().stream().map(Case::layer).collect(Collectors.toCollection(LinkedHashSet::new)),
                "分层评测必须覆盖中文/英文/数字/订单号/接口名五层");
        assertEquals(REQUIRED_LAYERS,
                dataset.layers().stream().map(Layer::name).collect(Collectors.toSet()),
                "数据集分层声明与样本分类不一致");

        Map<String, List<Map<String, Object>>> caseReportsByLayer = new LinkedHashMap<>();
        Map<String, List<double[]>> metricByLayer = new LinkedHashMap<>();
        for (Case evalCase : dataset.cases()) {
            List<Long> ranked = RetrievalService.rrfFuse(
                    truncate(evalCase.vector(), dataset.vectorTopN()),
                    truncate(evalCase.keyword(), dataset.keywordTopN()),
                    dataset.rrfK(), dataset.topK());
            assertEquals(ranked, RetrievalService.rrfFuse(
                            truncate(evalCase.vector(), dataset.vectorTopN()),
                            truncate(evalCase.keyword(), dataset.keywordTopN()),
                            dataset.rrfK(), dataset.topK()),
                    "同输入 RRF 结果必须可复现");

            Set<Long> relevant = evalCase.relevant().stream().map(Gain::id).collect(Collectors.toSet());
            Map<Long, Double> gains = evalCase.relevant().stream()
                    .collect(Collectors.toMap(Gain::id, g -> (double) g.gain()));
            double recall = RetrievalMetrics.recallAtK(ranked, relevant, dataset.topK());
            double precision = RetrievalMetrics.precisionAtK(ranked, relevant, dataset.topK());
            double mrr = RetrievalMetrics.mrrAtK(ranked, relevant, dataset.topK());
            double ndcg = RetrievalMetrics.ndcgAtK(ranked, gains, dataset.topK());

            metricByLayer.computeIfAbsent(evalCase.layer(), k -> new ArrayList<>())
                    .add(new double[]{recall, precision, mrr, ndcg});

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("id", evalCase.id());
            report.put("layer", evalCase.layer());
            report.put("query", evalCase.query());
            report.put("ranked", ranked);
            report.put("relevant", relevant);
            report.put("recallAt5", recall);
            report.put("precisionAt5", precision);
            report.put("mrrAt5", mrr);
            report.put("ndcgAt5", ndcg);
            caseReportsByLayer.computeIfAbsent(evalCase.layer(), k -> new ArrayList<>()).add(report);
        }

        // 精确标识符层：content.exact 加权应保证整串命中位于关键词通道首位，且 RRF 后 top-1
        for (Layer layer : dataset.layers()) {
            if (!layer.exactIdentifier()) {
                continue;
            }
            for (Case evalCase : dataset.cases()) {
                if (!layer.name().equals(evalCase.layer())) {
                    continue;
                }
                long topGainId = evalCase.relevant().stream()
                        .max(java.util.Comparator.comparingInt(Gain::gain)).orElseThrow().id();
                assertEquals(topGainId, evalCase.keyword().get(0),
                        evalCase.id() + " 精确标识符应位于关键词通道首位（content.exact boost）");
            }
        }

        List<Map<String, Object>> layerReports = new ArrayList<>();
        for (Layer layer : dataset.layers()) {
            List<double[]> metrics = metricByLayer.get(layer.name());
            assertNotNull(metrics, "分层缺少样本: " + layer.name());
            double recall = RetrievalMetrics.average(metrics.stream().map(m -> m[0]).toList());
            double precision = RetrievalMetrics.average(metrics.stream().map(m -> m[1]).toList());
            double mrr = RetrievalMetrics.average(metrics.stream().map(m -> m[2]).toList());
            double ndcg = RetrievalMetrics.average(metrics.stream().map(m -> m[3]).toList());

            Map<String, Object> layerReport = new LinkedHashMap<>();
            layerReport.put("layer", layer.name());
            layerReport.put("cases", metrics.size());
            layerReport.put("exactIdentifier", layer.exactIdentifier());
            layerReport.put("recallAt5", recall);
            layerReport.put("precisionAt5", precision);
            layerReport.put("mrrAt5", mrr);
            layerReport.put("ndcgAt5", ndcg);
            layerReport.put("casesDetail", caseReportsByLayer.get(layer.name()));
            layerReports.add(layerReport);

            assertTrue(recall + 1e-9 >= layer.baselineRecallAt5(),
                    layer.name() + " Recall@5 相对分层基线回退: " + recall);
            assertTrue(mrr + 1e-9 >= layer.baselineMrrAt5(),
                    layer.name() + " MRR@5 相对分层基线回退: " + mrr);
            if (layer.exactIdentifier()) {
                assertEquals(1.0, mrr, 1e-9,
                        layer.name() + " 层精确标识符应排 top-1，MRR@5=" + mrr);
            }
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("version", dataset.version());
        report.put("dataset", "classpath:eval/retrieval-layered-dataset.json");
        report.put("rrfK", dataset.rrfK());
        report.put("topK", dataset.topK());
        report.put("vectorTopN", dataset.vectorTopN());
        report.put("keywordTopN", dataset.keywordTopN());
        report.put("embeddingModel", dataset.embeddingModel());
        report.put("rerank", dataset.rerank());
        report.put("layers", layerReports);
        report.put("channelBudgetSweep", budgetSweep(dataset));
        Path out = Path.of("target", "eval", "retrieval-layered.json");
        Files.createDirectories(out.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), report);
    }

    /**
     * 通道预算敏感性：对每个预算同时截断两路召回列表，输出整体指标。
     * 用于证明当前默认预算（20）足以覆盖长尾相关块，收紧到 10 及以下会丢召回。
     */
    private List<Map<String, Object>> budgetSweep(Dataset dataset) {
        List<Map<String, Object>> sweep = new ArrayList<>();
        double configuredRecall = 0;
        double smallerRecall = 0;
        for (int budget : dataset.channelBudgetSweep()) {
            List<Double> recalls = new ArrayList<>();
            List<Double> mrrs = new ArrayList<>();
            List<Double> ndcgs = new ArrayList<>();
            for (Case evalCase : dataset.cases()) {
                List<Long> ranked = RetrievalService.rrfFuse(
                        truncate(evalCase.vector(), budget),
                        truncate(evalCase.keyword(), budget),
                        dataset.rrfK(), dataset.topK());
                Set<Long> relevant = evalCase.relevant().stream().map(Gain::id).collect(Collectors.toSet());
                Map<Long, Double> gains = evalCase.relevant().stream()
                        .collect(Collectors.toMap(Gain::id, g -> (double) g.gain()));
                recalls.add(RetrievalMetrics.recallAtK(ranked, relevant, dataset.topK()));
                mrrs.add(RetrievalMetrics.mrrAtK(ranked, relevant, dataset.topK()));
                ndcgs.add(RetrievalMetrics.ndcgAtK(ranked, gains, dataset.topK()));
            }
            double recall = RetrievalMetrics.average(recalls);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("channelTopN", budget);
            row.put("recallAt5", recall);
            row.put("mrrAt5", RetrievalMetrics.average(mrrs));
            row.put("ndcgAt5", RetrievalMetrics.average(ndcgs));
            sweep.add(row);
            if (budget == dataset.vectorTopN() && budget == dataset.keywordTopN()) {
                configuredRecall = recall;
            }
            if (budget == 10) {
                smallerRecall = recall;
            }
        }
        assertTrue(smallerRecall > 0, "预算敏感性必须包含 10 档用于对比");
        assertTrue(configuredRecall >= smallerRecall - 1e-9,
                "收紧通道预算不应提升整体召回: 预算10=" + smallerRecall + " 预算20=" + configuredRecall);
        assertEquals(1.0, configuredRecall, 1e-9,
                "默认通道预算 " + dataset.vectorTopN() + " 应覆盖全部标注相关块");
        return sweep;
    }

    private List<Long> truncate(List<Long> ids, int limit) {
        return ids.size() <= limit ? ids : ids.subList(0, limit);
    }

    private Dataset load() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/eval/retrieval-layered-dataset.json")) {
            assertNotNull(in, "缺少分层评测集 /eval/retrieval-layered-dataset.json");
            return MAPPER.readValue(in, Dataset.class);
        }
    }

    record Dataset(String version, int rrfK, int topK, int vectorTopN, int keywordTopN,
                   String embeddingModel, String rerank, List<Integer> channelBudgetSweep,
                   List<Layer> layers, List<Case> cases) {
    }

    record Layer(String name, double baselineRecallAt5, double baselineMrrAt5, boolean exactIdentifier) {
    }

    record Case(String id, String layer, String query, List<Long> vector, List<Long> keyword,
                List<Gain> relevant, String note) {
    }

    record Gain(long id, int gain) {
    }
}
