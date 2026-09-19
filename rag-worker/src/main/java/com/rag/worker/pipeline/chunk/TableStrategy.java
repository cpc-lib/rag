package com.rag.worker.pipeline.chunk;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * TABLE（指南 §16/17）：以表格为核心，表格整体保留、按行组切分时每片带表头；
 * 表格外文本按递归切片处理；同一页的切片归入一个独立计划。
 */
@Component
@RequiredArgsConstructor
public class TableStrategy implements ChunkStrategy {

    private final ChunkSupport support;

    @Override
    public ChunkMode mode() {
        return ChunkMode.TABLE;
    }

    @Override
    public List<ChunkPlan> plan(ChunkContext ctx) {
        int size = ctx.params().childSize();
        int overlap = ctx.params().overlap();
        List<ChunkPlan> plans = new ArrayList<>();
        for (var page : ctx.pages()) {
            String text = page.text();
            if (text == null || text.isBlank()) {
                continue;
            }
            List<ChunkSupport.PackPiece> pieces =
                    support.sectionPieces(support.extractBlocks(page.page(), text),
                            size, ctx.params().separators());
            List<PlannedChild> children = support.pack(pieces, size, overlap);
            if (!children.isEmpty()) {
                plans.add(ChunkPlan.independent(null, null, children));
            }
        }
        return plans;
    }
}
