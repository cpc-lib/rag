package com.rag.worker.pipeline.chunk;

import org.springframework.stereotype.Component;

/** MARKDOWN（指南 §8）：标题树 + 围栏代码/表格保护，单层独立切片。 */
@Component
public class MarkdownStrategy extends MarkdownLikeStrategy {

    public MarkdownStrategy(ChunkSupport support) {
        super(support);
    }

    @Override
    public ChunkMode mode() {
        return ChunkMode.MARKDOWN;
    }
}
