package com.rag.worker.pipeline.chunk;

import com.rag.worker.pipeline.TokenCounter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * SLIDING_WINDOW（指南 §3）：token 精确的滑动窗口，窗口按固定步长
 * (= childSize - overlap) 前移；与 FIXED_SIZE 的"硬切+复制尾部"不同，
 * 窗口边界连续滑动、重叠精确。
 */
@Component
@RequiredArgsConstructor
public class SlidingWindowStrategy implements ChunkStrategy {

    private final ChunkSupport support;

    @Override
    public ChunkMode mode() {
        return ChunkMode.SLIDING_WINDOW;
    }

    @Override
    public List<ChunkPlan> plan(ChunkContext ctx) {
        int size = ctx.params().childSize();
        int step = size - ctx.params().overlap();
        List<ChunkPlan> plans = new ArrayList<>();
        for (var page : ctx.pages()) {
            String text = page.text();
            if (text == null || text.isBlank()) {
                continue;
            }
            List<PlannedChild> children = new ArrayList<>();
            int start = 0;
            while (start < text.length()) {
                int end = indexAtTokenBudget(text, start, size);
                if (end <= start) {
                    end = Math.min(start + 1, text.length());
                }
                String window = text.substring(start, end).strip();
                if (!window.isEmpty()) {
                    children.add(new PlannedChild(page.page(), window));
                }
                if (end >= text.length()) {
                    break;
                }
                int next = indexAtTokenBudget(text, start, step);
                start = next > start ? next : end;
            }
            plans.add(ChunkPlan.independent(null, null, children));
        }
        return plans;
    }

    /** 从 from 起消费 budget 个 token 后对应的字符下标（口径同 TokenCounter）。 */
    private int indexAtTokenBudget(String text, int from, int budget) {
        double remain = budget;
        int i = from;
        while (i < text.length() && remain > 0) {
            char c = text.charAt(i);
            if (TokenCounter.isAsciiAlnum(c)) {
                remain -= 0.25;
            } else if (!Character.isWhitespace(c)) {
                remain -= 1;
            }
            i++;
        }
        return i;
    }
}
