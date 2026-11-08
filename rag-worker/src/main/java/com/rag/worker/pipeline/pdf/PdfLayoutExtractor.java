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
import java.util.List;

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

    /** 版面块：top 为自页面向下的 y 坐标（pt），用于块间排序；bbox 为该块在页面上的区域。 */
    public sealed interface Block {

        double top();

        BBox bbox();
    }

    /** 文本行。 */
    public record TextBlock(double top, String text, BBox bbox) implements Block {

        public TextBlock(double top, String text) {
            this(top, text, null);
        }
    }

    /** 表格块：渲染为 Markdown 嵌入页文本。 */
    public record TableBlock(double top, TableModel table, BBox bbox) implements Block {

        public TableBlock(double top, TableModel table) {
            this(top, table, null);
        }
    }

    /** 图片块：记录绘制位置与显示尺寸（pt），供按位置插入识别结果。 */
    public record ImageBlock(double top, PDImageXObject image,
                             float x, float y, float width, float height) implements Block {

        @Override
        public BBox bbox() {
            return BBox.of(x, (float) top, x + width, (float) top + height);
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
        List<Block> blocks = new ArrayList<>(assembleBlocks(lines, pageIndex));
        blocks.addAll(captureImages(pdf, pageIndex));
        blocks.sort((a, b) -> Double.compare(a.top(), b.top()));
        return blocks;
    }

    // ---------- 文本行捕获 ----------

    /** 词：文本 + bbox + 是否与前一词存在空格分隔（沿用 PDFTextStripper 词边界判定）。 */
    private record Word(String text, float x1, float top, float x2, float height, boolean spaceBefore) {
    }

    /** 行：top 为行内最小 y，height 为最大字高。 */
    private record Line(float top, float height, List<Word> words) {

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
            for (Word w : words) {
                top = Math.min(top, w.top());
                height = Math.max(height, w.height());
            }
            lines.add(new Line(top, height, words));
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
    private List<Block> assembleBlocks(List<Line> lines, int pageIndex) {
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
                blocks.add(new TextBlock(lines.get(i).top(), text, lines.get(i).bbox()));
            }
            i++;
        }
        return blocks;
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
                    last.getXDirAdj() + last.getWidthDirAdj(), first.getHeightDir(), pendingSpace));
            pendingSpace = false;
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
