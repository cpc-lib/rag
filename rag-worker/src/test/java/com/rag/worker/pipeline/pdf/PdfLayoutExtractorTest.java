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

            // 表格块位于上方标题与下方正文之间
            int titleIdx = indexOfText(blocks, "Quarterly report summary");
            int tableIdx = blocks.indexOf(table);
            int tailIdx = indexOfText(blocks, "End of table section.");
            assertTrue(titleIdx >= 0 && titleIdx < tableIdx && tableIdx < tailIdx,
                    "块顺序应保持版面自顶向下: " + blocks);
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
        cs.beginText();
        cs.setFont(PDType1Font.HELVETICA, 10);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }
}
