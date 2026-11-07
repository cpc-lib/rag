package com.rag.worker.pipeline;

/**
 * 不可通过重试恢复的配置/输入错误（ISSUE RAG-011）：
 * 如未启用向量模型、模型名缺失、文件类型不受支持。
 * 消费者捕获后直接置 FAILED，不再消耗重试次数与 DLQ。
 */
public class NonRetryableException extends RuntimeException {

    public NonRetryableException(String message) {
        super(message);
    }

    public NonRetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
