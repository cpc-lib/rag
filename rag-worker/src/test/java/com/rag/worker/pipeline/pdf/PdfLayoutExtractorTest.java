package com.rag.worker.pipeline.pdf;

import com.rag.worker.pipeline.table.TableModel;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RAG-007：PDF bbox 表格检测与图片定位（合成 PDF 验证，不依赖外部中间件）。 */
class PdfLayoutExtractorTest {

    private final PdfLayoutExtractor extractor = new PdfLayoutExtractor();

    @Test
    void tableRegionBecomesMarkdownTable() throws Exception {
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(pdf, page)) {
                text(cs, 50, 730, "Quarterly report summary");
                // 3x3 表格：列首 x 严格对齐，行距规整
                text(cs, 50, 650, "Name");
                text(cs, 200, 650, "Age");
                text(cs, 350, 650, "City");
                text(cs, 50, 630, "Alice");
                text(cs, 200, 630, "30");
                text(cs, 350, 630, "Paris");
                text(cs, 50, 610, "Bob");
                text(cs, 200, 610, "25");
                text(cs, 350, 610, "London");
                text(cs, 50, 540, "End of table section.");
            }

            List<PdfLayoutExtractor.Block> blocks = extractor.extractPage(pdf, 0);

            PdfLayoutExtractor.TableBlock table = blocks.stream()
                    .filter(b -> b instanceof PdfLayoutExtractor.TableBlock)
                    .map(b -> (PdfLayoutExtractor.TableBlock) b)
                    .findFirst().orElseThrow(() -> new AssertionError("未检测到表格"));
            TableModel model = table.table();
            assertEquals(3, model.grid().size());
            assertEquals(3, model.columns());
            List<List<String>> rows = model.expand();
            assertEquals("Name", rows.get(0).get(0));
            assertEquals("Alice", rows.get(1).get(0));
            assertEquals("London", rows.get(2).get(2));
            assertTrue(model.toMarkdown().contains("| Name | Age | City |"), model.toMarkdown());

            // RAG-007：表格块记录覆盖表格行的版面区域
            assertNotNull(table.bbox(), "表格块应有 bbox");
            assertTrue(table.bbox().width() > 300, "表格宽度应覆盖三列: " + table.bbox());
            assertTrue(table.bbox().height() > 40, "表格高度应覆盖表头与数据行: " + table.bbox());

            // 表格块位于上方标题与下方正文之间
            int titleIdx = indexOfText(blocks, "Quarterly report summary");
            int tableIdx = blocks.indexOf(table);
            int tailIdx = indexOfText(blocks, "End of table section.");
            assertTrue(titleIdx >= 0 && titleIdx < tableIdx && tableIdx < tailIdx,
                    "块顺序应保持版面自顶向下: " + blocks);
        }
    }

    @Test
    void textBlockCarriesLineBBox() throws Exception {
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(pdf, page)) {
                text(cs, 72, 700, "Layout line");
            }

            List<PdfLayoutExtractor.Block> blocks = extractor.extractPage(pdf, 0);

            PdfLayoutExtractor.TextBlock block = assertInstanceOf(
                    PdfLayoutExtractor.TextBlock.class, blocks.get(0));
            assertNotNull(block.bbox(), "文本块应有 bbox");
            assertTrue(block.bbox().x() >= 70 && block.bbox().x() <= 74, "左边界: " + block.bbox());
            assertTrue(block.bbox().width() > 30, "宽度应覆盖行文本: " + block.bbox());
            assertTrue(block.bbox().height() > 0, "高度应大于 0: " + block.bbox());
        }
    }

    @Test
    void plainParagraphIsNotDetectedAsTable() throws Exception {
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(pdf, page)) {
                text(cs, 50, 730, "This is a normal paragraph line one.");
                text(cs, 50, 712, "Another normal paragraph line two.");
                text(cs, 50, 694, "Yet another paragraph line three.");
            }

            List<PdfLayoutExtractor.Block> blocks = extractor.extractPage(pdf, 0);

            assertFalse(blocks.stream().anyMatch(b -> b instanceof PdfLayoutExtractor.TableBlock),
                    "普通段落不应误判为表格: " + blocks);
            assertEquals(3, blocks.size());
        }
    }

    @Test
    void imagePlacementIsCapturedInReadingOrder() throws Exception {
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            BufferedImage image = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            g.setColor(Color.BLUE);
            g.fillRect(0, 0, 200, 200);
            g.dispose();
            PDImageXObject pdImage = LosslessFactory.createFromImage(pdf, image);
            try (PDPageContentStream cs = new PDPageContentStream(pdf, page)) {
                text(cs, 50, 730, "Text above the image.");
                cs.drawImage(pdImage, 50, 300, 200, 200);
                text(cs, 50, 120, "Text below the image.");
            }

            List<PdfLayoutExtractor.Block> blocks = extractor.extractPage(pdf, 0);

            PdfLayoutExtractor.ImageBlock img = assertInstanceOf(
                    PdfLayoutExtractor.ImageBlock.class, blocks.get(1), "中间块应为图片: " + blocks);
            assertEquals(50, img.x(), 1);
            assertEquals(300, img.y(), 1);
            assertEquals(200, img.width(), 1);
            assertEquals(200, img.height(), 1);
            assertTrue(blocks.get(0) instanceof PdfLayoutExtractor.TextBlock
                    && ((PdfLayoutExtractor.TextBlock) blocks.get(0)).text().contains("above"));
            assertTrue(blocks.get(2) instanceof PdfLayoutExtractor.TextBlock
                    && ((PdfLayoutExtractor.TextBlock) blocks.get(2)).text().contains("below"));
        }
    }

    @Test
    void semanticKindsAreClassified() throws Exception {
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(pdf, page)) {
                text(cs, 50, 740, "Chapter One", PDType1Font.HELVETICA_BOLD, 18);
                text(cs, 50, 700, "- first list item", PDType1Font.HELVETICA, 10);
                text(cs, 50, 680, "f(x)=a+b-c*d/e", PDType1Font.HELVETICA, 10);
                text(cs, 50, 660, "public void main()", PDType1Font.COURIER, 10);
                text(cs, 50, 640, "This is a plain paragraph.", PDType1Font.HELVETICA, 10);
            }

            List<PdfLayoutExtractor.Block> blocks = extractor.extractPage(pdf, 0);

            assertEquals(PdfLayoutExtractor.BlockKind.TITLE, kindOf(blocks, "Chapter One"));
            assertEquals(PdfLayoutExtractor.BlockKind.LIST, kindOf(blocks, "first list item"));
            assertEquals(PdfLayoutExtractor.BlockKind.FORMULA, kindOf(blocks, "f(x)"));
            assertEquals(PdfLayoutExtractor.BlockKind.CODE, kindOf(blocks, "public void main"));
            assertEquals(PdfLayoutExtractor.BlockKind.PARAGRAPH, kindOf(blocks, "plain paragraph"));
        }
    }

    @Test
    void mergedCellsAreRebuiltFromVectorGrid() throws Exception {
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(pdf, page)) {
                float[] xs = {100, 200, 300, 400};
                float[] ys = {500, 550, 600, 650};
                cs.setLineWidth(1f);
                // 横线：中间行（y=550）在 x=300~400 缺一段 → 末列后两行纵向合并
                for (float y : ys) {
                    if (y == 550) {
                        line(cs, xs[0], y, 300, y);
                    } else {
                        line(cs, xs[0], y, xs[3], y);
                    }
                }
                // 竖线：x=200 在 y=600~650 缺一段 → 首行前两列横向合并
                for (float x : xs) {
                    if (x == 200) {
                        line(cs, x, 500, x, 600);
                    } else {
                        line(cs, x, ys[0], x, ys[3]);
                    }
                }
                text(cs, 110, 620, "Merged Header", PDType1Font.HELVETICA, 10);
                text(cs, 310, 620, "R1C3", PDType1Font.HELVETICA, 10);
                text(cs, 110, 575, "R2C1", PDType1Font.HELVETICA, 10);
                text(cs, 210, 575, "R2C2", PDType1Font.HELVETICA, 10);
                text(cs, 310, 565, "Vertical", PDType1Font.HELVETICA, 10);
                text(cs, 110, 525, "R3C1", PDType1Font.HELVETICA, 10);
                text(cs, 210, 525, "R3C2", PDType1Font.HELVETICA, 10);
            }

            List<PdfLayoutExtractor.Block> blocks = extractor.extractPage(pdf, 0);

            PdfLayoutExtractor.TableBlock table = blocks.stream()
                    .filter(b -> b instanceof PdfLayoutExtractor.TableBlock)
                    .map(b -> (PdfLayoutExtractor.TableBlock) b)
                    .findFirst().orElseThrow(() -> new AssertionError("未识别矢量网格表格: " + blocks));
            List<List<TableModel.Cell>> grid = table.table().grid();
            assertEquals(3, grid.size(), "应有 3 行锚点");

            TableModel.Cell header = grid.get(0).get(0);
            assertEquals(2, header.colspan(), "首行前两列应合并");
            assertTrue(header.text().contains("Merged Header"), header.text());
            assertEquals("R1C3", grid.get(0).get(1).text());

            TableModel.Cell vertical = grid.get(1).get(2);
            assertEquals(2, vertical.rowspan(), "末列后两行应纵向合并");
            assertTrue(vertical.text().contains("Vertical"), vertical.text());
            assertEquals(2, grid.get(2).size(), "被纵向合并覆盖的末列不应重复输出");
            assertEquals("R3C1", grid.get(2).get(0).text());
            assertEquals("R3C2", grid.get(2).get(1).text());
        }
    }

    private PdfLayoutExtractor.BlockKind kindOf(List<PdfLayoutExtractor.Block> blocks, String keyword) {
        for (PdfLayoutExtractor.Block block : blocks) {
            if (block instanceof PdfLayoutExtractor.TextBlock t && t.text().contains(keyword)) {
                return t.kind();
            }
        }
        throw new AssertionError("未找到文本块: " + keyword + " in " + blocks);
    }

    private void line(PDPageContentStream cs, float x1, float y1, float x2, float y2)
            throws IOException {
        cs.moveTo(x1, y1);
        cs.lineTo(x2, y2);
        cs.stroke();
    }

    private int indexOfText(List<PdfLayoutExtractor.Block> blocks, String keyword) {
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i) instanceof PdfLayoutExtractor.TextBlock t
                    && t.text().contains(keyword)) {
                return i;
            }
        }
        return -1;
    }

    private void text(PDPageContentStream cs, float x, float y, String text) throws IOException {
        text(cs, x, y, text, PDType1Font.HELVETICA, 10);
    }

    private void text(PDPageContentStream cs, float x, float y, String text,
                      PDType1Font font, float size) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }
}
