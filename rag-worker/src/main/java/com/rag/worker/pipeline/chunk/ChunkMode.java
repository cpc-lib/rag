package com.rag.worker.pipeline.chunk;

/** 切片策略枚举（指南 §3）。AUTO 不对应 bean，由 ChunkStrategyRouter 解析为具体策略。 */
public enum ChunkMode {
    FIXED_SIZE,
    RECURSIVE,
    PARAGRAPH,
    SENTENCE,
    SEMANTIC,
    STRUCTURE,
    MARKDOWN,
    HTML,
    PDF_LAYOUT,
    TABLE,
    QA,
    PARENT_CHILD,
    SLIDING_WINDOW,
    CODE,
    AUTO
}
