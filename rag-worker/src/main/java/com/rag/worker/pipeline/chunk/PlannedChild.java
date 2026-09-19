package com.rag.worker.pipeline.chunk;

/** 一个可检索单元（child）：页码 + 内容。 */
public record PlannedChild(int page, String content) {
}
