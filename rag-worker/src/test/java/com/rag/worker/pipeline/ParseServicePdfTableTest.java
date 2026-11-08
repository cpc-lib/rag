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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** RAG-007：PDF bbox 表格经 ParseService 解析后以 Markdown 表格进入页文本。 */
class ParseServicePdfTableTest {

    @Test
    void pdfTableIsParsedAsMarkdown() throws Exception {
        byte[] pdfBytes = pdfWithTable();
        MinioStorage minio = mock(MinioStorage.class);
        when(minio.download(anyString())).thenReturn(new ByteArrayInputStream(pdfBytes));
        OcrService ocr = mock(OcrService.class);
        when(ocr.available()).thenReturn(false);

        ParseService service = new ParseService(minio, ocr, mock(VisionClient.class));
        DocumentEntity doc = new DocumentEntity();
        doc.setFileName("report.pdf");
        doc.setObjectKey("tenant/kb/2026-01-01/report.pdf");

        ParseService.ParseOutcome outcome = service.parse(doc, null);

        String text = outcome.pages().get(0).text();
        assertTrue(text.contains("| Name | Age | City |"), text);
        assertTrue(text.contains("| Alice | 30 | Paris |"), text);
        assertTrue(text.contains("Quarterly report summary"), text);
        // 表格保持在前言与结语之间
        int head = text.indexOf("Quarterly report summary");
        int table = text.indexOf("| Name |");
        int tail = text.indexOf("End of table section");
        assertTrue(head < table && table < tail, text);
    }

    private byte[] pdfWithTable() throws Exception {
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(pdf, page)) {
                text(cs, 50, 730, "Quarterly report summary");
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
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.save(out);
            return out.toByteArray();
        }
    }

    private void text(PDPageContentStream cs, float x, float y, String text) throws IOException {
        cs.beginText();
        cs.setFont(PDType1Font.HELVETICA, 10);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }
}
