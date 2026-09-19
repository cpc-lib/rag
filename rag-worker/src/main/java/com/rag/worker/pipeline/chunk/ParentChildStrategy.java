package com.rag.worker.pipeline.chunk;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * PARENT_CHILD（指南 §10/12/21/28）：H1/H2 为 parent 边界，parent 完整内容留存，
 * children(400, overlap 60) 仅 child 进向量库；检索命中 child 后扩展到 parent。
 * 人工编辑 child 脱离 parent、删除 child 解散 parent 由 API 侧保证。
 */
@Component
@RequiredArgsConstructor
public class ParentChildStrategy implements ChunkStrategy {

    private final ChunkSupport support;

    @Override
    public ChunkMode mode() {
        return ChunkMode.PARENT_CHILD;
    }

    @Override
    public List<ChunkPlan> plan(ChunkContext ctx) {
        int parentSize = ctx.params().parentSize();
        int childSize = ctx.params().childSize();
        int childOverlap = ctx.params().overlap();

        List<String> titleStack = new ArrayList<>();
        List<Integer> levelStack = new ArrayList<>();
        List<ChunkSupport.Block> unitBlocks = new ArrayList<>();
        int unitPage = 0;
        String unitPath = "";
        String unitTitle = null;
        List<ChunkPlan> plans = new ArrayList<>();

        for (var page : ctx.pages()) {
            String text = page.text();
            if (text == null || text.isBlank()) {
                continue;
            }
            for (ChunkSupport.Block block : support.extractBlocks(page.page(), text)) {
                if (block.kind() == ChunkSupport.Kind.HEADING) {
                    while (!levelStack.isEmpty() && levelStack.get(levelStack.size() - 1) >= block.level()) {
                        levelStack.remove(levelStack.size() - 1);
                        titleStack.remove(titleStack.size() - 1);
                    }
                    levelStack.add(block.level());
                    titleStack.add(support.headingTitle(block.text()));
                    if (block.level() <= 2 && !unitBlocks.isEmpty()) {
                        plans.add(finalizeUnit(ctx, unitPage, unitTitle, unitPath,
                                unitBlocks, childSize, childOverlap));
                        unitBlocks.clear();
                    }
                    if (unitBlocks.isEmpty()) {
                        unitPage = block.page();
                        unitPath = String.join(" > ", titleStack);
                        unitTitle = titleStack.get(titleStack.size() - 1);
                    }
                    unitBlocks.add(block);
                    continue;
                }

                for (ChunkSupport.Block piece : splitOversized(block, parentSize, ctx.params().separators())) {
                    if (!unitBlocks.isEmpty()
                            && support.tokens(support.joinBlocks(unitBlocks)) + support.tokens(piece.text()) > parentSize) {
                        plans.add(finalizeUnit(ctx, unitPage, unitTitle, unitPath,
                                unitBlocks, childSize, childOverlap));
                        unitBlocks.clear();
                    }
                    if (unitBlocks.isEmpty()) {
                        unitPage = piece.page();
                        unitPath = String.join(" > ", titleStack);
                        unitTitle = titleStack.isEmpty() ? null : titleStack.get(titleStack.size() - 1);
                    }
                    unitBlocks.add(piece);
                }
            }
        }
        if (!unitBlocks.isEmpty()) {
            plans.add(finalizeUnit(ctx, unitPage, unitTitle, unitPath,
                    unitBlocks, childSize, childOverlap));
        }
        return plans;
    }

    private ChunkPlan finalizeUnit(ChunkContext ctx, int page, String title, String path,
                                   List<ChunkSupport.Block> blocks, int childSize, int childOverlap) {
        String parentContent = support.joinBlocks(blocks);
        // children 保留标题行（作为非原子正文片），表格/代码原子保护
        List<ChunkSupport.PackPiece> pieces = new ArrayList<>();
        for (ChunkSupport.Block b : blocks) {
            switch (b.kind()) {
                case HEADING -> pieces.add(ChunkSupport.PackPiece.text(b.page(), b.text()));
                default -> pieces.addAll(
                        support.sectionPieces(List.of(b), childSize, ctx.params().separators()));
            }
        }
        List<PlannedChild> children = support.pack(pieces, childSize, childOverlap);
        String hash = support.sha256(ctx.documentId() + "|" + (path == null ? "" : path)
                + "|" + support.normalized(parentContent));
        return ChunkPlan.parentChild(title, path, hash, parentContent, children);
    }

    private List<ChunkSupport.Block> splitOversized(ChunkSupport.Block block, int maxSize,
                                                    List<String> separators) {
        if (support.tokens(block.text()) <= maxSize) {
            return List.of(block);
        }
        List<String> texts = switch (block.kind()) {
            case TABLE -> support.splitTable(block.text(), maxSize);
            case CODE -> support.splitCode(block.text(), maxSize);
            case PARAGRAPH -> support.recursive(block.text(), separators).stream()
                    .flatMap(p -> support.hardSplit(p, maxSize).stream()).toList();
            case HEADING -> List.of(block.text());
        };
        List<ChunkSupport.Block> out = new ArrayList<>();
        for (String t : texts) {
            out.add(new ChunkSupport.Block(block.page(), block.kind(), block.level(), t));
        }
        return out;
    }
}
