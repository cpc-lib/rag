package com.rag.worker.pipeline.chunk;

import org.springframework.stereotype.Component;

/**
 * HTML（指南 §35）：ParseService 已把 DOM 归一化为 Markdown 结构文本
 * （标题 #、表格 md、pre 围栏、列表），切片逻辑同 Markdown 族。
 */
@Component
public class HtmlStrategy extends MarkdownLikeStrategy {

    public HtmlStrategy(ChunkSupport support) {
        super(support);
    }

    @Override
    public ChunkMode mode() {
        return ChunkMode.HTML;
    }
}
