package com.rag.worker.pipeline.chunk;

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
        String[] rows = mdTable.split("\n", -1);
        if (rows.length < 3) {
            return List.of(mdTable);
        }
        String header = rows[0];
        String sep = rows[1];
        List<String> out = new ArrayList<>();
        List<String> group = new ArrayList<>();
        for (int i = 2; i < rows.length; i++) {
            String row = rows[i];
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
