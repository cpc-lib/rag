package com.rag.worker.pipeline;

import com.rag.api.infrastructure.persistence.entity.DocumentEntity;
import com.rag.worker.infrastructure.llm.VisionClient;
import com.rag.worker.infrastructure.ocr.OcrService;
import com.rag.worker.infrastructure.storage.MinioStorage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** RAG-007：PDF 内嵌图片抽取 + OCR 通道，结果保留在对应页码文本中。 */
class ParseServicePdfImageTest {

    @Test
    void embeddedImageTextIsAppendedToSamePage() throws Exception {
        byte[] pdfBytes = pdfWithImage();
        MinioStorage minio = mock(MinioStorage.class);
        when(minio.download(anyString())).thenReturn(new ByteArrayInputStream(pdfBytes));

        OcrService ocr = mock(OcrService.class);
        when(ocr.available()).thenReturn(true);
        when(ocr.ocr(any(BufferedImage.class))).thenReturn("图片里的文字");

        ParseService service = new ParseService(minio, ocr, mock(VisionClient.class));
        DocumentEntity doc = new DocumentEntity();
        doc.setFileName("figure.pdf");
        doc.setObjectKey("tenant/kb/2026-01-01/figure.pdf");

        ParseService.ParseOutcome outcome = service.parse(doc, null);

        String text = outcome.pages().get(0).text();
        assertTrue(text.contains("[第1页图片]"), text);
        assertTrue(text.contains("图片里的文字"), text);
        assertTrue(outcome.pages().get(0).page() == 0);
    }

    private byte[] pdfWithImage() throws Exception {
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
                cs.drawImage(pdImage, 50, 500, 200, 200);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.save(out);
            return out.toByteArray();
        }
    }
}
