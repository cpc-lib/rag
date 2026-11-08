package com.rag.worker.pipeline;

import com.rag.api.infrastructure.persistence.entity.DocumentEntity;
import com.rag.worker.infrastructure.llm.VisionClient;
import com.rag.worker.infrastructure.ocr.OcrService;
import com.rag.worker.infrastructure.storage.MinioStorage;
import com.rag.worker.pipeline.pdf.PdfLayoutExtractor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** RAG-007：PDF 页眉页脚跨页去重与语义类别向切片元数据传递。 */
class ParseServiceHeaderFooterTest {

    @Test
    void repeatedHeaderFooterIsRemoved() throws Exception {
        byte[] pdf = buildPdf();
        ParseService service = new ParseService(mockMinio(pdf), mockOcr(), mock(VisionClient.class));
        DocumentEntity doc = new DocumentEntity();
        doc.setFileName("report.pdf");
        doc.setObjectKey("t/report.pdf");

        ParseService.ParseOutcome outcome = service.parse(doc, null);

        String all = outcome.pages().stream()
                .map(ParseService.RawPage::text)
                .collect(Collectors.joining("\n"));
        assertFalse(all.contains("ACME Confidential"), "页眉应被剔除: " + all);
        assertTrue(all.contains("Body Page One"), "正文应保留: " + all);
        assertTrue(all.contains("Body Page Three"), "末页正文应保留: " + all);

        ParseService.RawPage first = outcome.pages().get(0);
        List<ParseService.BlockSpan> spans = first.spans();
        assertTrue(spans.stream().anyMatch(s -> s.kind() == PdfLayoutExtractor.BlockKind.TITLE),
                "标题块应携带 TITLE 类别: " + spans);
        assertTrue(spans.stream().anyMatch(s ->
                        s.kind() == PdfLayoutExtractor.BlockKind.PARAGRAPH),
                "段落块应携带 PARAGRAPH 类别: " + spans);
        assertTrue(spans.stream().allMatch(s -> s.bbox() != null), "PDF 块应携带 bbox");
    }

    private MinioStorage mockMinio(byte[] pdf) {
        MinioStorage minio = mock(MinioStorage.class);
        when(minio.download(anyString())).thenReturn(new ByteArrayInputStream(pdf));
        return minio;
    }

    private OcrService mockOcr() {
        OcrService ocr = mock(OcrService.class);
        when(ocr.available()).thenReturn(false);
        return ocr;
    }

    private byte[] buildPdf() throws IOException {
        try (PDDocument pdf = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            String[] titles = {"Body Page One", "Body Page Two", "Body Page Three"};
            for (int i = 0; i < titles.length; i++) {
                PDPage page = new PDPage();
                pdf.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(pdf, page)) {
                    text(cs, 50, 770, "ACME Confidential", PDType1Font.HELVETICA, 9);
                    text(cs, 50, 730, titles[i], PDType1Font.HELVETICA_BOLD, 18);
                    text(cs, 50, 700, "This paragraph carries the body text of page " + (i + 1) + ".",
                            PDType1Font.HELVETICA, 10);
                    text(cs, 300, 30, String.valueOf(i + 1), PDType1Font.HELVETICA, 9);
                }
            }
            pdf.save(out);
            return out.toByteArray();
        }
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
