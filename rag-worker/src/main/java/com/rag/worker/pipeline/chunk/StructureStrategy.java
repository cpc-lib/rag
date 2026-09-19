package com.rag.worker.pipeline.chunk;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * STRUCTURE（指南 §9/10）：在 Markdown 标题之外，识别通用结构化编号标题
 * （第X章/节/部分、中文数字"一、"、阿拉伯数字 1 / 1.1 / 1.1.1），
 * 维护章节树 section_path；代码/表格仍整体保护。适用于 Word 导出的纯文本编号标题。
 */
@Component
@RequiredArgsConstructor
public class StructureStrategy implements ChunkStrategy {

    private static final Pattern CN_CHAPTER = Pattern.compile(
            "^第[一二三四五六七八九十百零〇0-9]+(?:章|部分|篇)(?:[、.\\s].*)?$");
    private static final Pattern CN_SECTION = Pattern.compile(
            "^第[一二三四五六七八九十百零〇0-9]+节(?:[、.\\s].*)?$");
    private static final Pattern CN_NUMERAL = Pattern.compile(
            "^[一二三四五六七八九十百]+、\\s*\\S");
    private static final Pattern NUM_PUNCT = Pattern.compile(
            "^(\\d{1,2}(?:\\.\\d{1,2}){0,3})\\s*[、.]\\s*\\S");
    private static final Pattern NUM_DOTTED = Pattern.compile(
            "^(\\d{1,2}(?:\\.\\d{1,2}){1,3})\\s+\\S");

    private final ChunkSupport support;

    @Override
    public ChunkMode mode() {
        return ChunkMode.STRUCTURE;
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
            for (ChunkSupport.Block b : support.extractBlocks(page.page(), text)) {
                if (b.kind() == ChunkSupport.Kind.PARAGRAPH) {
                    all.addAll(splitStructuralHeadings(b));
                } else {
                    all.add(b);
                }
            }
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

    /** 在段落块内部按行寻找编号标题，拆成"段落片 + HEADING 块"。 */
    private List<ChunkSupport.Block> splitStructuralHeadings(ChunkSupport.Block block) {
        List<ChunkSupport.Block> out = new ArrayList<>();
        List<String> paraBuf = new ArrayList<>();
        for (String line : block.text().split("\n", -1)) {
            Integer level = headingLevel(line);
            if (level != null) {
                if (!paraBuf.isEmpty()) {
                    out.add(new ChunkSupport.Block(block.page(), ChunkSupport.Kind.PARAGRAPH,
                            0, String.join("\n", paraBuf).strip()));
                    paraBuf.clear();
                }
                out.add(new ChunkSupport.Block(block.page(), ChunkSupport.Kind.HEADING,
                        level, "#".repeat(level) + " " + line.strip()));
            } else {
                paraBuf.add(line);
            }
        }
        if (!paraBuf.isEmpty()) {
            out.add(new ChunkSupport.Block(block.page(), ChunkSupport.Kind.PARAGRAPH,
                    0, String.join("\n", paraBuf).strip()));
        }
        return out;
    }

    /** 返回编号标题级别；不是标题返回 null。 */
    private Integer headingLevel(String line) {
        String s = line.strip();
        if (s.length() > 60) {
            return null;
        }
        if (CN_CHAPTER.matcher(s).find()) {
            return 1;
        }
        if (CN_SECTION.matcher(s).find()) {
            return 2;
        }
        if (CN_NUMERAL.matcher(s).find()) {
            return 1;
        }
        Matcher m = NUM_PUNCT.matcher(s);
        if (m.find()) {
            return m.group(1).split("\\.").length;
        }
        m = NUM_DOTTED.matcher(s);
        if (m.find()) {
            return m.group(1).split("\\.").length;
        }
        return null;
    }
}
