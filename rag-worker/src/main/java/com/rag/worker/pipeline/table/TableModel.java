package com.rag.worker.pipeline.table;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一表格内部模型（ISSUE RAG-008）：各解析器只负责把源表格还原为
 * "单元格网格 + 合并跨度"，渲染统一走 {@link #toMarkdown()}。
 *
 * <p>原始网格保留表头（第一行）、行列关系与合并信息（colspan/rowspan）；
 * Markdown 无法表达合并单元格，渲染时把合并值展开到其覆盖的每个格子，
 * 保证列数与后续行严格对齐，避免"合并单元格导致数据行列错位"。</p>
 *
 * @param title 表格来源标题（如 Excel Sheet 名），可为 null
 * @param page  源位置（页/Sheet 序号）
 * @param grid  行优先的单元格网格
 */
public record TableModel(String title, int page, List<List<Cell>> grid) {

    /** 单元格：文本 + 向下/向右合并跨度（至少为 1）。 */
    public record Cell(String text, int colspan, int rowspan) {

        public static Cell of(String text) {
            return new Cell(text, 1, 1);
        }

        public Cell {
            text = text == null ? "" : text;
            colspan = Math.max(1, colspan);
            rowspan = Math.max(1, rowspan);
        }
    }

    public TableModel {
        List<List<Cell>> copy = new ArrayList<>();
        if (grid != null) {
            for (List<Cell> row : grid) {
                if (row == null) {
                    continue;
                }
                List<Cell> cells = new ArrayList<>(row.size());
                for (Cell cell : row) {
                    cells.add(cell == null ? Cell.of("") : cell);
                }
                copy.add(List.copyOf(cells));
            }
        }
        grid = List.copyOf(copy);
    }

    public boolean isEmpty() {
        return grid.isEmpty() || columns() == 0;
    }

    /** 展开合并跨度后的列数。 */
    public int columns() {
        int max = 0;
        for (List<Cell> row : grid) {
            int cols = 0;
            for (Cell cell : row) {
                cols += cell.colspan();
            }
            max = Math.max(max, cols);
        }
        return max;
    }

    /**
     * 展开为矩形文本网格：colspan/rowspan 覆盖的格子都写入同一文本，
     * 后续单元格自动跳过已被占用位置，避免列偏移。
     */
    public List<List<String>> expand() {
        int rows = grid.size();
        int cols = columns();
        List<List<String>> out = new ArrayList<>(rows);
        if (rows == 0 || cols == 0) {
            return out;
        }
        String[][] cells = new String[rows][cols];
        boolean[][] filled = new boolean[rows][cols];
        for (int r = 0; r < rows; r++) {
            int c = 0;
            for (Cell cell : grid.get(r)) {
                while (c < cols && filled[r][c]) {
                    c++;
                }
                if (c >= cols) {
                    break;
                }
                String text = escape(cell.text());
                for (int dr = 0; dr < cell.rowspan() && r + dr < rows; dr++) {
                    for (int dc = 0; dc < cell.colspan() && c + dc < cols; dc++) {
                        cells[r + dr][c + dc] = text;
                        filled[r + dr][c + dc] = true;
                    }
                }
                c += cell.colspan();
            }
        }
        for (int r = 0; r < rows; r++) {
            List<String> row = new ArrayList<>(cols);
            for (int c = 0; c < cols; c++) {
                row.add(cells[r][c] == null ? "" : cells[r][c]);
            }
            out.add(List.copyOf(row));
        }
        return out;
    }

    /** 渲染为 Markdown 表格（第一行作表头）；空表返回空串。 */
    public String toMarkdown() {
        List<List<String>> rows = expand();
        if (rows.isEmpty()) {
            return "";
        }
        int cols = rows.get(0).size();
        StringBuilder sb = new StringBuilder();
        sb.append(mdRow(rows.get(0), cols)).append('\n');
        sb.append('|');
        for (int i = 0; i < cols; i++) {
            sb.append(" --- |");
        }
        sb.append('\n');
        for (int r = 1; r < rows.size(); r++) {
            sb.append(mdRow(rows.get(r), cols)).append('\n');
        }
        return sb.toString();
    }

    /** 单元格文本归一化：压缩换行/连续空白，转义 Markdown 表格分隔符。 */
    public static String escape(String cell) {
        if (cell == null) {
            return "";
        }
        return cell.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static String mdRow(List<String> cells, int cols) {
        StringBuilder sb = new StringBuilder("|");
        for (int c = 0; c < cols; c++) {
            sb.append(' ').append(c < cells.size() ? cells.get(c) : "").append(" |");
        }
        return sb.toString();
    }
}
