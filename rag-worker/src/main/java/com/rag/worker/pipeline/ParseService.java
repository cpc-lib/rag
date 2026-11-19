package com.rag.worker.pipeline;

import com.rag.worker.infrastructure.ocr.OcrService;
import com.rag.api.infrastructure.persistence.entity.DocumentEntity;
import com.rag.api.infrastructure.persistence.entity.ModelEntity;
import com.rag.worker.infrastructure.storage.ObjectStorage;
import com.rag.worker.infrastructure.llm.VisionClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
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
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 多格式解析（spec 4.1）：MD/TXT 直读；DOC/DOCX/XLSX 用 POI；PDF 文本层 + 扫描页 OCR；
 * 图片同时提取 OCR 文字与视觉描述（可用时）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParseService {

    /** 宽或高小于该值的图片视为图标/装饰，不进入切片。 */
    private static final int MIN_IMAGE_SIZE = 120;
    /** 单文档最多抽取的内嵌图片数，避免视觉模型调用失控。 */
    private static final int MAX_IMAGES = 200;
    /** 单页矢量绘制操作数达到该值视为图形密集页（架构图/流程图）。 */
    private static final int VECTOR_OP_THRESHOLD = 40;
    /** 图形密集页的文字量上限：超过说明主要是正文/表格，整页截图会与文本切片重复。 */
    private static final int PAGE_SNAPSHOT_MAX_TEXT = 800;
    /** 单文档最多渲染的整页截图数（矢量图兜底，Vision 成本控制）。 */
    private static final int MAX_PAGE_SNAPSHOTS = 20;

    public record RawPage(int page, String text) {
    }

    /**
     * PDF 图片素材：page 从 0 起，png 为归一化后的 PNG 字节，hash 为前 8 位 SHA-256（去重与命名）。
     * pageSnapshot=true 表示整页截图（矢量图兜底），文本层已单独抽取，无需再做 OCR。
     */
    public record RawImage(int page, int width, int height, byte[] png, String hash, boolean pageSnapshot) {
    }

    public record ParseOutcome(List<RawPage> pages, List<String> warnings, List<RawImage> images) {
        public ParseOutcome(List<RawPage> pages, List<String> warnings) {
            this(pages, warnings, new ArrayList<>());
        }
    }

    private final ObjectStorage minio;
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
            case "txt", "md" -> new ParseOutcome(
                    List.of(new RawPage(0, new String(bytes, StandardCharsets.UTF_8))), new ArrayList<>());
            case "docx" -> parseDocx(bytes);
            case "doc" -> parseDoc(bytes);
            case "xlsx" -> parseXlsx(bytes);
            case "pdf" -> parsePdf(doc, vision, bytes);
            case "html", "htm" -> parseHtml(bytes);
            case "png", "jpg", "jpeg" -> parseImage(doc, vision, bytes, ext);
            default -> throw new IllegalStateException("不支持的文件类型: " + ext);
        };
    }

    /**
     * .doc（Word 97-2003 二进制）：HWPF 抽取正文，换行归一化为 \n。
     */
    private ParseOutcome parseDoc(byte[] bytes) {
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(bytes))) {
            String text = new WordExtractor(document).getText();
            String normalized = text.replace("\r\n", "\n").replace('\r', '\n')
                    .replaceAll("\n{3,}", "\n\n");
            return new ParseOutcome(List.of(new RawPage(0, normalized.strip())), new ArrayList<>());
        } catch (Exception e) {
            throw new IllegalStateException("doc 解析失败: " + e.getMessage(), e);
        }
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

    /** Word 表格 → Markdown 表格（指南 §16，供表格保护与切片）。 */
    private String docxTableToMarkdown(XWPFTable table) {
        List<List<String>> rows = new ArrayList<>();
        int maxCols = 0;
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = row.getTableCells().stream()
                    .map(XWPFTableCell::getText)
                    .map(t -> t == null ? "" : t.replace("\\", "\\\\")
                            .replace("|", "\\|").replaceAll("\\s+", " ").strip())
                    .toList();
            rows.add(cells);
            maxCols = Math.max(maxCols, cells.size());
        }
        if (rows.isEmpty() || maxCols == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(toMdRow(rows.get(0), maxCols)).append('\n');
        sb.append('|').append("---|".repeat(maxCols)).append('\n');
        for (int r = 1; r < rows.size(); r++) {
            sb.append(toMdRow(rows.get(r), maxCols)).append('\n');
        }
        return sb.toString();
    }

    private String toMdRow(List<String> cells, int cols) {
        StringBuilder sb = new StringBuilder("|");
        for (int c = 0; c < cols; c++) {
            sb.append(c < cells.size() ? cells.get(c) : "").append('|');
        }
        return sb.toString();
    }

    private ParseOutcome parseXlsx(byte[] bytes) {
        List<RawPage> pages = new ArrayList<>();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                XSSFSheet sheet = workbook.getSheetAt(s);
                List<List<String>> rows = new ArrayList<>();
                int maxCols = 0;
                for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                    XSSFRow row = sheet.getRow(r);
                    if (row == null) {
                        continue;
                    }
                    List<String> cells = new ArrayList<>();
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        var cell = row.getCell(c);
                        cells.add(cell == null ? "" : cell.toString().replace("\\", "\\\\")
                                .replace("|", "\\|").replaceAll("\\s+", " ").strip());
                    }
                    if (cells.stream().anyMatch(v -> !v.isEmpty())) {
                        rows.add(cells);
                        maxCols = Math.max(maxCols, cells.size());
                    }
                }
                if (!rows.isEmpty()) {
                    String sheetName = workbook.getSheetName(s);
                    StringBuilder sb = new StringBuilder("# ")
                            .append(sheetName == null ? "Sheet" + s : sheetName).append("\n\n")
                            .append(toMdRow(rows.get(0), maxCols)).append('\n')
                            .append('|').append("---|".repeat(maxCols)).append('\n');
                    for (int r = 1; r < rows.size(); r++) {
                        sb.append(toMdRow(rows.get(r), maxCols)).append('\n');
                    }
                    pages.add(new RawPage(s, sb.toString()));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Excel 解析失败: " + e.getMessage(), e);
        }
        return new ParseOutcome(pages, new ArrayList<>());
    }

    private ParseOutcome parsePdf(DocumentEntity doc, ModelEntity vision, byte[] bytes) {
        List<RawPage> pages = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<RawImage> images = new ArrayList<>();
        Set<String> imageHashes = new HashSet<>();
        boolean visionReady = vision != null
                && visionClient.isConfigured(vision.getBaseUrl(), vision.getModel());
        int snapshots = 0;
        try (PDDocument pdf = PDDocument.load(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            PDFRenderer renderer = new PDFRenderer(pdf);
            int total = pdf.getNumberOfPages();
            for (int i = 0; i < total; i++) {
                PDPage page = pdf.getPage(i);
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                String text = stripper.getText(pdf);
                extractImages(page, i, images, imageHashes, warnings);
                // 矢量架构图（路径绘制）按整页截图兜底；需 Vision 才有检索价值
                if (visionReady && snapshots < MAX_PAGE_SNAPSHOTS && text != null
                        && text.strip().length() <= PAGE_SNAPSHOT_MAX_TEXT
                        && addPageSnapshot(page, i, images, imageHashes, renderer)) {
                    snapshots++;
                }
                if (text != null && text.strip().length() >= 10) {
                    pages.add(new RawPage(i, text));
                    continue;
                }
                // 无文本层 → 扫描页走 OCR
                if (ocrService.available()) {
                    try {
                        BufferedImage image = renderer.renderImageWithDPI(i, 150);
                        String ocrText = ocrService.ocr(image);
                        pages.add(new RawPage(i, ocrText == null ? "" : ocrText));
                    } catch (Exception oe) {
                        log.warn("PDF 第 {} 页 OCR 失败: {}", i + 1, oe.getMessage());
                        warnings.add("第" + (i + 1) + "页OCR失败已跳过");
                        pages.add(new RawPage(i, ""));
                    }
                } else {
                    warnings.add("第" + (i + 1) + "页为扫描件且OCR未配置已跳过");
                    pages.add(new RawPage(i, ""));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("PDF 解析失败: " + e.getMessage(), e);
        }
        return new ParseOutcome(pages, warnings, images);
    }

    /**
     * 抽取一页内嵌位图（PDImageXObject）：跳过蒙版/小图标，按内容哈希去重。
     * 矢量绘制的图表不是位图，由 {@link #addPageSnapshot} 整页截图兜底。
     */
    private void extractImages(PDPage page, int pageIndex, List<RawImage> images,
                               Set<String> hashes, List<String> warnings) {
        PDResources resources = page.getResources();
        if (resources == null) {
            return;
        }
        for (COSName name : resources.getXObjectNames()) {
            if (images.size() >= MAX_IMAGES) {
                if (!warnings.contains("图片数量超过" + MAX_IMAGES + "张，其余已忽略")) {
                    warnings.add("图片数量超过" + MAX_IMAGES + "张，其余已忽略");
                }
                return;
            }
            PDXObject xObject;
            try {
                xObject = resources.getXObject(name);
            } catch (Exception e) {
                continue;
            }
            if (!(xObject instanceof PDImageXObject image) || image.isStencil()
                    || image.getWidth() < MIN_IMAGE_SIZE || image.getHeight() < MIN_IMAGE_SIZE) {
                continue;
            }
            byte[] png;
            try {
                BufferedImage buffered = image.getImage();
                if (buffered == null) {
                    continue;
                }
                png = toPng(buffered);
            } catch (Exception e) {
                log.warn("PDF 第 {} 页图片解码失败已跳过: {}", pageIndex + 1, e.getMessage());
                continue;
            }
            String hash = sha256Prefix(png);
            if (!hashes.add(hash)) {
                continue;
            }
            images.add(new RawImage(pageIndex, image.getWidth(), image.getHeight(), png, hash, false));
        }
    }

    /**
     * 图形密集页整页截图（矢量架构图/流程图兜底）：矢量绘制操作多、文字量少时触发。
     * 截图仅交给 Vision 描述（文本层已单独抽取，不重复 OCR）。
     */
    private boolean addPageSnapshot(PDPage page, int pageIndex, List<RawImage> images,
                                    Set<String> hashes, PDFRenderer renderer) {
        try {
            if (countVectorOps(page) < VECTOR_OP_THRESHOLD) {
                return false;
            }
        } catch (Exception e) {
            log.warn("PDF 第 {} 页矢量内容统计失败: {}", pageIndex + 1, e.getMessage());
            return false;
        }
        try {
            BufferedImage rendered = renderer.renderImageWithDPI(pageIndex, 150);
            byte[] png = toPng(rendered);
            String hash = sha256Prefix(png);
            if (!hashes.add(hash)) {
                return false;
            }
            images.add(new RawImage(pageIndex, rendered.getWidth(), rendered.getHeight(), png, hash, true));
            return true;
        } catch (Exception e) {
            log.warn("PDF 第 {} 页整页截图失败: {}", pageIndex + 1, e.getMessage());
            return false;
        }
    }

    /** 统计页面矢量绘制操作数（填充/描边/阴影），用于识别矢量绘制的架构图页面。 */
    private int countVectorOps(PDPage page) throws IOException {
        VectorOpCounter counter = new VectorOpCounter(page);
        counter.processPage(page);
        return counter.ops;
    }

    /** 只统计绘制操作，忽略路径点细节（文字是字形不是路径，正文页操作数很低）。 */
    private static final class VectorOpCounter extends PDFGraphicsStreamEngine {

        private int ops;

        VectorOpCounter(PDPage page) {
            super(page);
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            ops++;
        }

        @Override
        public void drawImage(PDImage pdImage) {
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
        public void endPath() {
        }

        @Override
        public void strokePath() {
            ops++;
        }

        @Override
        public void fillPath(int windingRule) {
            ops++;
        }

        @Override
        public void fillAndStrokePath(int windingRule) {
            ops++;
        }

        @Override
        public void shadingFill(COSName shadingName) {
            ops++;
        }
    }

    /** 统一转 PNG：PDF 里可能是 CMYK/1-bit/带 alpha 等 ImageIO 不直接支持的颜色模型。 */
    private byte[] toPng(BufferedImage source) throws IOException {
        int type = source.getColorModel().hasAlpha()
                ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), type);
        java.awt.Graphics2D g = rgb.createGraphics();
        try {
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(rgb, "png", out)) {
            throw new IOException("PNG 编码器不可用");
        }
        return out.toByteArray();
    }

    /** 内容哈希前 8 位十六进制：用于同一文档内图片去重与对象命名。 */
    private String sha256Prefix(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(java.util.Arrays.hashCode(data));
        }
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

    /** HTML 表格 → Markdown 表格。 */
    private String htmlTableToMarkdown(org.jsoup.nodes.Element table) {
        List<org.jsoup.nodes.Element> rows = table.select("tr");
        if (rows.isEmpty()) {
            return "";
        }
        List<List<String>> data = new ArrayList<>();
        int maxCols = 0;
        for (org.jsoup.nodes.Element row : rows) {
            List<String> cells = row.select("td, th").stream()
                    .map(c -> c.text().replace("\\", "\\\\").replace("|", "\\|")
                            .replaceAll("\\s+", " ").strip())
                    .toList();
            data.add(cells);
            maxCols = Math.max(maxCols, cells.size());
        }
        StringBuilder sb = new StringBuilder();
        sb.append(toMdRow(data.get(0), maxCols)).append('\n');
        sb.append('|').append("---|".repeat(maxCols)).append('\n');
        for (int r = 1; r < data.size(); r++) {
            sb.append(toMdRow(data.get(r), maxCols)).append('\n');
        }
        return sb.toString();
    }

    private String extOf(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }
}
