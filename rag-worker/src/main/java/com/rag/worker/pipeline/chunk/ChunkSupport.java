package com.rag.worker.pipeline.chunk;

import com.rag.worker.pipeline.ParseService;
import com.rag.worker.pipeline.TokenCounter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 策略共享底层能力：token 计量、递归/句子/段落切分、Markdown 块提取（围栏代码/表格保护）、
 * 表格保表头切片、代码按行切、统一打包（pack）与章节分组。
 */
@Component
@RequiredArgsConstructor
public class ChunkSupport {

    public enum Kind { HEADING, CODE, TABLE, PARAGRAPH }

    public record Block(int page, Kind kind, int level, String text) {
    }

    /** 打包输入：page 归属；atomic=true 时不参与 overlap（代码/表格）。 */
    public record PackPiece(int page, boolean atomic, String text) {
        public static PackPiece text(int page, String text) {
            return new PackPiece(page, false, text);
        }
    }

    /** 章节分组：叶子标题 + 完整路径 + 章节内块（不含标题块本身）。 */
    public record Section(String title, String path, List<Block> blocks) {
    }

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})[ \\t　]+(.+?)(?:[ \\t]+#+)?$");
    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~)");
    private static final Pattern TABLE_LINE = Pattern.compile("^\\s*\\|.*\\|\\s*$");
    private static final Pattern TABLE_SEP = Pattern.compile("^\\s*\\|?\\s*:?-{2,}.*$");
    private static final Pattern SENT_BOUND = Pattern.compile("(?<=[。！？!?])|\\n+");

    private final TokenCounter tokenCounter;

    // ---------------------------------------------------------------- 计量

    public int tokens(String text) {
        return tokenCounter.count(text);
    }

    public String tail(String text, int overlapTokens) {
        return tokenCounter.tail(text, overlapTokens);
    }

    public List<String> hardSplit(String text, int maxTokens) {
        return tokenCounter.hardSplitByTokens(text, maxTokens);
    }

    // ---------------------------------------------------------------- 基础切分

    /** 按分隔符优先级递归切分；最终回退整段（调用方自行 hardSplit）。 */
    public List<String> recursive(String text, List<String> separators) {
        List<String> out = new ArrayList<>();
        recurse(text, separators, out);
        return out;
    }

    private void recurse(String text, List<String> separators, List<String> out) {
        if (text.isBlank()) {
            return;
        }
        if (separators.isEmpty()) {
            out.add(text.strip());
            return;
        }
        String sep = separators.get(0);
        List<String> rest = separators.subList(1, separators.size());
        if (sep.isEmpty()) {
            recurse(text, rest, out);
            return;
        }
        if (!text.contains(sep)) {
            recurse(text, rest, out);
            return;
        }
        int start = 0;
        int end;
        while ((end = text.indexOf(sep, start)) >= 0) {
            recurse(text.substring(start, end + sep.length()), rest, out);
            start = end + sep.length();
        }
        if (start < text.length()) {
            recurse(text.substring(start), rest, out);
        }
    }

    /** 句子切分（中文句读 + 换行），保留非空句。 */
    public List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        for (String s : SENT_BOUND.split(text)) {
            if (!s.isBlank()) {
                out.add(s.strip());
            }
        }
        return out;
    }

    /** 段落切分（空行分组）。 */
    public List<String> paragraphs(String text) {
        List<String> out = new ArrayList<>();
        for (String p : text.split("\\n\\s*\\n")) {
            if (!p.isBlank()) {
                out.add(p.strip());
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 块提取

    /** 单页行扫描：围栏代码整体保护 → 表格 → Markdown 标题 → 段落。 */
    public List<Block> extractBlocks(int page, String text) {
        String[] lines = text.split("\n", -1);
        List<Block> blocks = new ArrayList<>();
        List<String> codeBuf = null;
        List<String> normal = new ArrayList<>();

        for (String line : lines) {
            if (codeBuf != null) {
                codeBuf.add(line);
                if (FENCE.matcher(line).find()) {
                    blocks.add(new Block(page, Kind.CODE, 0, String.join("\n", codeBuf)));
                    codeBuf = null;
                }
                continue;
            }
            if (FENCE.matcher(line).find()) {
                flushTablesAndParagraphs(page, normal, blocks);
                codeBuf = new ArrayList<>();
                codeBuf.add(line);
                continue;
            }
            normal.add(line);
        }
        if (codeBuf != null) {
            blocks.add(new Block(page, Kind.CODE, 0, String.join("\n", codeBuf)));
        }
        flushTablesAndParagraphs(page, normal, blocks);
        return blocks;
    }

    private void flushTablesAndParagraphs(int page, List<String> lines, List<Block> out) {
        List<String> para = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            if (TABLE_LINE.matcher(lines.get(i)).matches()) {
                int start = i;
                boolean hasSep = false;
                while (i < lines.size() && TABLE_LINE.matcher(lines.get(i)).matches()) {
                    if (TABLE_SEP.matcher(lines.get(i)).find()) {
                        hasSep = true;
                    }
                    i++;
                }
                if (hasSep && i - start >= 2) {
                    flushParagraphs(page, para, out);
                    out.add(new Block(page, Kind.TABLE, 0,
                            String.join("\n", lines.subList(start, i))));
                } else {
                    para.addAll(lines.subList(start, i));
                }
                continue;
            }
            para.add(lines.get(i));
            i++;
        }
        flushParagraphs(page, para, out);
        // flush 后必须清空：extractBlocks 在每个代码围栏前都会调用本方法，
        // 不清空会导致历史行在下一次 flush 时被重复输出（前缀雪崩）
        lines.clear();
    }

    private void flushParagraphs(int page, List<String> lines, List<Block> out) {
        List<String> buf = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) {
                if (!buf.isEmpty()) {
                    out.add(new Block(page, Kind.PARAGRAPH, 0, String.join("\n", buf).strip()));
                    buf.clear();
                }
            } else {
                Matcher m = HEADING.matcher(line);
                if (m.matches()) {
                    if (!buf.isEmpty()) {
                        out.add(new Block(page, Kind.PARAGRAPH, 0, String.join("\n", buf).strip()));
                        buf.clear();
                    }
                    out.add(new Block(page, Kind.HEADING, m.group(1).length(), line));
                } else {
                    buf.add(line);
                }
            }
        }
        if (!buf.isEmpty()) {
            out.add(new Block(page, Kind.PARAGRAPH, 0, String.join("\n", buf).strip()));
        }
        lines.clear();
    }

    // ---------------------------------------------------------------- 表格/代码切片

    /** Markdown 表格按行组切分，每片保留表头 + 分隔行（指南 §16）。 */
    public List<String> splitTable(String mdTable, int maxTokens) {
        List<String> rows = normalizeTable(List.of(mdTable.split("\n", -1)));
        if (rows.size() < 3) {
            return List.of(String.join("\n", rows));
        }
        String header = rows.get(0);
        String sep = rows.get(1);
        List<String> out = new ArrayList<>();
        List<String> group = new ArrayList<>();
        for (int i = 2; i < rows.size(); i++) {
            String row = rows.get(i);
            int groupTokens = tokens(header + "\n" + sep + "\n" + String.join("\n", group) + "\n" + row);
            if (!group.isEmpty() && (groupTokens > maxTokens || group.size() >= 50)) {
                out.add(header + "\n" + sep + "\n" + String.join("\n", group));
                group = new ArrayList<>();
            }
            group.add(row);
        }
        if (!group.isEmpty()) {
            out.add(header + "\n" + sep + "\n" + String.join("\n", group));
        }
        return out;
    }

    /**
     * 跨页表格续接（ISSUE RAG-008）：上一页以 Markdown 表格结尾、下一页以表格行开头时，
     * 将续接行并入上一页表格，只保留一份表头；下一页重复相同表头时自动去重。
     * 表头不同视为另一张表，不合并；可连续处理跨 3 页及以上的表格。
     */
    public List<ParseService.RawPage> mergeContinuedTables(List<ParseService.RawPage> pages) {
        List<ParseService.RawPage> out = new ArrayList<>();
        for (ParseService.RawPage page : pages) {
            if (page == null || page.text() == null || page.text().isBlank()) {
                out.add(page);
                continue;
            }
            List<String> current = new ArrayList<>(List.of(page.text().split("\n", -1)));
            int leadingEnd = leadingTableEnd(current);
            boolean merged = false;
            if (leadingEnd > 0) {
                int idx = lastNonBlank(out);
                if (idx >= 0) {
                    List<String> prev = new ArrayList<>(List.of(out.get(idx).text().split("\n", -1)));
                    int prevStart = trailingTableStart(prev);
                    if (prevStart >= 0) {
                        List<String> mergedTable = mergeTable(
                                prev.subList(prevStart, prev.size()), current.subList(0, leadingEnd));
                        if (mergedTable != null) {
                            List<String> rebuilt = new ArrayList<>(prev.subList(0, prevStart));
                            rebuilt.addAll(mergedTable);
                            out.set(idx, new ParseService.RawPage(out.get(idx).page(),
                                    String.join("\n", rebuilt).strip()));
                            String rest = String.join("\n",
                                    current.subList(leadingEnd, current.size())).strip();
                            if (!rest.isEmpty()) {
                                out.add(new ParseService.RawPage(page.page(), rest));
                            }
                            merged = true;
                        }
                    }
                }
            }
            if (!merged) {
                out.add(page);
            }
        }
        return out;
    }

    /** 页首连续表格块的结束下标；页首不是表格行时返回 0。 */
    private int leadingTableEnd(List<String> lines) {
        int start = 0;
        while (start < lines.size() && lines.get(start).isBlank()) {
            start++;
        }
        if (start >= lines.size() || !TABLE_LINE.matcher(lines.get(start)).matches()) {
            return 0;
        }
        int end = start;
        while (end < lines.size() && TABLE_LINE.matcher(lines.get(end)).matches()) {
            end++;
        }
        return end;
    }

    /** 页尾连续表格块的起始下标；要求块内含表头分隔行，否则视为普通文本返回 -1。 */
    private int trailingTableStart(List<String> lines) {
        int end = lines.size() - 1;
        while (end >= 0 && lines.get(end).isBlank()) {
            end--;
        }
        if (end < 0 || !TABLE_LINE.matcher(lines.get(end)).matches()) {
            return -1;
        }
        int start = end;
        while (start > 0 && TABLE_LINE.matcher(lines.get(start - 1)).matches()) {
            start--;
        }
        boolean hasHeader = start + 1 <= end && TABLE_SEP.matcher(lines.get(start + 1)).find();
        return hasHeader ? start : -1;
    }

    private int lastNonBlank(List<ParseService.RawPage> pages) {
        for (int i = pages.size() - 1; i >= 0; i--) {
            ParseService.RawPage page = pages.get(i);
            if (page != null && page.text() != null && !page.text().isBlank()) {
                return i;
            }
        }
        return -1;
    }

    /** 合并两段表格；表头不一致或没有续接数据时返回 null。 */
    private List<String> mergeTable(List<String> prevTable, List<String> nextTable) {
        List<String> prev = normalizeTable(prevTable);
        if (prev.size() < 2 || !TABLE_SEP.matcher(prev.get(1)).find()) {
            return null;
        }
        List<String> next = normalizeTable(nextTable);
        List<String> data;
        if (next.size() >= 2 && TABLE_SEP.matcher(next.get(1)).find()) {
            // 下一页重复表头：表头一致才可续接
            if (!headerKey(prev.get(0)).equals(headerKey(next.get(0)))) {
                return null;
            }
            data = next.subList(2, next.size());
        } else {
            data = next;
        }
        List<String> merged = new ArrayList<>(prev);
        for (String row : data) {
            if (!TABLE_SEP.matcher(row).find()) {
                merged.add(row);
            }
        }
        return merged.size() == prev.size() ? null : normalizeTable(merged);
    }

    private String headerKey(String headerLine) {
        return String.join("|", rowCells(headerLine)).replaceAll("\\s+", "");
    }

    /**
     * 表格行列归一化：以表头列数为准，补齐缺失单元格、把多余单元格并入最后一列，
     * 避免残缺行在 Markdown / 切片后与表头错位。列数一致的行保持原样。
     */
    private List<String> normalizeTable(List<String> lines) {
        if (lines.isEmpty()) {
            return lines;
        }
        int cols = rowCells(lines.get(0)).size();
        if (cols == 0) {
            return lines;
        }
        List<String> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            List<String> cells = rowCells(line);
            if (i == 1 && TABLE_SEP.matcher(line).find()) {
                out.add(cells.size() == cols ? line : "|" + " --- |".repeat(cols));
                continue;
            }
            if (cells.size() == cols) {
                out.add(line);
                continue;
            }
            List<String> fixed = new ArrayList<>(cells.subList(0, Math.min(cols, cells.size())));
            if (cells.size() > cols) {
                fixed.set(cols - 1, String.join(" ", cells.subList(cols - 1, cells.size())));
            }
            while (fixed.size() < cols) {
                fixed.add("");
            }
            out.add("| " + String.join(" | ", fixed) + " |");
        }
        return out;
    }

    /** 拆分表格行单元格，保留单元格内原始空白与转义竖线。 */
    private List<String> rowCells(String line) {
        String s = line.strip();
        if (s.startsWith("|")) {
            s = s.substring(1);
        }
        if (s.endsWith("|") && !s.endsWith("\\|")) {
            s = s.substring(0, s.length() - 1);
        }
        return new ArrayList<>(List.of(s.split("(?<!\\\\)\\|", -1)));
    }

    /** 代码按行切分（超长代码块的兜底）。 */
    public List<String> splitCode(String code, int maxTokens) {
        List<String> out = new ArrayList<>();
        List<String> group = new ArrayList<>();
        for (String line : code.split("\n", -1)) {
            if (!group.isEmpty() && tokens(String.join("\n", group) + "\n" + line) > maxTokens) {
                out.add(String.join("\n", group));
                group = new ArrayList<>();
            }
            group.add(line);
        }
        if (!group.isEmpty()) {
            out.add(String.join("\n", group));
        }
        return out;
    }

    // ---------------------------------------------------------------- 打包/分组

    /**
     * 通用打包：pieces 顺序聚合到 ≤size；溢出时成块，新块用最近正文尾部（≤overlap tokens）开头；
     * atomic 块（代码/表格）不参与 overlap。
     */
    public List<PlannedChild> pack(List<PackPiece> pieces, int size, int overlapTokens) {
        List<PlannedChild> children = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int bufPage = 0;
        String lastTextTail = "";
        for (PackPiece piece : pieces) {
            if (piece.text() == null || piece.text().isBlank()) {
                continue;
            }
            if (piece.atomic()) {
                if (!buf.isEmpty()) {
                    children.add(new PlannedChild(bufPage, buf.toString().strip()));
                    buf.setLength(0);
                }
                children.add(new PlannedChild(piece.page(), piece.text().strip()));
                lastTextTail = "";
                continue;
            }
            boolean overflow = !buf.isEmpty()
                    && tokens(buf.toString()) + tokens(piece.text()) > size;
            if (overflow) {
                children.add(new PlannedChild(bufPage, buf.toString().strip()));
                buf.setLength(0);
                if (tokens(lastTextTail) + tokens(piece.text()) <= size) {
                    buf.append(lastTextTail);
                }
                bufPage = piece.page();
            }
            if (buf.isEmpty()) {
                bufPage = piece.page();
            } else {
                buf.append('\n');
            }
            buf.append(piece.text());
            lastTextTail = tail(piece.text(), overlapTokens);
        }
        if (!buf.toString().isBlank()) {
            children.add(new PlannedChild(bufPage, buf.toString().strip()));
        }
        return children;
    }

    /** 将块按标题分组（每个标题开启新章节），无标题块归入 null 章节。 */
    public List<Section> sections(List<Block> blocks) {
        List<Section> out = new ArrayList<>();
        List<String> titleStack = new ArrayList<>();
        List<Integer> levelStack = new ArrayList<>();
        List<Block> current = new ArrayList<>();
        String currentTitle = null;
        String currentPath = null;

        for (Block b : blocks) {
            if (b.kind() == Kind.HEADING) {
                if (!current.isEmpty()) {
                    out.add(new Section(currentTitle, currentPath, new ArrayList<>(current)));
                    current.clear();
                }
                while (!levelStack.isEmpty() && levelStack.get(levelStack.size() - 1) >= b.level()) {
                    levelStack.remove(levelStack.size() - 1);
                    titleStack.remove(titleStack.size() - 1);
                }
                levelStack.add(b.level());
                titleStack.add(headingTitle(b.text()));
                currentTitle = titleStack.get(titleStack.size() - 1);
                currentPath = String.join(" > ", titleStack);
                continue;
            }
            current.add(b);
            if (currentTitle == null) {
                currentPath = null;
            }
        }
        if (!current.isEmpty()) {
            out.add(new Section(currentTitle, currentPath, current));
        }
        return out;
    }

    // ---------------------------------------------------------------- 工具

    /** 章节块 → PackPiece：表格/代码原子切分，段落递归后硬切。 */
    public List<PackPiece> sectionPieces(List<Block> blocks, int size, List<String> separators) {
        List<PackPiece> pieces = new ArrayList<>();
        for (Block b : blocks) {
            switch (b.kind()) {
                case TABLE -> {
                    for (String t : splitTable(b.text(), size)) {
                        pieces.add(new PackPiece(b.page(), true, t));
                    }
                }
                case CODE -> {
                    for (String t : splitCode(b.text(), size)) {
                        pieces.add(new PackPiece(b.page(), true, t));
                    }
                }
                case PARAGRAPH -> {
                    List<String> parts = recursive(b.text(), separators);
                    if (parts.isEmpty()) {
                        parts = List.of(b.text());
                    }
                    for (String part : parts) {
                        for (String bounded : hardSplit(part, size)) {
                            pieces.add(PackPiece.text(b.page(), bounded));
                        }
                    }
                }
                case HEADING -> { /* 章节标题已在元数据，不进正文片 */ }
            }
        }
        return pieces;
    }

    public String headingTitle(String line) {
        Matcher m = HEADING.matcher(line);
        return m.matches() ? m.group(2).strip() : line.strip();
    }

    public String normalized(String content) {
        return content.replaceAll("\\s+", " ").strip();
    }

    public String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String joinBlocks(List<Block> blocks) {
        StringBuilder sb = new StringBuilder();
        for (Block b : blocks) {
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(b.text());
        }
        return sb.toString();
    }
}
