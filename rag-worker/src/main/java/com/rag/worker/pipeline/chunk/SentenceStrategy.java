package com.rag.worker.pipeline.chunk;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * SENTENCE（指南 §7）：句子聚合到 childSize；相邻块重叠最近 N 个句子
 * （总 token ≤ overlap），句子级 overlap 更保持自然语义。
 */
@Component
@RequiredArgsConstructor
public class SentenceStrategy implements ChunkStrategy {

    private final ChunkSupport support;

    @Override
    public ChunkMode mode() {
        return ChunkMode.SENTENCE;
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
            List<String> window = new ArrayList<>();
            int windowTokens = 0;
            for (String s : support.sentences(text)) {
                int st = support.tokens(s);
                if (!window.isEmpty() && windowTokens + st > size) {
                    children.add(new PlannedChild(page.page(), String.join("\n", window)));
                    window = pickBackSentences(window, overlap);
                    windowTokens = window.stream().mapToInt(support::tokens).sum();
                }
                window.add(s);
                windowTokens += st;
            }
            if (!window.isEmpty()) {
                children.add(new PlannedChild(page.page(), String.join("\n", window)));
            }
            plans.add(ChunkPlan.independent(null, null, children));
        }
        return plans;
    }

    /** 从窗口尾部取总 token ≤ overlap 的句子；overlap>0 时至少保留最后一句。 */
    private List<String> pickBackSentences(List<String> window, int overlap) {
        if (overlap <= 0 || window.isEmpty()) {
            return List.of();
        }
        List<String> back = new ArrayList<>();
        int budget = 0;
        for (int i = window.size() - 1; i >= 0; i--) {
            int t = support.tokens(window.get(i));
            if (!back.isEmpty() && budget + t > overlap) {
                break;
            }
            back.add(0, window.get(i));
            budget += t;
        }
        return back;
    }
}
