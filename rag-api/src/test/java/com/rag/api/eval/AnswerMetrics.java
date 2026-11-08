package com.rag.api.eval;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * RAG-009 答案侧离线指标：引用精确率、拒答正确率与聚合。
 *
 * <p>Faithfulness / Answer Relevance 依赖语义判断，由 {@link AnswerJudge} 在线打分；
 * 本类只保留可确定性复现的口径，CI 与本地无模型时也能校验评测流程。</p>
 */
public final class AnswerMetrics {

    /** 判为拒答的常见措辞：命中且答案较短时视为拒答，避免长答案中的顺带提及被误判。 */
    private static final List<String> REFUSAL_PATTERNS = List.of(
            "无法回答", "无法确定", "无法从", "没有找到", "未找到", "未提及", "没有相关信息",
            "资料中没有", "知识库中没有", "不知道", "不足以回答", "抱歉，我无法", "无法提供");

    /** 拒答判定长度上限：超过该长度即使包含拒答措辞也视为正常回答。 */
    private static final int REFUSAL_MAX_LENGTH = 80;

    private AnswerMetrics() {
    }

    /**
     * 引用精确率：答案引用的 chunk 中属于本次检索结果的比例。
     * 引用为空视为 0（有答案却无引用），拒答样例不参与该指标。
     */
    public static double citationPrecision(List<Long> cited, Set<Long> retrieved) {
        if (cited == null || cited.isEmpty() || retrieved == null || retrieved.isEmpty()) {
            return 0;
        }
        long hit = cited.stream().distinct().filter(retrieved::contains).count();
        return (double) hit / cited.stream().distinct().count();
    }

    /** 是否拒答：空答案直接判拒答；否则命中拒答措辞且长度较短。 */
    public static boolean isRefusal(String answer) {
        if (answer == null || answer.isBlank()) {
            return true;
        }
        String trimmed = answer.strip();
        if (trimmed.length() > REFUSAL_MAX_LENGTH) {
            return false;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return REFUSAL_PATTERNS.stream().anyMatch(lower::contains);
    }

    /** 拒答正确率：预测拒答与期望拒答一致的比例（无答案问题应拒答，有答案问题不应误拒）。 */
    public static double refusalAccuracy(List<Boolean> predicted, List<Boolean> expected) {
        if (expected == null || expected.isEmpty() || predicted == null
                || predicted.size() != expected.size()) {
            return 0;
        }
        long correct = 0;
        for (int i = 0; i < expected.size(); i++) {
            boolean predictedRefusal = Boolean.TRUE.equals(predicted.get(i));
            boolean expectedRefusal = Boolean.TRUE.equals(expected.get(i));
            if (predictedRefusal == expectedRefusal) {
                correct++;
            }
        }
        return (double) correct / expected.size();
    }

    /** 均值：忽略 NaN（判分器调用失败的样例不计入分母）。 */
    public static double average(List<Double> values) {
        List<Double> valid = values.stream().filter(v -> v != null && !v.isNaN()).toList();
        return valid.isEmpty() ? Double.NaN
                : valid.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }
}
