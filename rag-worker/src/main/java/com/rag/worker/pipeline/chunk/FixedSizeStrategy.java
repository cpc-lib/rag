package com.rag.worker.pipeline.chunk;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * FIXED_SIZE（指南 §4）：固定 token 窗口硬切，相邻窗口复制尾部 overlap。
 * 最简单稳定，作为兜底策略。
 */
@Component
@RequiredArgsConstructor
public class FixedSizeStrategy implements ChunkStrategy {

    private final ChunkSupport support;

    @Override
    public ChunkMode mode() {
        return ChunkMode.FIXED_SIZE;
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
            List<PlannedChild> children = new ArrayList<>();
            String prevWindow = null;
            for (String window : support.hardSplit(text, size)) {
                String content = (overlap > 0 && prevWindow != null)
                        ? support.tail(prevWindow, overlap) + "\n" + window
                        : window;
                children.add(new PlannedChild(page.page(), content));
                prevWindow = window;
            }
            plans.add(ChunkPlan.independent(null, null, children));
        }
        return plans;
    }
}
