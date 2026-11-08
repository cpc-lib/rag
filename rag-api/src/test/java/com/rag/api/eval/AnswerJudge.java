package com.rag.api.eval;

import java.util.List;

/**
 * RAG-009 答案语义指标判分器。在线实现调用 CHAT 模型打分，失败返回 {@link Double#NaN}
 * 由报告记录，不阻断整批评测。
 */
public interface AnswerJudge {

    /** 忠实度：答案中的事实是否都能被检索上下文支撑，返回 0~1。 */
    double faithfulness(String query, String answer, List<String> contexts);

    /** 答案相关性：答案是否直接回应问题（不评价事实正确性），返回 0~1。 */
    double answerRelevance(String query, String answer);
}
