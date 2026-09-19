package com.rag.worker.pipeline.chunk;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * PARAGRAPH（指南 §6）：段落为单位；过短段落（&lt;150 tokens）与相邻段落合并，
 * 过长段落递归切分；再统一打包。
 */
@Component
@RequiredArgsConstructor
public class ParagraphStrategy implements ChunkStrategy {

    private static final int MIN_CHUNK_TOKENS = 150;

    private final ChunkSupport support;

    @Override
    public ChunkMode mode() {
        return ChunkMode.PARAGRAPH;
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
            // 小段落合并：累计到 ≥MIN 后断组；超长段落递归拆分
            List<String> merged = new ArrayList<>();
            String acc = null;
            for (String para : support.paragraphs(text)) {
                if (support.tokens(para) > size) {
                    if (acc != null) {
                        merged.add(acc);
                        acc = null;
                    }
                    merged.addAll(support.recursive(para, ctx.params().separators()));
                    continue;
                }
                if (acc != null && support.tokens(acc) >= MIN_CHUNK_TOKENS) {
                    merged.add(acc);
                    acc = para;
                } else {
                    acc = acc == null ? para : acc + "\n" + para;
                }
            }
            if (acc != null) {
                merged.add(acc);
            }

            List<ChunkSupport.PackPiece> pieces = new ArrayList<>();
            for (String m : merged) {
                for (String bounded : support.hardSplit(m, size)) {
                    pieces.add(ChunkSupport.PackPiece.text(page.page(), bounded));
                }
            }
            plans.add(ChunkPlan.independent(null, null, support.pack(pieces, size, overlap)));
        }
        return plans;
    }
}
