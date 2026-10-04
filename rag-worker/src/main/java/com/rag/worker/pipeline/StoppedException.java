package com.rag.worker.pipeline;

/**
 * 用户主动停止流水线：Worker 在阶段边界/向量化批次间检测到停止标记后抛出。
 * 消费者捕获后直接 ack 并将任务置为 CANCELLED，不进入重试。
 */
public class StoppedException extends RuntimeException {

    public StoppedException(long documentId) {
        super("文档处理已被用户停止 doc=" + documentId);
    }
}
