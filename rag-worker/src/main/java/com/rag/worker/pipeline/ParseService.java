package com.rag.worker.pipeline;

import com.rag.worker.infrastructure.ocr.OcrService;
import com.rag.worker.infrastructure.persistence.entity.DocumentEntity;
import com.rag.worker.infrastructure.persistence.entity.ModelEntity;
import com.rag.worker.infrastructure.storage.MinioStorage;
import com.rag.worker.infrastructure.llm.VisionClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 多格式解析（spec 4.1）：MD/TXT 直读；DOCX/XLSX 用 POI；PDF 文本层 + 扫描页 OCR；
 * 图片优先视觉大模型，其次 OCR。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParseService {

    public record RawPage(int page, String text) {
    }

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
            case "txt", "md" -> new ParseOutcome(
                    List.of(new RawPage(0, new String(bytes, StandardCharsets.UTF_8))), new ArrayList<>());
            case "docx" -> parseDocx(bytes);
            case "xlsx" -> parseXlsx(bytes);
            case "pdf" -> parsePdf(doc, bytes);
            case "html", "htm" -> parseHtml(bytes);
            case "png", "jpg", "jpeg" -> parseImage(doc, vision, bytes, ext);
            default -> throw new IllegalStateException("不支持的文件类型: " + ext);
        };
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
                StringBuilder sb = new StringBuilder();
                String sheetName = workbook.getSheetName(s);
                sb.append("# ").append(sheetName == null ? "Sheet" + s : sheetName).append('\n');
                for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                    XSSFRow row = sheet.getRow(r);
                    if (row == null) {
                        continue;
                    }
                    List<String> cells = new ArrayList<>();
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        var cell = row.getCell(c);
                        cells.add(cell == null ? "" : cell.toString().strip());
                    }
                    if (cells.stream().anyMatch(v -> !v.isEmpty())) {
                        sb.append(String.join(" | ", cells)).append('\n');
                    }
                }
                if (!sb.isEmpty()) {
                    pages.add(new RawPage(s, sb.toString()));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Excel 解析失败: " + e.getMessage(), e);
        }
        return new ParseOutcome(pages, new ArrayList<>());
    }

    private ParseOutcome parsePdf(DocumentEntity doc, byte[] bytes) {
        List<RawPage> pages = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        try (PDDocument pdf = PDDocument.load(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            PDFRenderer renderer = new PDFRenderer(pdf);
            int total = pdf.getNumberOfPages();
            for (int i = 0; i < total; i++) {
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                String text = stripper.getText(pdf);
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
                    }
                } else {
                    warnings.add("第" + (i + 1) + "页为扫描件且OCR未配置已跳过");
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("PDF 解析失败: " + e.getMessage(), e);
        }
        return new ParseOutcome(pages, warnings);
    }

    private ParseOutcome parseImage(DocumentEntity doc, ModelEntity vision, byte[] bytes, String ext) {
        List<RawPage> pages = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String text = null;
        if (vision != null && visionClient.isConfigured(vision.getBaseUrl(), vision.getModel())) {
            text = visionClient.describe(vision.getBaseUrl(), vision.getApiKey(), vision.getModel(),
                    bytes, "image/" + ("jpg".equals(ext) ? "jpeg" : ext));
            if (text == null) {
                warnings.add("视觉模型调用失败");
            }
        }
        if (text == null && ocrService.available()) {
            try {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
                text = ocrService.ocr(image);
            } catch (Exception e) {
                warnings.add("图片OCR失败: " + e.getMessage());
            }
        }
        if (text == null) {
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
