package com.rag.worker.pipeline.table;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RAG-008：统一表格模型的行列对齐与合并单元格展开。 */
class TableModelTest {

    @Test
    void colspanIsExpandedWithoutShiftingNextRow() {
        List<List<TableModel.Cell>> grid = List.of(
                List.of(new TableModel.Cell("合计", 2, 1)),
                List.of(TableModel.Cell.of("a"), TableModel.Cell.of("1")));

        String markdown = new TableModel(null, 0, grid).toMarkdown();

        assertEquals("| 合计 | 合计 |\n| --- | --- |\n| a | 1 |\n", markdown);
    }

    @Test
    void rowspanKeepsFollowingCellsInCorrectColumns() {
        List<List<TableModel.Cell>> grid = List.of(
                List.of(new TableModel.Cell("地区", 1, 2), TableModel.Cell.of("产品"), TableModel.Cell.of("金额")),
                List.of(TableModel.Cell.of("手机"), TableModel.Cell.of("100")),
                List.of(TableModel.Cell.of("华南"), TableModel.Cell.of("电脑"), TableModel.Cell.of("200")));

        String markdown = new TableModel(null, 0, grid).toMarkdown();

        assertEquals("| 地区 | 产品 | 金额 |\n"
                + "| --- | --- | --- |\n"
                + "| 地区 | 手机 | 100 |\n"
                + "| 华南 | 电脑 | 200 |\n", markdown);
    }

    @Test
    void cellTextIsEscapedAndWhitespaceCollapsed() {
        List<List<TableModel.Cell>> grid = List.of(
                List.of(TableModel.Cell.of("a|b")),
                List.of(TableModel.Cell.of("第一行\n第二行")));

        String markdown = new TableModel(null, 0, grid).toMarkdown();

        assertTrue(markdown.contains("| a\\|b |"), markdown);
        assertTrue(markdown.contains("| 第一行 第二行 |"), markdown);
    }

    @Test
    void emptyTableRendersNothing() {
        assertEquals("", new TableModel(null, 0, List.of()).toMarkdown());
        assertEquals("", new TableModel(null, 0, List.of(List.of())).toMarkdown());
    }
}
