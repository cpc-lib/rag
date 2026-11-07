package com.rag.worker.pipeline;

import com.rag.api.infrastructure.persistence.entity.DocumentEntity;
import com.rag.worker.infrastructure.llm.VisionClient;
import com.rag.worker.infrastructure.ocr.OcrService;
import com.rag.worker.infrastructure.storage.MinioStorage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** RAG-006 边界用例：格式路由、BOM、空文件、不支持扩展名。 */
class ParseServiceFormatTest {

    @Test
    void csvIsConvertedToMarkdownTableWithQuotedComma() throws Exception {
        String csv = "\uFEFFname,amount\n\"张,三\",100\n";
        ParseService.ParseOutcome outcome = parse("data.csv", csv);

        String text = outcome.pages().get(0).text();
        assertTrue(text.contains("| name | amount |"), text);
        assertTrue(text.contains("| 张,三 | 100 |"), text);
    }

    @Test
    void jsonAndCodeFilesAreReadAsPlainText() throws Exception {
        String json = "{\"key\":\"值\"}";
        assertEquals(json, parse("config.json", json).pages().get(0).text());

        String code = "public class A { }";
        assertEquals(code, parse("A.java", code).pages().get(0).text());
    }

    @Test
    void emptyFileDoesNotCrashParser() throws Exception {
        ParseService.ParseOutcome outcome = parse("empty.txt", "");
        assertEquals(1, outcome.pages().size());
        assertEquals("", outcome.pages().get(0).text());
    }

    @Test
    void unsupportedExtensionFailsWithoutRetry() {
        assertThrows(NonRetryableException.class, () -> parse("slides.pptx", "binary"));
        assertThrows(NonRetryableException.class, () -> parse("noext", "binary"));
    }

    private ParseService.ParseOutcome parse(String fileName, String content) throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        MinioStorage minio = mock(MinioStorage.class);
        when(minio.download(anyString())).thenReturn(new ByteArrayInputStream(bytes));
        ParseService service = new ParseService(minio, mock(OcrService.class), mock(VisionClient.class));

        DocumentEntity doc = new DocumentEntity();
        doc.setFileName(fileName);
        doc.setObjectKey("tenant/kb/2026-01-01/test");
        return service.parse(doc, null);
    }
}
