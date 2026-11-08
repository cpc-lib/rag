package com.rag.worker.pipeline;

import com.rag.api.infrastructure.persistence.entity.DocumentEntity;
import com.rag.worker.infrastructure.llm.VisionClient;
import com.rag.worker.infrastructure.ocr.OcrService;
import com.rag.worker.infrastructure.storage.MinioStorage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** RAG-007：跨页表格在解析阶段合并，续页重复表头只保留一次。 */
class ParseServicePdfTableContinuationTest {

    @Test
    void tableAcrossPagesIsMergedIntoFirstPage() throws Exception {
        byte[] pdfBytes = pdfWithTableAcrossPages();
        MinioStorage minio = mock(MinioStorage.class);
        when(minio.download(anyString())).thenReturn(new ByteArrayInputStream(pdfBytes));
        OcrService ocr = mock(OcrService.class);
        when(ocr.available()).thenReturn(false);

        ParseService service = new ParseService(minio, ocr, mock(VisionClient.class));
        DocumentEntity doc = new DocumentEntity();
        doc.setFileName("report.pdf");
        doc.setObjectKey("tenant/kb/2026-01-01/report.pdf");

        ParseService.ParseOutcome outcome = service.parse(doc, null);

        assertEquals(1, outcome.pages().size(), "续页只剩表格与页码，应并入上一页");
        String text = outcome.pages().get(0).text();
        int headerCount = occurrences(text, "| Name | Age | City |");
        assertEquals(1, headerCount, "表头只保留一次: " + text);
        assertTrue(text.contains("Alice"), text);
        assertTrue(text.contains("Carol"), "续页数据行应并入同一表格: " + text);
        assertTrue(text.contains("Dave"), text);
        // 表头在数据行之前，两页数据保持原顺序
        assertTrue(text.indexOf("Alice") < text.indexOf("Carol"), text);
    }

    private int occurrences(String text, String target) {
        int count = 0;
        int index = text.indexOf(target);
        while (index >= 0) {
            count++;
            index = text.indexOf(target, index + target.length());
        }
        return count;
    }

    private byte[] pdfWithTableAcrossPages() throws Exception {
        try (PDDocument pdf = new PDDocument()) {
            PDPage first = new PDPage();
            pdf.addPage(first);
            try (PDPageContentStream cs = new PDPageContentStream(pdf, first)) {
                text(cs, 50, 730, "Quarterly report summary");
                tableHeader(cs, 650);
                tableRow(cs, 630, "Alice", "30", "Paris");
                text(cs, 50, 30, "1");
            }
            PDPage second = new PDPage();
            pdf.addPage(second);
            try (PDPageContentStream cs = new PDPageContentStream(pdf, second)) {
                tableHeader(cs, 750);
                tableRow(cs, 730, "Carol", "41", "Berlin");
                tableRow(cs, 710, "Dave", "35", "Rome");
                text(cs, 50, 30, "2");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.save(out);
            return out.toByteArray();
        }
    }

    private void tableHeader(PDPageContentStream cs, float y) throws IOException {
        text(cs, 50, y, "Name");
        text(cs, 200, y, "Age");
        text(cs, 350, y, "City");
    }

    private void tableRow(PDPageContentStream cs, float y, String name, String age, String city)
            throws IOException {
        text(cs, 50, y, name);
        text(cs, 200, y, age);
        text(cs, 350, y, city);
    }

    private void text(PDPageContentStream cs, float x, float y, String text) throws IOException {
        cs.beginText();
        cs.setFont(PDType1Font.HELVETICA, 10);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }
}
