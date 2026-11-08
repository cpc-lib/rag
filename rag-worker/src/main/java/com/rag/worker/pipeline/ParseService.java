package com.rag.worker.pipeline;

import com.rag.worker.infrastructure.ocr.OcrService;
import com.rag.api.infrastructure.persistence.entity.DocumentEntity;
import com.rag.api.infrastructure.persistence.entity.ModelEntity;
import com.rag.worker.infrastructure.storage.MinioStorage;
import com.rag.worker.infrastructure.llm.VisionClient;
import com.rag.worker.pipeline.pdf.PdfLayoutExtractor;
import com.rag.worker.pipeline.pdf.PdfTableContinuation;
import com.rag.worker.pipeline.table.TableModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 多格式解析（spec 4.1）：MD/TXT/CSV/JSON/常见代码文件直读；DOCX/XLSX 用 POI；
 * PDF 文本层 + 扫描页 OCR；图片同时提取 OCR 文字与视觉描述（可用时）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParseService {

    /**
     * 解析页：text 为页文本，spans 记录版面块在 text 中的字符区间与 bbox（ISSUE RAG-007）。
     * 非 PDF 或纯文本解析没有版面信息时 spans 为空，切片元数据只保留页码。
     */
    public record RawPage(int page, String text, List<BlockSpan> spans) {

        public RawPage(int page, String text) {
            this(page, text, List.of());
        }
    }

    /**
     * 版面块在页文本中的区间 [start, end)：blockId 形如 p3-b2（页-块，1 起），
     * 供切片阶段反查所属块并写入 chunk 的 bbox / source_id。
     */
    public record BlockSpan(int start, int end, String blockId, BBox bbox) {
    }

    /** 按纯文本直读、无需专用解析器的扩展名（与上传白名单 / ChunkStrategyRouter 保持一致）。 */
    private static final Set<String> TEXT_EXTS = Set.of(
            "txt", "md", "json", "sql", "xml", "yml", "yaml", "properties", "log", "ini", "conf",
            "java", "py", "go", "js", "jsx", "ts", "tsx", "sh");

    /** Excel 单元格显示格式化（日期/金额/编号按单元格格式输出，避免变成浮点串）。 */
    private static final DataFormatter FORMATTER = new DataFormatter();

    public record ParseOutcome(List<RawPage> pages, List<String> warnings) {
    }

    private final MinioStorage minio;
    private final OcrService ocrService;
    private final VisionClient visionClient;

    public ParseOutcome parse(DocumentEntity doc, ModelEntity vision) {
        byte[] bytes;
        try (var in = minio.download(doc.getObjectKey())) {
            bytes = in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("文件下载失败: " + e.getMessage(), e);
        }
        String ext = extOf(doc.getFileName());
        return switch (ext) {
            case "csv" -> parseCsv(bytes);
            case "docx" -> parseDocx(bytes);
            case "xlsx" -> parseXlsx(bytes);
            case "pdf" -> parsePdf(doc, vision, bytes);
            case "html", "htm" -> parseHtml(bytes);
            case "png", "jpg", "jpeg" -> parseImage(doc, vision, bytes, ext);
            default -> {
                if (TEXT_EXTS.contains(ext)) {
                    yield plainText(bytes);
                }
                throw new NonRetryableException("不支持的文件类型: " + ext);
            }
        };
    }

    private ParseOutcome plainText(byte[] bytes) {
        return new ParseOutcome(
                List.of(new RawPage(0, new String(bytes, StandardCharsets.UTF_8))), new ArrayList<>());
    }

    /** CSV 转为 Markdown 表格，首行作为表头；解析异常时回退为纯文本。 */
    private ParseOutcome parseCsv(byte[] bytes) {
        String raw = new String(bytes, StandardCharsets.UTF_8);
        if (raw.startsWith("\uFEFF")) {
            raw = raw.substring(1);
        }
        List<List<String>> rows = parseCsvRows(raw);
        if (rows.isEmpty()) {
            return plainText(bytes);
        }
        int cols = rows.stream().mapToInt(List::size).max().orElse(0);
        if (cols == 0) {
            return plainText(bytes);
        }
        List<List<TableModel.Cell>> grid = new ArrayList<>();
        for (List<String> row : rows) {
            List<TableModel.Cell> cells = new ArrayList<>(cols);
            for (int i = 0; i < cols; i++) {
                cells.add(TableModel.Cell.of(i < row.size() ? row.get(i) : ""));
            }
            grid.add(cells);
        }
        String markdown = new TableModel(null, 0, grid).toMarkdown();
        return new ParseOutcome(List.of(new RawPage(0, markdown)), new ArrayList<>());
    }

    /** 最小 RFC4180 CSV 解析：支持双引号包裹、引号转义与字段内换行。 */
    private List<List<String>> parseCsvRows(String raw) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < raw.length() && raw.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < raw.length() && raw.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                if (!(row.size() == 1 && row.get(0).isBlank())) {
                    rows.add(row);
                }
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    private ParseOutcome parseDocx(byte[] bytes) {
        List<RawPage> pages = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (var element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph p) {
                    String text = p.getText();
                    if (text == null || text.isBlank()) {
                        continue;
                    }
                    Integer level = headingLevel(document, p);
                    if (level != null) {
                        sb.append("#".repeat(Math.max(1, Math.min(level, 6))))
                                .append(' ').append(text.strip()).append('\n');
                    } else {
                        sb.append(text.strip()).append('\n');
                    }
                } else if (element instanceof org.apache.poi.xwpf.usermodel.XWPFTable table) {
                    sb.append(docxTableToMarkdown(table));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Word 解析失败: " + e.getMessage(), e);
        }
        pages.add(new RawPage(0, sb.toString()));
        return new ParseOutcome(pages, new ArrayList<>());
    }

    /** 识别 Word 标题层级：样式名 heading N / 标题 N，其次大纲级别；非标题返回 null。 */
    private Integer headingLevel(XWPFDocument document, XWPFParagraph p) {
        String styleId = p.getStyleID();
        if (styleId != null && document.getStyles() != null) {
            var style = document.getStyles().getStyle(styleId);
            String name = style == null ? "" : style.getName();
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?i)(?:heading|标题)\\s*(\\d)").matcher(name == null ? "" : name);
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        }
        try {
            var ppr = p.getCTP().getPPr();
            if (ppr != null && ppr.isSetOutlineLvl()) {
                int lvl = ppr.getOutlineLvl().getVal().intValue() + 1;
                return lvl <= 9 ? lvl : null;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Word 表格 → 统一表格模型（指南 §16）：保留 gridSpan 横向合并，供后续切片重复表头。 */
    private String docxTableToMarkdown(XWPFTable table) {
        List<List<TableModel.Cell>> grid = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<TableModel.Cell> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(new TableModel.Cell(cell.getText(), docxGridSpan(cell), 1));
            }
            if (!cells.isEmpty()) {
                grid.add(cells);
            }
        }
        return new TableModel(null, 0, grid).toMarkdown();
    }

    /** Word 横向合并：gridSpan 表示单元格横跨的网格列数。 */
    private int docxGridSpan(XWPFTableCell cell) {
        try {
            var tcPr = cell.getCTTc().getTcPr();
            if (tcPr != null && tcPr.getGridSpan() != null && tcPr.getGridSpan().getVal() != null) {
                return tcPr.getGridSpan().getVal().intValue();
            }
        } catch (Exception ignored) {
        }
        return 1;
    }

    private ParseOutcome parseXlsx(byte[] bytes) {
        try {
            return new ParseOutcome(xlsxPages(bytes), new ArrayList<>());
        } catch (Exception e) {
            throw new IllegalStateException("Excel 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * Excel → 每 Sheet 一页 Markdown 表格（首行表头）。包级可见用于多 Sheet 回归测试。
     * 合并区域按 colspan/rowspan 展开，保证表头与数据行列对齐。
     */
    static List<RawPage> xlsxPages(byte[] bytes) throws Exception {
        List<RawPage> pages = new ArrayList<>();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                XSSFSheet sheet = workbook.getSheetAt(s);
                List<List<TableModel.Cell>> grid = xlsxGrid(sheet);
                if (grid.isEmpty()) {
                    continue;
                }
                String sheetName = workbook.getSheetName(s);
                String markdown = new TableModel(sheetName, s, grid).toMarkdown();
                pages.add(new RawPage(s, "# " + (sheetName == null ? "Sheet" + s : sheetName)
                        + "\n\n" + markdown));
            }
        }
        return pages;
    }

    /** 读取 Sheet 为单元格网格：预扫描合并区域，跳过被覆盖格子并在起点写入跨度。 */
    private static List<List<TableModel.Cell>> xlsxGrid(XSSFSheet sheet) {
        Map<Long, CellRangeAddress> mergeStart = new HashMap<>();
        Set<Long> covered = new HashSet<>();
        int maxCols = 0;
        for (CellRangeAddress region : sheet.getMergedRegions()) {
            mergeStart.put(cellKey(region.getFirstRow(), region.getFirstColumn()), region);
            for (int r = region.getFirstRow(); r <= region.getLastRow(); r++) {
                for (int c = region.getFirstColumn(); c <= region.getLastColumn(); c++) {
                    if (r != region.getFirstRow() || c != region.getFirstColumn()) {
                        covered.add(cellKey(r, c));
                    }
                }
            }
            maxCols = Math.max(maxCols, region.getLastColumn() + 1);
        }
        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
            XSSFRow row = sheet.getRow(r);
            if (row != null && row.getLastCellNum() > 0) {
                maxCols = Math.max(maxCols, row.getLastCellNum());
            }
        }
        List<List<TableModel.Cell>> grid = new ArrayList<>();
        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
            XSSFRow row = sheet.getRow(r);
            List<TableModel.Cell> cells = new ArrayList<>();
            for (int c = 0; c < maxCols; c++) {
                long key = cellKey(r, c);
                if (covered.contains(key)) {
                    continue;
                }
                XSSFCell cell = row == null ? null : row.getCell(c);
                CellRangeAddress region = mergeStart.get(key);
                String text = xlsxCellText(cell);
                if (region != null) {
                    cells.add(new TableModel.Cell(text,
                            region.getLastColumn() - region.getFirstColumn() + 1,
                            region.getLastRow() - region.getFirstRow() + 1));
                } else {
                    cells.add(TableModel.Cell.of(text));
                }
            }
            if (cells.stream().anyMatch(c -> !c.text().isBlank())) {
                grid.add(cells);
            }
        }
        return grid;
    }

    private static long cellKey(int row, int col) {
        return ((long) row << 32) | (col & 0xffffffffL);
    }

    /** 用 DataFormatter 保留日期/金额/编号的显示格式；公式单元格保持原值输出。 */
    private static String xlsxCellText(XSSFCell cell) {
        if (cell == null) {
            return "";
        }
        try {
            if (cell.getCellType() == CellType.FORMULA) {
                return cell.toString();
            }
            return FORMATTER.formatCellValue(cell);
        } catch (Exception e) {
            return cell.toString();
        }
    }

    /** 单文档最多抽取的图片数。 */
    private static final int MAX_IMAGES_PER_DOC = 30;
    /** 小于该尺寸（显示尺寸 pt 或像素）的图片多为图标/线条，不进入 OCR/Vision。 */
    private static final int MIN_IMAGE_SIDE = 80;

    private final PdfLayoutExtractor pdfLayoutExtractor = new PdfLayoutExtractor();

    private ParseOutcome parsePdf(DocumentEntity doc, ModelEntity vision, byte[] bytes) {
        List<RawPage> pages = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean imageChannels = ocrService.available()
                || (vision != null && visionClient.isConfigured(vision.getBaseUrl(), vision.getModel()));
        int[] imageBudget = {MAX_IMAGES_PER_DOC};
        // 同一图片对象跨页/重复绘制时复用识别结果，避免重复调用 OCR/Vision
        Map<PDImageXObject, String> imageDescCache = new IdentityHashMap<>();
        try (PDDocument pdf = PDDocument.load(bytes)) {
            PDFRenderer renderer = new PDFRenderer(pdf);
            int total = pdf.getNumberOfPages();
            // 延迟一页输出：下一页抽取后才能判断上一页表格是否接续
            List<PdfLayoutExtractor.Block> pending = null;
            int pendingIndex = -1;
            float pendingHeight = 0;
            for (int i = 0; i < total; i++) {
                List<PdfLayoutExtractor.Block> blocks;
                try {
                    blocks = new ArrayList<>(pdfLayoutExtractor.extractPage(pdf, i));
                } catch (Exception le) {
                    log.warn("PDF 第 {} 页版式抽取失败，回退纯文本: {}", i + 1, le.getMessage());
                    blocks = new ArrayList<>(fallbackTextBlocks(pdf, i));
                }
                boolean absorbed = false;
                if (pending != null) {
                    var connected = PdfTableContinuation.connect(pending, pendingHeight,
                            blocks, pageHeight(pdf, i));
                    blocks = new ArrayList<>(connected.current());
                    absorbed = connected.absorbed();
                    emitPdfPage(pdf, renderer, pendingIndex, pending, pages, warnings, vision,
                            imageBudget, imageChannels, imageDescCache);
                }
                if (absorbed) {
                    // 整页仅剩被接续表格与页码，内容已并入上一页
                    pending = null;
                    pendingIndex = -1;
                    continue;
                }
                pending = blocks;
                pendingIndex = i;
                pendingHeight = pageHeight(pdf, i);
            }
            if (pending != null) {
                emitPdfPage(pdf, renderer, pendingIndex, pending, pages, warnings, vision,
                        imageBudget, imageChannels, imageDescCache);
            }
        } catch (Exception e) {
            throw new IllegalStateException("PDF 解析失败: " + e.getMessage(), e);
        }
        return new ParseOutcome(pages, warnings);
    }

    /** 输出单页：有文本层走版面组装，无文本层按扫描页 OCR。 */
    private void emitPdfPage(PDDocument pdf, PDFRenderer renderer, int index,
                             List<PdfLayoutExtractor.Block> blocks, List<RawPage> pages,
                             List<String> warnings, ModelEntity vision, int[] imageBudget,
                             boolean imageChannels, Map<PDImageXObject, String> imageDescCache) {
        Assembled assembled = assemblePage(blocks, index, vision, warnings, imageBudget,
                imageChannels, imageDescCache);
        if (assembled.text().strip().length() >= 10) {
            pages.add(new RawPage(index, assembled.text(), assembled.spans()));
            return;
        }
        // 无文本层 → 扫描页走 OCR
        if (ocrService.available()) {
            try {
                BufferedImage image = renderer.renderImageWithDPI(index, 150);
                String ocrText = ocrService.ocr(image);
                pages.add(new RawPage(index, ocrText == null ? "" : ocrText));
            } catch (Exception oe) {
                log.warn("PDF 第 {} 页 OCR 失败: {}", index + 1, oe.getMessage());
                warnings.add("第" + (index + 1) + "页OCR失败已跳过");
                pages.add(new RawPage(index, ""));
            }
        } else {
            warnings.add("第" + (index + 1) + "页为扫描件且OCR未配置已跳过");
            pages.add(new RawPage(index, ""));
        }
    }

    /** 页高（与 PdfLayoutExtractor 的 top 坐标系一致，基于 MediaBox）。 */
    private float pageHeight(PDDocument pdf, int index) {
        try {
            return pdf.getPage(index).getMediaBox().getHeight();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 版式抽取失败时按纯文本提取整页（保持阅读顺序），保证页面不丢失。 */
    private List<PdfLayoutExtractor.Block> fallbackTextBlocks(PDDocument pdf, int pageIndex) {
        try {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setStartPage(pageIndex + 1);
            stripper.setEndPage(pageIndex + 1);
            return List.of(new PdfLayoutExtractor.TextBlock(0, stripper.getText(pdf)));
        } catch (Exception e) {
            log.warn("PDF 第 {} 页纯文本回退提取失败: {}", pageIndex + 1, e.getMessage());
            return List.of();
        }
    }

    /**
     * 组装单页文本（ISSUE RAG-007）：文本行 / 表格 Markdown / 图片识别结果
     * 按版面自顶向下顺序拼接，图片说明保留在原文相邻位置而不是页尾。
     * 同时记录每个块在页文本中的区间与 bbox，供切片阶段写入 chunk 元数据。
     */
    private Assembled assemblePage(List<PdfLayoutExtractor.Block> blocks, int pageIndex,
                                   ModelEntity vision, List<String> warnings, int[] imageBudget,
                                   boolean imageChannels, Map<PDImageXObject, String> descCache) {
        StringBuilder sb = new StringBuilder();
        List<BlockSpan> spans = new ArrayList<>();
        int imgSeq = 0;
        boolean budgetWarned = false;
        for (int bi = 0; bi < blocks.size(); bi++) {
            PdfLayoutExtractor.Block block = blocks.get(bi);
            String rendered = null;
            if (block instanceof PdfLayoutExtractor.TextBlock t) {
                rendered = t.text();
            } else if (block instanceof PdfLayoutExtractor.TableBlock t) {
                rendered = t.table().toMarkdown();
            } else if (block instanceof PdfLayoutExtractor.ImageBlock img) {
                if (!imageChannels) {
                    continue;
                }
                if (imageBudget[0] <= 0) {
                    if (!budgetWarned) {
                        warnings.add("PDF 图片数量超过上限 " + MAX_IMAGES_PER_DOC + "，其余已跳过");
                        budgetWarned = true;
                    }
                    continue;
                }
                if (img.width() < MIN_IMAGE_SIDE || img.height() < MIN_IMAGE_SIDE
                        || img.image().getWidth() < MIN_IMAGE_SIDE
                        || img.image().getHeight() < MIN_IMAGE_SIDE) {
                    continue;
                }
                imageBudget[0]--;
                imgSeq++;
                String label = "第" + (pageIndex + 1) + "页图片" + imgSeq;
                String described = descCache.computeIfAbsent(img.image(),
                        k -> describeImageSafely(vision, k, warnings, label));
                if (!described.isBlank()) {
                    rendered = "[" + label + "]\n" + described;
                }
            }
            int start = appendBlock(sb, rendered);
            if (start >= 0) {
                // 块序号按版面块顺序（1 起），与是否输出无关，保证同一文档重复解析时 id 稳定
                spans.add(new BlockSpan(start, sb.length(),
                        "p" + (pageIndex + 1) + "-b" + (bi + 1), block.bbox()));
            }
        }
        return new Assembled(sb.toString(), spans);
    }

    /** 单页组装结果：文本 + 块区间索引。 */
    private record Assembled(String text, List<BlockSpan> spans) {
    }

    /** 追加块内容并返回写入起始偏移；空内容返回 -1（不写入分隔符）。 */
    private int appendBlock(StringBuilder sb, String text) {
        if (text == null || text.isBlank()) {
            return -1;
        }
        if (sb.length() > 0) {
            sb.append("\n\n");
        }
        int start = sb.length();
        sb.append(text.strip());
        return start;
    }

    /** 图片识别（OCR + Vision 双通道），任何异常只告警返回空串，不阻断解析。 */
    private String describeImageSafely(ModelEntity vision, PDImageXObject image,
                                       List<String> warnings, String label) {
        try {
            return describeImage(vision, toPng(image), warnings, label);
        } catch (Exception e) {
            log.warn("{} 处理失败: {}", label, e.getMessage());
            warnings.add(label + "处理失败");
            return "";
        }
    }

    private byte[] toPng(PDImageXObject image) throws Exception {
        BufferedImage buffered = image.getImage();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(buffered, "png", out);
        return out.toByteArray();
    }

    private String describeImage(ModelEntity vision, byte[] png, List<String> warnings, String label) {
        StringBuilder sb = new StringBuilder();
        if (ocrService.available()) {
            try {
                String ocrText = ocrService.ocr(ImageIO.read(new ByteArrayInputStream(png)));
                if (ocrText != null && !ocrText.isBlank()) {
                    sb.append("OCR文字：").append(ocrText.strip()).append("\n");
                }
            } catch (Exception e) {
                log.warn("{} OCR 失败: {}", label, e.getMessage());
                warnings.add(label + "OCR失败");
            }
        }
        if (vision != null && visionClient.isConfigured(vision.getBaseUrl(), vision.getModel())) {
            String visionText = visionClient.describe(vision.getBaseUrl(), vision.getApiKey(),
                    vision.getModel(), png, "image/png");
            if (visionText != null && !visionText.isBlank()) {
                sb.append("视觉描述：").append(visionText.strip());
            } else {
                warnings.add(label + "视觉模型调用失败");
            }
        }
        return sb.toString();
    }

    private ParseOutcome parseImage(DocumentEntity doc, ModelEntity vision, byte[] bytes, String ext) {
        List<RawPage> pages = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String visionText = null;
        if (vision != null && visionClient.isConfigured(vision.getBaseUrl(), vision.getModel())) {
            visionText = visionClient.describe(vision.getBaseUrl(), vision.getApiKey(), vision.getModel(),
                    bytes, "image/" + ("jpg".equals(ext) ? "jpeg" : ext));
            if (visionText == null) {
                warnings.add("视觉模型调用失败");
            }
        }
        String ocrText = null;
        if (ocrService.available()) {
            try {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
                ocrText = ocrService.ocr(image);
            } catch (Exception e) {
                warnings.add("图片OCR失败: " + e.getMessage());
            }
        }
        String text;
        if (ocrText != null && !ocrText.isBlank() && visionText != null && !visionText.isBlank()) {
            text = "OCR文字：\n" + ocrText.strip() + "\n\n视觉描述：\n" + visionText.strip();
        } else {
            text = ocrText != null && !ocrText.isBlank() ? ocrText : visionText;
        }
        if (text == null || text.isBlank()) {
            if (warnings.isEmpty()) {
                warnings.add("图片未配置视觉模型且OCR不可用，已跳过");
            }
            text = "";
        }
        pages.add(new RawPage(0, text));
        return new ParseOutcome(pages, warnings);
    }

    private ParseOutcome parseHtml(byte[] bytes) {
        List<RawPage> pages = new ArrayList<>();
        try {
            org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(
                    new ByteArrayInputStream(bytes), StandardCharsets.UTF_8.name(), "");
            doc.select("script, style").remove();
            StringBuilder sb = new StringBuilder();
            if (doc.body() != null) {
                htmlBlocks(doc.body(), sb);
            }
            pages.add(new RawPage(0, sb.toString()));
        } catch (Exception e) {
            throw new IllegalStateException("HTML 解析失败: " + e.getMessage(), e);
        }
        return new ParseOutcome(pages, new ArrayList<>());
    }

    /** DOM 递归 → Markdown 结构文本（标题/段落/列表/表格/代码块）。 */
    private void htmlBlocks(org.jsoup.nodes.Element el, StringBuilder sb) {
        String tag = el.tagName().toLowerCase(java.util.Locale.ROOT);
        switch (tag) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                appendHtmlBlock(sb, "#".repeat(tag.charAt(1) - '0') + " " + el.text().strip());
                return;
            }
            case "p" -> {
                appendHtmlBlock(sb, el.text().strip());
                return;
            }
            case "pre" -> {
                appendHtmlBlock(sb, "```\n" + el.wholeText().strip() + "\n```");
                return;
            }
            case "table" -> {
                appendHtmlBlock(sb, htmlTableToMarkdown(el));
                return;
            }
            case "ul", "ol" -> {
                StringBuilder list = new StringBuilder();
                int n = 1;
                for (org.jsoup.nodes.Node ch : el.childNodes()) {
                    if (ch instanceof org.jsoup.nodes.Element li && "li".equals(li.tagName())) {
                        String prefix = "ul".equals(tag) ? "- " : (n++) + ". ";
                        list.append(prefix).append(li.text().strip()).append('\n');
                    }
                }
                appendHtmlBlock(sb, list.toString());
                return;
            }
            case "br" -> {
                sb.append('\n');
                return;
            }
            default -> { /* 容器元素：继续向下递归 */ }
        }
        for (org.jsoup.nodes.Node child : el.childNodes()) {
            if (child instanceof org.jsoup.nodes.TextNode tn) {
                sb.append(tn.text());
            } else if (child instanceof org.jsoup.nodes.Element ce) {
                htmlBlocks(ce, sb);
            }
        }
    }

    private void appendHtmlBlock(StringBuilder sb, String block) {
        if (block == null || block.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append("\n\n");
        }
        sb.append(block.strip());
    }

    /** HTML 表格 → 统一表格模型：保留 colspan/rowspan，避免合并单元格导致列错位。 */
    private String htmlTableToMarkdown(org.jsoup.nodes.Element table) {
        List<List<TableModel.Cell>> grid = new ArrayList<>();
        for (org.jsoup.nodes.Element row : table.select("tr")) {
            List<TableModel.Cell> cells = new ArrayList<>();
            for (org.jsoup.nodes.Element cell : row.select("td, th")) {
                cells.add(new TableModel.Cell(cell.text(),
                        htmlSpan(cell.attr("colspan")), htmlSpan(cell.attr("rowspan"))));
            }
            if (!cells.isEmpty()) {
                grid.add(cells);
            }
        }
        return new TableModel(null, 0, grid).toMarkdown();
    }

    private int htmlSpan(String value) {
        try {
            return value == null || value.isBlank() ? 1 : Math.max(1, Integer.parseInt(value.strip()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private String extOf(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }
}
