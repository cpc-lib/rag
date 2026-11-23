package com.rag.api.eval;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RAG-009 检索离线指标：Recall@K、Precision@K、MRR@K、nDCG@K。
 * 纯函数实现，不依赖 ES / Milvus / LLM，可在 CI 中稳定复现。
 */
public final class RetrievalMetrics {

    private RetrievalMetrics() {
    }

    /** 召回率：Top-K 命中的相关条目数 / 全部相关条目数。 */
    public static double recallAtK(List<Long> ranked, Set<Long> relevant, int k) {
        if (relevant.isEmpty() || k <= 0) {
            return 0;
        }
        long hit = ranked.stream().limit(k).filter(relevant::contains).distinct().count();
        return (double) hit / relevant.size();
    }

    /** 精确率：Top-K 中相关条目数 / K（返回不足 K 条时按缺失计为不相关，口径固定便于横向比较）。 */
    public static double precisionAtK(List<Long> ranked, Set<Long> relevant, int k) {
        if (k <= 0) {
            return 0;
        }
        long hit = ranked.stream().limit(k).filter(relevant::contains).distinct().count();
        return (double) hit / k;
    }

    /** 平均倒数排名：第一个相关结果排名的倒数。 */
    public static double mrrAtK(List<Long> ranked, Set<Long> relevant, int k) {
        for (int i = 0; i < Math.min(k, ranked.size()); i++) {
            if (relevant.contains(ranked.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0;
    }

    /** 归一化折损累计增益：支持分级相关度（gain 越大越相关）。 */
    public static double ndcgAtK(List<Long> ranked, Map<Long, Double> gains, int k) {
        double dcg = 0;
        for (int i = 0; i < Math.min(k, ranked.size()); i++) {
            double gain = gains.getOrDefault(ranked.get(i), 0.0);
            dcg += (Math.pow(2, gain) - 1) / log2(i + 2);
        }
        List<Double> ideal = gains.values().stream()
                .filter(g -> g > 0)
                .sorted(Comparator.reverseOrder())
                .limit(Math.max(0, k))
                .toList();
        double idcg = 0;
        for (int i = 0; i < ideal.size(); i++) {
            idcg += (Math.pow(2, ideal.get(i)) - 1) / log2(i + 2);
        }
        return idcg == 0 ? 0 : dcg / idcg;
    }

    public static double average(List<Double> values) {
        return values.isEmpty() ? 0
                : values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static double log2(int value) {
        return Math.log(value) / Math.log(2);
    }
}
