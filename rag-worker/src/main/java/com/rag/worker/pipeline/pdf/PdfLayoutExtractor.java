package com.rag.worker.pipeline.pdf;

import com.rag.worker.pipeline.BBox;
import com.rag.worker.pipeline.table.TableModel;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PDF 版面结构抽取（ISSUE RAG-007）：利用 PDFBox 坐标信息把每页还原为
 * 按阅读顺序排列的文本行 / 表格 / 图片块。
 *
 * <p>文本行与词位置复用 {@link PDFTextStripper}（sortByPosition）的阅读顺序排序
 * 与词切分；表格采用 bbox 启发式检测：连续多行、行间垂直距离规整、行内词间
 * 水平空隙可切分出 ≥2 列且各列首 x 坐标跨行对齐的区域判定为表格，转换为
 * {@link TableModel} 复用统一的 Markdown 渲染；图片通过内容流 Do 算子捕获
 * 绘制位置（Form XObject 递归），供调用方按版面位置插入 OCR/Vision 描述。</p>
 *
 * <p>坐标系说明：{@link TextPosition#getYDirAdj()} 为自顶向下的显示坐标，
 * 图片绘制矩阵为 PDF 用户空间坐标（y 向上），统一换算为自顶向下的 top 值排序。
 * 旋转页面的图片坐标换算未做适配，位置可能偏差（仅占位顺序，不影响内容）。</p>
 */
@Slf4j
public class PdfLayoutExtractor {

    /**
     * 版面块语义类别（ISSUE RAG-007）：标题 / 段落 / 列表 / 表格 / 图片 / 代码 / 公式 / 页眉页脚。
     * 页眉页脚在跨页去重阶段标记，组装页文本时被剔除。
     */
    public enum BlockKind {
        TITLE, PARAGRAPH, LIST, TABLE, IMAGE, CODE, FORMULA, HEADER_FOOTER
    }

    /** 版面块：top 为自页面向下的 y 坐标（pt），用于块间排序；bbox 为该块在页面上的区域。 */
    public sealed interface Block {

        double top();

        BBox bbox();

        BlockKind kind();
    }

    /** 文本行；kind 为语义分类结果。 */
    public record TextBlock(double top, String text, BBox bbox, BlockKind kind) implements Block {

        public TextBlock(double top, String text, BBox bbox) {
            this(top, text, bbox, BlockKind.PARAGRAPH);
        }

        public TextBlock(double top, String text) {
            this(top, text, null, BlockKind.PARAGRAPH);
        }

        public TextBlock withKind(BlockKind value) {
            return new TextBlock(top, text, bbox, value);
        }
    }

    /** 表格块：渲染为 Markdown 嵌入页文本。 */
    public record TableBlock(double top, TableModel table, BBox bbox) implements Block {

        public TableBlock(double top, TableModel table) {
            this(top, table, null);
        }

        @Override
        public BlockKind kind() {
            return BlockKind.TABLE;
        }
    }

    /** 图片块：记录绘制位置与显示尺寸（pt），供按位置插入识别结果。 */
    public record ImageBlock(double top, PDImageXObject image,
                             float x, float y, float width, float height) implements Block {

        @Override
        public BBox bbox() {
            return BBox.of(x, (float) top, x + width, (float) top + height);
        }

        @Override
        public BlockKind kind() {
            return BlockKind.IMAGE;
        }
    }

    /** 行内切分列的最小水平空隙（与行高取大者）。 */
    private static final float COL_GAP_MIN = 6f;
    /** 相邻表格行的最大垂直间距（相对行高倍数；PDFBox 行高为字帽高，10pt 字 ≈5.8，需覆盖常见 1.5~3 倍行距）。 */
    private static final float ROW_GAP_FACTOR = 4.0f;
    /** 列首 x 坐标对齐容差下限。 */
    private static final float COL_ALIGN_TOL_MIN = 8f;
    /** 段首 x 对齐列边界的最低比例。 */
    private static final double ALIGN_RATIO = 0.7;
    /** 平均单元格长度上限，防止把多栏正文误判为表格。 */
    private static final double MAX_AVG_CELL_LEN = 60;
    /** 表格最少行数（两列时要求更多行，降低正文误判）。 */
    private static final int MIN_TABLE_ROWS = 2;
    private static final int MIN_TWO_COL_ROWS = 3;
    /** 表格最多列数。 */
    private static final int MAX_TABLE_COLS = 20;

    /** 抽取单页，返回按版面自顶向下排序的结构化块。 */
    public List<Block> extractPage(PDDocument pdf, int pageIndex) throws IOException {
        List<Line> lines = captureLines(pdf, pageIndex);
        float pageHeight = pageHeight(pdf, pageIndex);
        float medianFontSize = medianFontSize(lines);
        List<Block> blocks = new ArrayList<>();
        // 优先用 PDF 矢量线段重建表格网格（支持合并单元格），无线段时回退 bbox 启发式
        List<Rule> rules = captureRules(pdf, pageIndex, pageHeight);
        Set<Line> consumed = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        blocks.addAll(buildTablesFromRules(lines, rules, pageIndex, consumed));
        List<Line> remaining = new ArrayList<>(lines.size());
        for (Line line : lines) {
            if (!consumed.contains(line)) {
                remaining.add(line);
            }
        }
        blocks.addAll(assembleBlocks(remaining, pageIndex, medianFontSize));
        blocks.addAll(captureImages(pdf, pageIndex));
        blocks.sort((a, b) -> Double.compare(a.top(), b.top()));
        return blocks;
    }

    /** 页高；抽取失败返回 0，调用方按无行线段处理。 */
    private float pageHeight(PDDocument pdf, int pageIndex) {
        try {
            return pdf.getPage(pageIndex).getMediaBox().getHeight();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 页面字号中位数（按词数加权），用于标题判定；无文本返回 0。 */
    private float medianFontSize(List<Line> lines) {
        List<Float> sizes = new ArrayList<>();
        for (Line line : lines) {
            for (Word word : line.words()) {
                if (word.fontSize() > 0) {
                    sizes.add(word.fontSize());
                }
            }
        }
        if (sizes.isEmpty()) {
            return 0;
        }
        sizes.sort(Float::compare);
        return sizes.get(sizes.size() / 2);
    }

    // ---------- 文本行捕获 ----------

    /** 词：文本 + bbox + 是否与前一词存在空格分隔 + 字号/字体名（用于语义分类）。 */
    private record Word(String text, float x1, float top, float x2, float height,
                        boolean spaceBefore, float fontSize, String fontName) {
    }

    /** 行：top 为行内最小 y，height 为最大字高，fontSize 为行内最大字号。 */
    private record Line(float top, float height, float fontSize, List<Word> words) {

        /** 还原行文本：按 PDFBox 词分隔标记补空格，避免 CJK 字符间被插入多余空格。 */
        String text() {
            StringBuilder sb = new StringBuilder();
            for (Word w : words) {
                if (w.spaceBefore() && sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(w.text());
            }
            return sb.toString();
        }

        /** 行区域：首词左边界 → 末词右边界，行顶 → 行底。 */
        BBox bbox() {
            float x1 = Float.MAX_VALUE;
            float x2 = 0;
            for (Word w : words) {
                x1 = Math.min(x1, w.x1());
                x2 = Math.max(x2, w.x2());
            }
            return BBox.of(x1, top, x2, top + height);
        }

        /** 行内字体名（取首个非空词），用于等宽字体识别。 */
        String fontName() {
            for (Word w : words) {
                if (w.fontName() != null && !w.fontName().isBlank()) {
                    return w.fontName().toLowerCase(Locale.ROOT);
                }
            }
            return "";
        }
    }

    /** 捕获 PDFBox 阅读顺序排序后的词流，按行回调重组为带坐标的行。 */
    private List<Line> captureLines(PDDocument pdf, int pageIndex) throws IOException {
        WordStripper stripper = new WordStripper();
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        stripper.getText(pdf);
        stripper.flushLine();
        List<Line> lines = new ArrayList<>();
        for (List<Word> words : stripper.lines) {
            float top = Float.MAX_VALUE;
            float height = 0;
            float fontSize = 0;
            for (Word w : words) {
                top = Math.min(top, w.top());
                height = Math.max(height, w.height());
                fontSize = Math.max(fontSize, w.fontSize());
            }
            lines.add(new Line(top, height, fontSize, words));
        }
        return lines;
    }

    /** 按词水平空隙切分行内列段。 */
    private record Seg(float x1, float x2, String text) {
    }

    private List<Seg> segments(Line line) {
        List<Seg> segs = new ArrayList<>();
        float gapLimit = Math.max(COL_GAP_MIN, line.height());
        float start = 0;
        float end = 0;
        StringBuilder text = new StringBuilder();
        boolean first = true;
        for (Word w : line.words()) {
            if (first) {
                start = w.x1();
                end = w.x2();
                text.append(w.text());
                first = false;
                continue;
            }
            if (w.x1() - end > gapLimit) {
                segs.add(new Seg(start, end, text.toString()));
                start = w.x1();
                text.setLength(0);
            } else if (w.spaceBefore()) {
                text.append(' ');
            }
            text.append(w.text());
            end = Math.max(end, w.x2());
        }
        if (text.length() > 0) {
            segs.add(new Seg(start, end, text.toString()));
        }
        return segs;
    }

    // ---------- 表格检测 ----------

    /** 行序列 → 块序列：连续的候选表格行交给 tryBuildTable，其余输出文本行。 */
    private List<Block> assembleBlocks(List<Line> lines, int pageIndex, float medianFontSize) {
        List<Block> blocks = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            if (segments(lines.get(i)).size() >= 2) {
                int j = i;
                while (j + 1 < lines.size() && rowGapOk(lines.get(j), lines.get(j + 1))
                        && segments(lines.get(j + 1)).size() >= 2) {
                    j++;
                }
                if (j > i) {
                    List<Line> tableLines = lines.subList(i, j + 1);
                    TableModel table = tryBuildTable(tableLines, pageIndex);
                    if (table != null) {
                        blocks.add(new TableBlock(lines.get(i).top(), table, unionBBox(tableLines)));
                        i = j + 1;
                        continue;
                    }
                }
            }
            String text = lines.get(i).text().strip();
            if (!text.isEmpty()) {
                blocks.add(new TextBlock(lines.get(i).top(), text, lines.get(i).bbox(),
                        classify(lines.get(i), text, medianFontSize)));
            }
            i++;
        }
        return blocks;
    }

    // ---------- 语义分类 ----------

    /** 列表项行首标记：项目符号、1. / 1) / (1)、一、/ (一)、① 等。 */
    private static final Pattern LIST_MARKER = Pattern.compile(
            "^\\s*(?:[-*•·▪◦●○]|\\(?\\d{1,3}[.)、]|[（(]?[一二三四五六七八九十百]+[、.)）]"
                    + "|[①-⑳])\\s*\\S.*");

    /** 等宽字体（代码）：常见 mono 字体族，大小写不敏感。 */
    private static final Pattern MONO_FONT = Pattern.compile(
            ".*(mono|courier|consolas|menlo|sourcecode|code|fixed|terminal).*");

    /** 代码行特征：行尾分号 / 箭头 / 关键字开头。 */
    private static final Pattern CODE_LINE = Pattern.compile(
            "(.*(->|=>|::|;\\s*$)).*|^\\s*(public|private|protected|static|final|class|interface"
                    + "|import|package|def|function|func|const|let|var|return|if|for|while|#include)\\b.*");

    /** 公式特征：关系符、希腊字母、上下标与函数符号。 */
    private static final String FORMULA_SIGNS = "=+−-*/^_∑∫√±×÷≈≠≤≥∞πθαβγδλμσΔΩ";

    /**
     * 文本行语义分类（ISSUE RAG-007）：代码 → 公式 → 列表 → 标题 → 段落。
     * 页眉页脚在跨页阶段识别；标题以页面字号中位数为基准，避免固定字号假设。
     */
    private BlockKind classify(Line line, String text, float medianFontSize) {
        if (MONO_FONT.matcher(line.fontName()).matches() || CODE_LINE.matcher(text).matches()) {
            return BlockKind.CODE;
        }
        if (looksLikeFormula(text)) {
            return BlockKind.FORMULA;
        }
        if (LIST_MARKER.matcher(text).matches()) {
            return BlockKind.LIST;
        }
        if (medianFontSize > 0 && line.fontSize() >= medianFontSize * 1.25f
                && text.length() <= 100 && !endsWithSentencePunctuation(text)) {
            return BlockKind.TITLE;
        }
        return BlockKind.PARAGRAPH;
    }

    /** 公式行：数学符号占比高且含关系符/求和/积分等强特征，长度不宜过长。 */
    private boolean looksLikeFormula(String text) {
        if (text.length() < 3 || text.length() > 80) {
            return false;
        }
        int signs = 0;
        for (int i = 0; i < text.length(); i++) {
            if (FORMULA_SIGNS.indexOf(text.charAt(i)) >= 0) {
                signs++;
            }
        }
        return signs >= 2 && signs >= text.length() * 0.25
                && (text.indexOf('=') >= 0 || text.indexOf('∑') >= 0
                || text.indexOf('∫') >= 0 || text.indexOf('√') >= 0);
    }

    private boolean endsWithSentencePunctuation(String text) {
        char last = text.charAt(text.length() - 1);
        return "。.；;，,：:！!？?".indexOf(last) >= 0;
    }

    /** 表格区域：候选行的最小外接矩形。 */
    private BBox unionBBox(List<Line> rows) {
        BBox bbox = null;
        for (Line row : rows) {
            bbox = BBox.union(bbox, row.bbox());
        }
        return bbox;
    }

    private boolean rowGapOk(Line a, Line b) {
        return b.top() - a.top() <= ROW_GAP_FACTOR * Math.max(a.height(), b.height());
    }

    /**
     * 候选行区域 → TableModel：按各行段首 x 聚类出列边界，校验对齐率与
     * 单元格长度后把段映射到最近列，缺列补空、同列多段拼接。
     */
    private TableModel tryBuildTable(List<Line> rows, int pageIndex) {
        if (rows.size() < MIN_TABLE_ROWS) {
            return null;
        }
        List<List<Seg>> rowSegs = new ArrayList<>(rows.size());
        List<Float> starts = new ArrayList<>();
        int segCount = 0;
        float avgHeight = 0;
        for (Line row : rows) {
            List<Seg> segs = segments(row);
            rowSegs.add(segs);
            avgHeight += row.height();
            for (Seg s : segs) {
                starts.add(s.x1());
                segCount++;
            }
        }
        avgHeight /= rows.size();
        float tol = Math.max(COL_ALIGN_TOL_MIN, avgHeight * 1.5f);
        starts.sort(Float::compare);
        // 列首聚类：相邻段首 x 距离 ≤ 容差归入同列，簇心取均值
        List<Float> colCenters = new ArrayList<>();
        List<Integer> colSupport = new ArrayList<>();
        for (float x : starts) {
            int last = colCenters.size() - 1;
            if (last >= 0 && x - colCenters.get(last) <= tol) {
                int n = colSupport.get(last);
                colCenters.set(last, (colCenters.get(last) * n + x) / (n + 1));
                colSupport.set(last, n + 1);
            } else {
                colCenters.add(x);
                colSupport.add(1);
            }
        }
        int nCols = colCenters.size();
        if (nCols < 2 || nCols > MAX_TABLE_COLS) {
            return null;
        }
        if (nCols == 2 && rows.size() < MIN_TWO_COL_ROWS) {
            return null;
        }
        // 对齐率与平均单元格长度校验：过滤两端对齐排版的多栏正文
        int aligned = 0;
        int totalChars = 0;
        for (List<Seg> segs : rowSegs) {
            for (Seg s : segs) {
                totalChars += s.text().length();
                for (float c : colCenters) {
                    if (Math.abs(s.x1() - c) <= tol) {
                        aligned++;
                        break;
                    }
                }
            }
        }
        if (aligned < ALIGN_RATIO * segCount || totalChars / (double) segCount > MAX_AVG_CELL_LEN) {
            return null;
        }
        List<List<TableModel.Cell>> grid = new ArrayList<>(rows.size());
        float assignTol = 3 * tol;
        for (List<Seg> segs : rowSegs) {
            String[] cells = new String[nCols];
            for (Seg s : segs) {
                int best = -1;
                float dist = Float.MAX_VALUE;
                for (int c = 0; c < nCols; c++) {
                    float d = Math.abs(s.x1() - colCenters.get(c));
                    if (d < dist) {
                        dist = d;
                        best = c;
                    }
                }
                if (best < 0 || dist > assignTol) {
                    continue;
                }
                cells[best] = cells[best] == null ? s.text() : cells[best] + " " + s.text();
            }
            List<TableModel.Cell> rowCells = new ArrayList<>(nCols);
            for (String cell : cells) {
                rowCells.add(TableModel.Cell.of(cell == null ? "" : cell));
            }
            grid.add(rowCells);
        }
        return new TableModel(null, pageIndex, grid);
    }

    // ---------- 矢量线段表格（合并单元格） ----------

    /** 页面网格线：horizontal=true 表示横线（pos=top 坐标），否则竖线（pos=x 坐标），start/end 为线段范围。 */
    private record Rule(boolean horizontal, float pos, float start, float end) {
    }

    /** 网格线位置聚类与覆盖判定容差（pt）。 */
    private static final float RULE_TOL = 3f;
    /** 过短的线段（下划线、文字装饰）不参与表格识别。 */
    private static final float MIN_RULE_LEN = 6f;

    /** 捕获页面矢量线段（矩形边框、描边路径），用于重建带合并单元格的表格网格。 */
    private List<Rule> captureRules(PDDocument pdf, int pageIndex, float pageHeight) {
        if (pageHeight <= 0) {
            return List.of();
        }
        RuleLocator locator = new RuleLocator(pdf.getPage(pageIndex), pageHeight);
        try {
            locator.processPage(pdf.getPage(pageIndex));
        } catch (Exception e) {
            log.debug("PDF 第 {} 页矢量线段提取失败: {}", pageIndex + 1, e.getMessage());
            return List.of();
        }
        return locator.rules;
    }

    /** 线段 → 表格块：只处理能形成 ≥2×2 网格的独立线段簇，避免误吞正文。 */
    private List<Block> buildTablesFromRules(List<Line> lines, List<Rule> rules,
                                             int pageIndex, Set<Line> consumed) {
        if (rules.size() < 4 || lines.isEmpty()) {
            return List.of();
        }
        List<Block> tables = new ArrayList<>();
        for (List<Rule> component : connectedComponents(rules)) {
            TableModel table = tableFromRules(component, lines, pageIndex, consumed);
            if (table == null) {
                continue;
            }
            float top = Float.MAX_VALUE;
            BBox bbox = null;
            for (Rule rule : component) {
                top = Math.min(top, rule.horizontal() ? rule.pos() : rule.start());
                BBox rb = ruleBBox(rule);
                bbox = BBox.union(bbox, rb);
            }
            tables.add(new TableBlock(top, table, bbox));
        }
        return tables;
    }

    private TableModel tableFromRules(List<Rule> rules, List<Line> lines,
                                      int pageIndex, Set<Line> consumed) {
        List<Float> xs = clusterPositions(rules.stream()
                .filter(r -> !r.horizontal()).map(Rule::pos).toList());
        List<Float> ys = clusterPositions(rules.stream()
                .filter(Rule::horizontal).map(Rule::pos).toList());
        if (xs.size() < 3 || ys.size() < 3) {
            return null;
        }
        int cols = xs.size() - 1;
        int rows = ys.size() - 1;
        if (cols < 2 || rows < 2 || cols > MAX_TABLE_COLS || rows > 500) {
            return null;
        }
        float left = xs.get(0);
        float right = xs.get(xs.size() - 1);
        float top = ys.get(0);
        float bottom = ys.get(ys.size() - 1);
        if (right - left < 40 || bottom - top < 20) {
            return null;
        }
        List<Line> regionLines = new ArrayList<>();
        for (Line line : lines) {
            BBox b = line.bbox();
            if (b.x() < right + RULE_TOL && b.x() + b.width() > left - RULE_TOL
                    && b.y() < bottom + RULE_TOL && b.y() + b.height() > top - RULE_TOL) {
                regionLines.add(line);
            }
        }
        if (regionLines.size() < 2) {
            return null;
        }

        boolean[][] occupied = new boolean[rows][cols];
        List<Anchor> anchors = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (occupied[r][c]) {
                    continue;
                }
                boolean hasTop = r == 0 || hasRule(rules, true, ys.get(r), xs.get(c), xs.get(c + 1));
                boolean hasLeft = c == 0 || hasRule(rules, false, xs.get(c), ys.get(r), ys.get(r + 1));
                // 上/左边框缺失说明该格被上方或左侧合并单元格覆盖
                if (!hasTop || !hasLeft) {
                    continue;
                }
                int colspan = 1;
                while (c + colspan < cols
                        && !hasRule(rules, false, xs.get(c + colspan), ys.get(r), ys.get(r + 1))) {
                    colspan++;
                }
                int rowspan = 1;
                while (r + rowspan < rows
                        && !hasRule(rules, true, ys.get(r + rowspan), xs.get(c), xs.get(c + colspan))) {
                    rowspan++;
                }
                anchors.add(new Anchor(r, c, colspan, rowspan));
                for (int dr = 0; dr < rowspan; dr++) {
                    for (int dc = 0; dc < colspan; dc++) {
                        occupied[r + dr][c + dc] = true;
                    }
                }
            }
        }
        // 把文本段归入包含其中心的最小锚点单元格（同一基线的多列文本按水平空隙拆分）
        Map<Anchor, StringBuilder> texts = new LinkedHashMap<>();
        for (Line line : regionLines) {
            float cy = line.top() + line.height() / 2;
            for (Seg seg : segments(line)) {
                float cx = (seg.x1() + seg.x2()) / 2;
                Anchor best = null;
                float bestArea = Float.MAX_VALUE;
                for (Anchor a : anchors) {
                    float ax1 = xs.get(a.col());
                    float ax2 = xs.get(a.col() + a.colspan());
                    float ay1 = ys.get(a.row());
                    float ay2 = ys.get(a.row() + a.rowspan());
                    if (cx >= ax1 - RULE_TOL && cx <= ax2 + RULE_TOL
                            && cy >= ay1 - RULE_TOL && cy <= ay2 + RULE_TOL) {
                        float area = (ax2 - ax1) * (ay2 - ay1);
                        if (area < bestArea) {
                            bestArea = area;
                            best = a;
                        }
                    }
                }
                if (best != null) {
                    StringBuilder sb = texts.computeIfAbsent(best, k -> new StringBuilder());
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(seg.text().strip());
                }
            }
        }
        int nonEmpty = 0;
        for (StringBuilder sb : texts.values()) {
            if (!sb.toString().isBlank()) {
                nonEmpty++;
            }
        }
        if (nonEmpty < 2) {
            return null;
        }
        // 按行组织锚点：被合并覆盖的格子不写入，交由 colspan/rowspan 展开
        List<List<TableModel.Cell>> grid = new ArrayList<>(rows);
        for (int r = 0; r < rows; r++) {
            List<TableModel.Cell> rowCells = new ArrayList<>();
            for (Anchor a : anchors) {
                if (a.row() != r) {
                    continue;
                }
                StringBuilder sb = texts.get(a);
                rowCells.add(new TableModel.Cell(sb == null ? "" : sb.toString().strip(),
                        a.colspan(), a.rowspan()));
            }
            grid.add(rowCells);
        }
        // 合并单元格在 expand 时依赖列顺序，按起始列排序
        consumed.addAll(regionLines);
        return new TableModel(null, pageIndex, grid);
    }

    /** 锚点单元格：起始行列 + 合并跨度。 */
    private record Anchor(int row, int col, int colspan, int rowspan) {
    }

    /** 位置聚类：间隔 ≤ 容差的位置合并为同一网格线。 */
    private List<Float> clusterPositions(List<Float> values) {
        List<Float> sorted = new ArrayList<>(values);
        sorted.sort(Float::compare);
        List<Float> centers = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (float v : sorted) {
            int last = centers.size() - 1;
            if (last >= 0 && v - centers.get(last) <= RULE_TOL) {
                int n = counts.get(last);
                centers.set(last, (centers.get(last) * n + v) / (n + 1));
                counts.set(last, n + 1);
            } else {
                centers.add(v);
                counts.add(1);
            }
        }
        return centers;
    }

    /** 判定 pos 处是否存在覆盖 [from, to] 至少 60% 的网格线。 */
    private boolean hasRule(List<Rule> rules, boolean horizontal, float pos, float from, float to) {
        float span = to - from;
        if (span <= 0) {
            return false;
        }
        for (Rule rule : rules) {
            if (rule.horizontal() != horizontal || Math.abs(rule.pos() - pos) > RULE_TOL) {
                continue;
            }
            float overlap = Math.min(rule.end(), to) - Math.max(rule.start(), from);
            if (overlap >= span * 0.6f) {
                return true;
            }
        }
        return false;
    }

    /** 按包围盒相交把线段划分为独立连通簇（扩展容差吸收绘制误差）。 */
    private List<List<Rule>> connectedComponents(List<Rule> rules) {
        int n = rules.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (boxesOverlap(ruleBBox(rules.get(i)), ruleBBox(rules.get(j)))) {
                    union(parent, i, j);
                }
            }
        }
        Map<Integer, List<Rule>> groups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(rules.get(i));
        }
        return new ArrayList<>(groups.values());
    }

    private int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            parent[ra] = rb;
        }
    }

    private BBox ruleBBox(Rule rule) {
        return rule.horizontal()
                ? BBox.of(rule.start(), rule.pos() - 1, rule.end(), rule.pos() + 1)
                : BBox.of(rule.pos() - 1, rule.start(), rule.pos() + 1, rule.end());
    }

    /** 两个区域在容差内是否相交（用于把相邻网格线归入同一表格簇）。 */
    private boolean boxesOverlap(BBox a, BBox b) {
        return a.x() - RULE_TOL <= b.x() + b.width()
                && b.x() - RULE_TOL <= a.x() + a.width()
                && a.y() - RULE_TOL <= b.y() + b.height()
                && b.y() - RULE_TOL <= a.y() + a.height();
    }

    /** 表格网格线抽取：矩形边框与描边路径都折算为横/竖线段（用户空间 → 自顶向下）。 */
    private static final class RuleLocator extends PDFGraphicsStreamEngine {

        private final float pageHeight;
        private final List<Rule> rules = new ArrayList<>();
        private final List<Point2D> path = new ArrayList<>();

        RuleLocator(PDPage page, float pageHeight) {
            super(page);
            this.pageHeight = pageHeight;
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            float minX = (float) Math.min(Math.min(p0.getX(), p1.getX()), Math.min(p2.getX(), p3.getX()));
            float maxX = (float) Math.max(Math.max(p0.getX(), p1.getX()), Math.max(p2.getX(), p3.getX()));
            float minY = (float) Math.min(Math.min(p0.getY(), p1.getY()), Math.min(p2.getY(), p3.getY()));
            float maxY = (float) Math.max(Math.max(p0.getY(), p1.getY()), Math.max(p2.getY(), p3.getY()));
            addRect(minX, minY, maxX, maxY);
        }

        private void addRect(float x1, float y1, float x2, float y2) {
            Point2D a = transform(x1, y1);
            Point2D b = transform(x2, y2);
            float left = (float) Math.min(a.getX(), b.getX());
            float right = (float) Math.max(a.getX(), b.getX());
            float top1 = pageHeight - (float) a.getY();
            float top2 = pageHeight - (float) b.getY();
            float top = Math.min(top1, top2);
            float bottom = Math.max(top1, top2);
            float w = right - left;
            float h = bottom - top;
            if (h <= RULE_TOL) {
                addRule(true, (top + bottom) / 2, left, right);
            } else if (w <= RULE_TOL) {
                addRule(false, (left + right) / 2, top, bottom);
            } else {
                addRule(true, top, left, right);
                addRule(true, bottom, left, right);
                addRule(false, left, top, bottom);
                addRule(false, right, top, bottom);
            }
        }

        private void addRule(boolean horizontal, float pos, float from, float to) {
            if (to - from >= MIN_RULE_LEN) {
                rules.add(new Rule(horizontal, pos, from, to));
            }
        }

        private Point2D transform(float x, float y) {
            Matrix m = getGraphicsState().getCurrentTransformationMatrix();
            return m.transformPoint(x, y);
        }

        @Override
        public void drawImage(PDImage pdImage) {
            // 仅收集表格网格线，图片由 ImageLocator 处理
        }

        @Override
        public void moveTo(float x, float y) {
            path.clear();
            path.add(transform(x, y));
        }

        @Override
        public void lineTo(float x, float y) {
            path.add(transform(x, y));
        }

        @Override
        public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            path.add(transform(x3, y3));
        }

        @Override
        public void closePath() {
            if (!path.isEmpty()) {
                path.add(path.get(0));
            }
        }

        @Override
        public void strokePath() {
            commitPath();
        }

        @Override
        public void fillPath(int windingRule) {
            commitPath();
        }

        @Override
        public void fillAndStrokePath(int windingRule) {
            commitPath();
        }

        @Override
        public void endPath() {
            path.clear();
        }

        private void commitPath() {
            for (int i = 1; i < path.size(); i++) {
                Point2D p = path.get(i - 1);
                Point2D q = path.get(i);
                float x1 = (float) p.getX();
                float x2 = (float) q.getX();
                float t1 = pageHeight - (float) p.getY();
                float t2 = pageHeight - (float) q.getY();
                if (Math.abs(t1 - t2) <= RULE_TOL) {
                    addRule(true, (t1 + t2) / 2, Math.min(x1, x2), Math.max(x1, x2));
                } else if (Math.abs(x1 - x2) <= RULE_TOL) {
                    addRule(false, (x1 + x2) / 2, Math.min(t1, t2), Math.max(t1, t2));
                }
            }
            path.clear();
        }

        @Override
        public void clip(int windingRule) {
        }

        @Override
        public Point2D getCurrentPoint() {
            return path.isEmpty() ? null : path.get(path.size() - 1);
        }

        @Override
        public void shadingFill(COSName shadingName) {
        }
    }

    // ---------- 图片定位 ----------

    private List<ImageBlock> captureImages(PDDocument pdf, int pageIndex) {
        PDPage page = pdf.getPage(pageIndex);
        ImageLocator locator = new ImageLocator(page);
        try {
            locator.processPage(page);
        } catch (Exception e) {
            log.warn("PDF 第 {} 页图片定位失败: {}", pageIndex + 1, e.getMessage());
            return List.of();
        }
        float pageHeight = page.getMediaBox().getHeight();
        List<ImageBlock> out = new ArrayList<>();
        for (Placement p : locator.placements) {
            // 用户空间（y 向上）→ 自顶向下：图片顶边 = 页高 - (底边 + 显示高度)
            float top = pageHeight - (p.ty() + p.height());
            out.add(new ImageBlock(top, p.image(), p.tx(), p.ty(), p.width(), p.height()));
        }
        return out;
    }

    private record Placement(PDImageXObject image, float tx, float ty, float width, float height) {
    }

    /**
     * 图片定位：通过 PDFGraphicsStreamEngine 的 drawImage 钩子捕获图片，
     * 当前变换矩阵（含 Form XObject 嵌套变换，由引擎自动递归）即绘制位置；
     * 其余图形操作空实现。
     */
    private static final class ImageLocator extends PDFGraphicsStreamEngine {

        private final List<Placement> placements = new ArrayList<>();

        ImageLocator(PDPage page) {
            super(page);
        }

        @Override
        public void drawImage(PDImage pdImage) {
            if (pdImage instanceof PDImageXObject image) {
                Matrix m = getGraphicsState().getCurrentTransformationMatrix();
                placements.add(new Placement(image, m.getTranslateX(), m.getTranslateY(),
                        Math.abs(m.getScalingFactorX()), Math.abs(m.getScalingFactorY())));
            }
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
        }

        @Override
        public void clip(int windingRule) {
        }

        @Override
        public void moveTo(float x, float y) {
        }

        @Override
        public void lineTo(float x, float y) {
        }

        @Override
        public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        }

        @Override
        public Point2D getCurrentPoint() {
            return null;
        }

        @Override
        public void closePath() {
        }

        @Override
        public void endPath() {
        }

        @Override
        public void strokePath() {
        }

        @Override
        public void fillPath(int windingRule) {
        }

        @Override
        public void fillAndStrokePath(int windingRule) {
        }

        @Override
        public void shadingFill(COSName shadingName) {
        }
    }

    /** 复用 PDFTextStripper 的阅读顺序排序与词切分，回调重组词/行结构。 */
    private static final class WordStripper extends PDFTextStripper {

        private final List<List<Word>> lines = new ArrayList<>();
        private List<Word> current = new ArrayList<>();
        private boolean pendingSpace;

        WordStripper() throws IOException {
            setSortByPosition(true);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            if (text == null || text.isBlank() || positions.isEmpty()) {
                return;
            }
            TextPosition first = positions.get(0);
            TextPosition last = positions.get(positions.size() - 1);
            current.add(new Word(text.strip(), first.getXDirAdj(), first.getYDirAdj(),
                    last.getXDirAdj() + last.getWidthDirAdj(), first.getHeightDir(), pendingSpace,
                    first.getFontSizeInPt(), fontNameOf(first)));
            pendingSpace = false;
        }

        /** 字体名可能受嵌入子集前缀影响（ABCDEF+Arial），取加号后的部分。 */
        private static String fontNameOf(TextPosition position) {
            String name = position.getFont() == null ? null : position.getFont().getName();
            if (name == null) {
                return "";
            }
            int plus = name.indexOf('+');
            return plus >= 0 ? name.substring(plus + 1) : name;
        }

        @Override
        protected void writeWordSeparator() {
            pendingSpace = true;
        }

        @Override
        protected void writeLineSeparator() {
            flushLine();
        }

        void flushLine() {
            if (!current.isEmpty()) {
                lines.add(current);
                current = new ArrayList<>();
            }
            pendingSpace = false;
        }
    }
}
