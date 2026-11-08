package com.rag.worker.pipeline.pdf;

import com.rag.worker.pipeline.table.TableModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RAG-007：跨页表格续接判定与合并。 */
class PdfTableContinuationTest {

    private static final float PAGE_HEIGHT = 842;

    @Test
    void duplicateHeaderIsMergedAndPageAbsorbed() {
        List<PdfLayoutExtractor.Block> previous = new ArrayList<>(List.of(
                new PdfLayoutExtractor.TextBlock(100, "Quarterly report"),
                new PdfLayoutExtractor.TableBlock(600, table(List.of("Name", "Age"), List.of("Alice", "30")))));
        List<PdfLayoutExtractor.Block> current = new ArrayList<>(List.of(
                new PdfLayoutExtractor.TextBlock(20, "2"),
                new PdfLayoutExtractor.TableBlock(60, table(List.of("Name", "Age"), List.of("Bob", "25")))));

        PdfTableContinuation.Result result = PdfTableContinuation.connect(
                previous, PAGE_HEIGHT, current, PAGE_HEIGHT);

        assertTrue(result.absorbed(), "整页只剩接续表格与页码，应被吸收");
        assertTrue(result.current().isEmpty());
        PdfLayoutExtractor.TableBlock merged = (PdfLayoutExtractor.TableBlock) previous.get(1);
        assertEquals(3, merged.table().grid().size(), "表头 + 两页各一行数据");
        assertEquals("Bob", merged.table().expand().get(2).get(0));
    }

    @Test
    void headerlessTableAtPageTopIsMerged() {
        List<PdfLayoutExtractor.Block> previous = new ArrayList<>(List.of(
                new PdfLayoutExtractor.TableBlock(700, table(List.of("Name", "Age"), List.of("Alice", "30")))));
        List<PdfLayoutExtractor.Block> current = new ArrayList<>(List.of(
                new PdfLayoutExtractor.TableBlock(60, table(List.of("Bob", "25")))));

        PdfTableContinuation.Result result = PdfTableContinuation.connect(
                previous, PAGE_HEIGHT, current, PAGE_HEIGHT);

        assertTrue(result.absorbed());
        PdfLayoutExtractor.TableBlock merged = (PdfLayoutExtractor.TableBlock) previous.get(0);
        assertEquals(3, merged.table().grid().size());
    }

    @Test
    void headerlessTableAwayFromTopIsNotMerged() {
        List<PdfLayoutExtractor.Block> previous = new ArrayList<>(List.of(
                new PdfLayoutExtractor.TableBlock(700, table(List.of("Name", "Age"), List.of("Alice", "30")))));
        List<PdfLayoutExtractor.Block> current = new ArrayList<>(List.of(
                new PdfLayoutExtractor.TableBlock(400, table(List.of("Bob", "25")))));

        PdfTableContinuation.Result result = PdfTableContinuation.connect(
                previous, PAGE_HEIGHT, current, PAGE_HEIGHT);

        assertFalse(result.absorbed());
        assertEquals(1, result.current().size(), "未接续时当前页保持原样");
        assertEquals(2, ((PdfLayoutExtractor.TableBlock) previous.get(0)).table().grid().size());
    }

    @Test
    void differentColumnCountIsNotMerged() {
        List<PdfLayoutExtractor.Block> previous = new ArrayList<>(List.of(
                new PdfLayoutExtractor.TableBlock(700, table(List.of("Name", "Age"), List.of("Alice", "30")))));
        List<PdfLayoutExtractor.Block> current = new ArrayList<>(List.of(
                new PdfLayoutExtractor.TableBlock(60,
                        table(List.of("Name", "Age", "City"), List.of("Bob", "25", "London")))));

        PdfTableContinuation.Result result = PdfTableContinuation.connect(
                previous, PAGE_HEIGHT, current, PAGE_HEIGHT);

        assertFalse(result.absorbed());
        assertEquals(2, ((PdfLayoutExtractor.TableBlock) previous.get(0)).table().grid().size());
    }

    @Test
    void bodyTextAfterPreviousTableBlocksMerge() {
        List<PdfLayoutExtractor.Block> previous = new ArrayList<>(List.of(
                new PdfLayoutExtractor.TableBlock(600, table(List.of("Name", "Age"), List.of("Alice", "30"))),
                new PdfLayoutExtractor.TextBlock(700, "This paragraph follows the table.")));
        List<PdfLayoutExtractor.Block> current = new ArrayList<>(List.of(
                new PdfLayoutExtractor.TableBlock(60, table(List.of("Name", "Age"), List.of("Bob", "25")))));

        PdfTableContinuation.Result result = PdfTableContinuation.connect(
                previous, PAGE_HEIGHT, current, PAGE_HEIGHT);

        assertFalse(result.absorbed());
        assertEquals(2, ((PdfLayoutExtractor.TableBlock) previous.get(0)).table().grid().size());
    }

    @Test
    void bodyTextBeforeCurrentTableBlocksMerge() {
        List<PdfLayoutExtractor.Block> previous = new ArrayList<>(List.of(
                new PdfLayoutExtractor.TableBlock(700, table(List.of("Name", "Age"), List.of("Alice", "30")))));
        List<PdfLayoutExtractor.Block> current = new ArrayList<>(List.of(
                new PdfLayoutExtractor.TextBlock(30, "Continuation of the report body text."),
                new PdfLayoutExtractor.TableBlock(120, table(List.of("Name", "Age"), List.of("Bob", "25")))));

        PdfTableContinuation.Result result = PdfTableContinuation.connect(
                previous, PAGE_HEIGHT, current, PAGE_HEIGHT);

        assertFalse(result.absorbed());
        assertEquals(2, result.current().size());
    }

    @Test
    void currentPageKeepsBodyTextAfterMerge() {
        List<PdfLayoutExtractor.Block> previous = new ArrayList<>(List.of(
                new PdfLayoutExtractor.TableBlock(600, table(List.of("Name", "Age"), List.of("Alice", "30")))));
        List<PdfLayoutExtractor.Block> current = new ArrayList<>(List.of(
                new PdfLayoutExtractor.TableBlock(60, table(List.of("Name", "Age"), List.of("Bob", "25"))),
                new PdfLayoutExtractor.TextBlock(300, "Summary paragraph after the continued table.")));

        PdfTableContinuation.Result result = PdfTableContinuation.connect(
                previous, PAGE_HEIGHT, current, PAGE_HEIGHT);

        assertFalse(result.absorbed(), "表格后还有正文，当前页不能整页丢弃");
        assertEquals(1, result.current().size());
        assertInstanceOf(PdfLayoutExtractor.TextBlock.class, result.current().get(0));
        assertEquals(3, ((PdfLayoutExtractor.TableBlock) previous.get(0)).table().grid().size());
    }

    private TableModel table(List<String>... rows) {
        List<List<TableModel.Cell>> grid = new ArrayList<>();
        for (List<String> row : rows) {
            List<TableModel.Cell> cells = new ArrayList<>();
            for (String value : row) {
                cells.add(TableModel.Cell.of(value));
            }
            grid.add(cells);
        }
        return new TableModel(null, 0, grid);
    }
}
