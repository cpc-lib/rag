package com.rag.worker.pipeline.chunk;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * QA（指南 §14）：问答对为单位。识别 Q:/问:/数字编号问句开头，
 * 问句与其后的答案（可跨段落）合为一个切片，绝不拆散问答对。
 */
@Component
public class QaStrategy implements ChunkStrategy {

    private static final Pattern QUESTION_START = Pattern.compile(
            "^\\s*(?:\\d{1,3}[.、]\\s*)?(?:Q|问)\\s*[:：]");

    @Override
    public ChunkMode mode() {
        return ChunkMode.QA;
    }

    @Override
    public List<ChunkPlan> plan(ChunkContext ctx) {
        List<ChunkPlan> plans = new ArrayList<>();
        for (var page : ctx.pages()) {
            String text = page.text();
            if (text == null || text.isBlank()) {
                continue;
            }
            List<PlannedChild> children = new ArrayList<>();
            List<String> pair = null;
            for (String line : text.split("\n", -1)) {
                if (QUESTION_START.matcher(line).find()) {
                    if (pair != null) {
                        children.add(new PlannedChild(page.page(), String.join("\n", pair).strip()));
                    }
                    pair = new ArrayList<>();
                    pair.add(line.strip());
                } else if (pair != null) {
                    pair.add(line);
                }
            }
            if (pair != null) {
                children.add(new PlannedChild(page.page(), String.join("\n", pair).strip()));
            }
            if (!children.isEmpty()) {
                plans.add(ChunkPlan.independent(null, null, children));
            }
        }
        return plans;
    }
}
