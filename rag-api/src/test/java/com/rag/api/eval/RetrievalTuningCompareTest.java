package com.rag.api.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.api.application.RetrievalService;
import com.rag.api.infrastructure.llm.RerankClient;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG-009 调优前后对比：Top-K 档位、Rerank 开/关、切片策略（切片对比见 rag-worker
 * {@code ChunkStrategyCompareTest}）。本测试离线复用生产 RRF 排序与指标口径，输出
 * {@code target/eval/tuning-compare.json}。
 *
 * <p>Rerank 默认使用确定性词法精排（可复现、无需模型），报告标注 source=offline-lexical；
 * 配置环境变量 {@code RAG_EVAL_RERANK_BASE_URL/API_KEY/MODEL} 后改用真实精排模型，
 * 报告标注 source=online，失败时回退词法并记录 error。</p>
 */
class RetrievalTuningCompareTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern ASCII_TOKEN = Pattern.compile("[a-z0-9_]{2,}");

    @Test
    void topKAndRerankComparisonIsReproducible() throws Exception {
        List<Map<String, Object>> topKSweep = topKSweep();
        Map<String, Object> rerank = rerankComparison();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedFrom", "rag-api/src/test/resources/eval/{retrieval,rerank}-dataset.json");
        report.put("topKSweep", topKSweep);
        report.put("rerank", rerank);
        Path out = Path.of("target", "eval", "tuning-compare.json");
        Files.createDirectories(out.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), report);

        // Top-K 单调性：候选集合不变时，K 越大召回不应下降
        double previous = -1;
        for (Map<String, Object> row : topKSweep) {
            double recall = (double) row.get("recall");
            assertTrue(recall + 1e-9 >= previous, "Recall 应随 K 增大单调不降");
            previous = recall;
        }
        // 离线词法精排应提升排序质量；在线模型结果只记录不强制断言
        if ("offline-lexical".equals(rerank.get("source"))) {
            double rrfNdcg = (double) rerank.get("rrfNdcg");
            double rerankNdcg = (double) rerank.get("rerankNdcg");
            assertTrue(rerankNdcg + 1e-9 >= rrfNdcg,
                    "词法精排 nDCG 不应低于 RRF: " + rrfNdcg + " -> " + rerankNdcg);
        }
    }

    // ---------------------------------------------------------------- Top-K 档位

    private List<Map<String, Object>> topKSweep() throws Exception {
        RetrievalBaselineTest.Dataset dataset = loadRetrieval();
        List<Integer> ks = List.of(3, 5, 8, 10);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int k : ks) {
            List<Double> recalls = new ArrayList<>();
            List<Double> precisions = new ArrayList<>();
            List<Double> mrrs = new ArrayList<>();
            List<Double> ndcgs = new ArrayList<>();
            for (RetrievalBaselineTest.Case evalCase : dataset.cases()) {
                List<Long> ranked = RetrievalService.rrfFuse(
                        evalCase.vector(), evalCase.keyword(), dataset.rrfK(), Math.max(20, k));
                Set<Long> relevant = evalCase.relevant().stream().map(RetrievalBaselineTest.Gain::id)
                        .collect(Collectors.toSet());
                if (relevant.isEmpty()) {
                    continue;
                }
                Map<Long, Double> gains = evalCase.relevant().stream()
                        .collect(Collectors.toMap(RetrievalBaselineTest.Gain::id,
                                g -> (double) g.gain()));
                recalls.add(RetrievalMetrics.recallAtK(ranked, relevant, k));
                precisions.add(RetrievalMetrics.precisionAtK(ranked, relevant, k));
                mrrs.add(RetrievalMetrics.mrrAtK(ranked, relevant, k));
                ndcgs.add(RetrievalMetrics.ndcgAtK(ranked, gains, k));
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("k", k);
            row.put("evaluatedCases", recalls.size());
            row.put("recall", round(RetrievalMetrics.average(recalls)));
            row.put("precision", round(RetrievalMetrics.average(precisions)));
            row.put("mrr", round(RetrievalMetrics.average(mrrs)));
            row.put("ndcg", round(RetrievalMetrics.average(ndcgs)));
            rows.add(row);
        }
        return rows;
    }

    // ---------------------------------------------------------------- Rerank 前后

    private Map<String, Object> rerankComparison() throws Exception {
        RerankDataset dataset = loadRerank();
        List<Double> rrfNdcgs = new ArrayList<>();
        List<Double> rerankNdcgs = new ArrayList<>();
        List<Double> rrfMrrs = new ArrayList<>();
        List<Double> rerankMrrs = new ArrayList<>();
        List<Map<String, Object>> caseRows = new ArrayList<>();

        String baseUrl = env("RAG_EVAL_RERANK_BASE_URL");
        String apiKey = env("RAG_EVAL_RERANK_API_KEY");
        String model = env("RAG_EVAL_RERANK_MODEL");
        boolean online = notBlank(baseUrl) && notBlank(model);
        String source = online ? "online:" + model : "offline-lexical";
        String error = null;

        for (RerankCase evalCase : dataset.cases()) {
            List<Long> rrfOrder = RetrievalService.rrfFuse(
                    evalCase.vector(), evalCase.keyword(), dataset.rrfK(), dataset.candidateK());
            Map<Long, String> texts = evalCase.candidates().stream()
                    .collect(Collectors.toMap(Candidate::id, Candidate::text));
            List<String> documents = rrfOrder.stream()
                    .map(id -> texts.getOrDefault(id, "")).toList();
            List<Long> rerankOrder;
            String caseSource = source;
            if (online) {
                try {
                    rerankOrder = onlineRerank(baseUrl, apiKey, model, evalCase.query(),
                            rrfOrder, documents);
                } catch (Exception e) {
                    error = e.getClass().getSimpleName() + ": " + e.getMessage();
                    caseSource = "offline-lexical";
                    rerankOrder = lexicalRerank(evalCase.query(), rrfOrder, texts);
                }
            } else {
                rerankOrder = lexicalRerank(evalCase.query(), rrfOrder, texts);
            }
            assertEquals(rrfOrder.size(), rerankOrder.size(), "精排不应丢失候选");
            assertEquals(Set.copyOf(rrfOrder), Set.copyOf(rerankOrder), "精排不应增删候选");

            Set<Long> relevant = evalCase.relevant().stream().map(Gain::id)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Map<Long, Double> gains = evalCase.relevant().stream()
                    .collect(Collectors.toMap(Gain::id, g -> (double) g.gain()));
            int k = dataset.topK();
            double rrfNdcg = RetrievalMetrics.ndcgAtK(rrfOrder, gains, k);
            double rerankNdcg = RetrievalMetrics.ndcgAtK(rerankOrder, gains, k);
            double rrfMrr = RetrievalMetrics.mrrAtK(rrfOrder, relevant, k);
            double rerankMrr = RetrievalMetrics.mrrAtK(rerankOrder, relevant, k);
            rrfNdcgs.add(rrfNdcg);
            rerankNdcgs.add(rerankNdcg);
            rrfMrrs.add(rrfMrr);
            rerankMrrs.add(rerankMrr);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", evalCase.id());
            row.put("query", evalCase.query());
            row.put("rrfOrder", rrfOrder);
            row.put("rerankOrder", rerankOrder);
            row.put("rrfNdcg", round(rrfNdcg));
            row.put("rerankNdcg", round(rerankNdcg));
            row.put("source", caseSource);
            caseRows.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", error == null ? source : source + "（部分回退）");
        result.put("error", error);
        result.put("rrfK", dataset.rrfK());
        result.put("candidateK", dataset.candidateK());
        result.put("topK", dataset.topK());
        result.put("rrfNdcg", round(RetrievalMetrics.average(rrfNdcgs)));
        result.put("rerankNdcg", round(RetrievalMetrics.average(rerankNdcgs)));
        result.put("rrfMrr", round(RetrievalMetrics.average(rrfMrrs)));
        result.put("rerankMrr", round(RetrievalMetrics.average(rerankMrrs)));
        result.put("cases", caseRows);
        return result;
    }

    private List<Long> onlineRerank(String baseUrl, String apiKey, String model, String query,
                                    List<Long> candidateIds, List<String> documents) throws Exception {
        RerankClient client = new RerankClient(MAPPER);
        var timeout = RerankClient.class.getDeclaredField("timeoutSeconds");
        timeout.setAccessible(true);
        timeout.setInt(client, 30);
        List<RerankClient.RerankHit> hits = client.rerank(baseUrl, apiKey, model, query,
                documents, candidateIds.size());
        List<Long> ordered = hits.stream().map(h -> candidateIds.get(h.index()))
                .collect(Collectors.toCollection(ArrayList::new));
        // 精排未返回的候选按原 RRF 顺序补齐，保证候选集合不变
        for (Long id : candidateIds) {
            if (!ordered.contains(id)) {
                ordered.add(id);
            }
        }
        return ordered;
    }

    /**
     * 确定性词法精排：查询与候选按 ASCII 词 + 中文二元组切词，按命中词长度和 /
     * sqrt(候选长度) 打分，同分保持 RRF 原序。用于无模型环境下的可复现对比。
     */
    private List<Long> lexicalRerank(String query, List<Long> rrfOrder, Map<Long, String> texts) {
        Set<String> queryTokens = tokens(query);
        List<Long> ordered = new ArrayList<>(rrfOrder);
        Map<Long, Double> scores = new LinkedHashMap<>();
        for (Long id : ordered) {
            String text = texts.getOrDefault(id, "");
            double matched = 0;
            for (String token : queryTokens) {
                if (text.toLowerCase(Locale.ROOT).contains(token)) {
                    matched += token.length();
                }
            }
            scores.put(id, matched / Math.sqrt(Math.max(1, text.length())));
        }
        ordered.sort((a, b) -> {
            int cmp = Double.compare(scores.get(b), scores.get(a));
            return cmp != 0 ? cmp : Integer.compare(rrfOrder.indexOf(a), rrfOrder.indexOf(b));
        });
        return ordered;
    }

    private Set<String> tokens(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        String lower = text.toLowerCase(Locale.ROOT);
        Matcher matcher = ASCII_TOKEN.matcher(lower);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        for (int i = 0; i + 1 < lower.length(); i++) {
            if (isCjk(lower.charAt(i)) && isCjk(lower.charAt(i + 1))) {
                tokens.add(lower.substring(i, i + 2));
            }
        }
        return tokens;
    }

    private boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    private String env(String key) {
        String value = System.getenv(key);
        return value == null ? System.getProperty(key) : value;
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    // ---------------------------------------------------------------- 数据集

    private RetrievalBaselineTest.Dataset loadRetrieval() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/eval/retrieval-dataset.json")) {
            assertNotNull(in, "缺少 /eval/retrieval-dataset.json");
            return MAPPER.readValue(in, RetrievalBaselineTest.Dataset.class);
        }
    }

    private RerankDataset loadRerank() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/eval/rerank-dataset.json")) {
            assertNotNull(in, "缺少 /eval/rerank-dataset.json");
            return MAPPER.readValue(in, RerankDataset.class);
        }
    }

    record RerankDataset(String version, int rrfK, int candidateK, int topK, String description,
                         List<RerankCase> cases) {
    }

    record RerankCase(String id, String query, List<Long> vector, List<Long> keyword,
                      List<Candidate> candidates, List<Gain> relevant) {
    }

    record Candidate(long id, String text) {
    }

    record Gain(long id, int gain) {
    }
}
