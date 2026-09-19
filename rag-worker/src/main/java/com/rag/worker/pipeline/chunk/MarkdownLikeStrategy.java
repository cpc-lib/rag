package com.rag.worker.pipeline.chunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 族共享流程（MARKDOWN §8 / HTML §35）：块提取 → 按标题章节分组
 * → 章节内表格/代码原子保护、段落递归 → 打包为独立可检索 child。
 */
public abstract class MarkdownLikeStrategy implements ChunkStrategy {

    protected final ChunkSupport support;

    protected MarkdownLikeStrategy(ChunkSupport support) {
        this.support = support;
    }

    @Override
    public List<ChunkPlan> plan(ChunkContext ctx) {
        int size = ctx.params().childSize();
        int overlap = ctx.params().overlap();
        List<ChunkSupport.Block> all = new ArrayList<>();
        for (var page : ctx.pages()) {
            String text = page.text();
            if (text == null || text.isBlank()) {
                continue;
            }
            all.addAll(support.extractBlocks(page.page(), text));
        }
        List<ChunkPlan> plans = new ArrayList<>();
        for (ChunkSupport.Section sec : support.sections(all)) {
            List<ChunkSupport.PackPiece> pieces =
                    support.sectionPieces(sec.blocks(), size, ctx.params().separators());
            List<PlannedChild> children = support.pack(pieces, size, overlap);
            if (!children.isEmpty()) {
                plans.add(ChunkPlan.independent(sec.title(), sec.path(), children));
            }
        }
        return plans;
    }
}
