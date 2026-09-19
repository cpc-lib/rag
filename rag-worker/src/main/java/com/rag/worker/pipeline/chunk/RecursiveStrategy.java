package com.rag.worker.pipeline.chunk;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * RECURSIVE（指南 §5）：按分隔符优先级递归切分（段落→行→句读→标点），
 * 聚合到 childSize，相邻块用正文尾部 overlap。
 */
@Component
@RequiredArgsConstructor
public class RecursiveStrategy implements ChunkStrategy {

    private final ChunkSupport support;

    @Override
    public ChunkMode mode() {
        return ChunkMode.RECURSIVE;
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
            List<ChunkSupport.PackPiece> pieces = new ArrayList<>();
            for (String part : support.recursive(text, ctx.params().separators())) {
                for (String bounded : support.hardSplit(part, size)) {
                    pieces.add(ChunkSupport.PackPiece.text(page.page(), bounded));
                }
            }
            plans.add(ChunkPlan.independent(null, null, support.pack(pieces, size, overlap)));
        }
        return plans;
    }
}
