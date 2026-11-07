package com.rag.worker.pipeline;

import com.rag.api.infrastructure.persistence.entity.DocumentEntity;
import com.rag.worker.infrastructure.llm.VisionClient;
import com.rag.worker.infrastructure.ocr.OcrService;
import com.rag.worker.infrastructure.storage.MinioStorage;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** RAG-008：Word / Excel / HTML 表格统一走 TableModel，覆盖多 Sheet、合并单元格与列对齐。 */
class ParseServiceTableTest {

    @Test
    void multiSheetExcelProducesOnePagePerSheetWithHeader() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet orders = workbook.createSheet("订单");
            XSSFRow header = orders.createRow(0);
            header.createCell(0).setCellValue("编号");
            header.createCell(1).setCellValue("金额");
            XSSFRow row = orders.createRow(1);
            row.createCell(0).setCellValue("A1");
            row.createCell(1).setCellValue(1000.5);

            XSSFSheet summary = workbook.createSheet("汇总");
            XSSFRow sumHeader = summary.createRow(0);
            sumHeader.createCell(0).setCellValue("地区");
            sumHeader.createCell(1).setCellValue("合计");
            XSSFRow sumRow = summary.createRow(1);
            sumRow.createCell(0).setCellValue("华东");
            sumRow.createCell(1).setCellValue(1000.5);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            bytes = out.toByteArray();
        }

        List<ParseService.RawPage> pages = ParseService.xlsxPages(bytes);

        assertEquals(2, pages.size());
        assertTrue(pages.get(0).text().startsWith("# 订单\n\n"), pages.get(0).text());
        assertTrue(pages.get(0).text().contains("| 编号 | 金额 |"), pages.get(0).text());
        assertTrue(pages.get(0).text().contains("| A1 | 1000.5 |"), pages.get(0).text());
        assertTrue(pages.get(1).text().startsWith("# 汇总\n\n"), pages.get(1).text());
    }

    @Test
    void mergedExcelHeaderExpandsAcrossColumns() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("表");
            XSSFRow header = sheet.createRow(0);
            header.createCell(0).setCellValue("合并表头");
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 1));
            XSSFRow row = sheet.createRow(1);
            row.createCell(0).setCellValue("a");
            row.createCell(1).setCellValue("1");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            bytes = out.toByteArray();
        }

        String text = ParseService.xlsxPages(bytes).get(0).text();

        assertTrue(text.contains("| 合并表头 | 合并表头 |"), text);
        assertTrue(text.contains("| a | 1 |"), text);
    }

    @Test
    void htmlTableHonorsColspan() throws Exception {
        String html = "<html><body><table>"
                + "<tr><th colspan=\"2\">合计</th></tr>"
                + "<tr><td>a</td><td>1</td></tr>"
                + "</table></body></html>";

        String text = parse("report.html", html).pages().get(0).text();

        assertTrue(text.contains("| 合计 | 合计 |"), text);
        assertTrue(text.contains("| a | 1 |"), text);
    }

    @Test
    void docxTableBecomesMarkdownTable() throws Exception {
        byte[] bytes;
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFTable table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("编号");
            table.getRow(0).getCell(1).setText("金额");
            table.getRow(1).getCell(0).setText("A1");
            table.getRow(1).getCell(1).setText("100");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            bytes = out.toByteArray();
        }

        String text = parse("table.docx", bytes).pages().get(0).text();

        assertTrue(text.contains("| 编号 | 金额 |"), text);
        assertTrue(text.contains("| A1 | 100 |"), text);
    }

    private ParseService.ParseOutcome parse(String fileName, String content) throws Exception {
        return parse(fileName, content.getBytes(StandardCharsets.UTF_8));
    }

    private ParseService.ParseOutcome parse(String fileName, byte[] bytes) throws Exception {
        MinioStorage minio = mock(MinioStorage.class);
        when(minio.download(anyString())).thenReturn(new ByteArrayInputStream(bytes));
        ParseService service = new ParseService(minio, mock(OcrService.class), mock(VisionClient.class));

        DocumentEntity doc = new DocumentEntity();
        doc.setFileName(fileName);
        doc.setObjectKey("tenant/kb/2026-01-01/test");
        return service.parse(doc, null);
    }
}
