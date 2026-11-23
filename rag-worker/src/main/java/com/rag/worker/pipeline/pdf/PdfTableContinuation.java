package com.rag.worker.pipeline.pdf;

import com.rag.worker.pipeline.table.TableModel;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨页表格续接（ISSUE RAG-007）：上一页末尾的表格与下一页开头的表格列数一致，
 * 且续页重复表头或表格紧贴页顶时，把续页数据行并入上一页表格，避免表格被页边界切断。
 *
 * <p>只在版面位置严格满足条件时合并：上一页表格之后、下一页表格之前最多只允许
 * 少量短文本（页码/页眉页脚），因此正文中相隔较远的同构表格不会被误接。</p>
 */
public final class PdfTableContinuation {

    /** 表格前后允许出现的短文本长度上限（页码、页眉）。 */
    private static final int SHORT_TEXT_LIMIT = 20;

    /** 续页表格顶部落在页面该比例以内时视为接续（无重复表头的情况）。 */
    private static final double TOP_BAND_RATIO = 0.15;

    private PdfTableContinuation() {
    }

    /** current 为当前页处理后的块；absorbed=true 表示当前页只剩被吸收的表格/短文本，整页可跳过。 */
    public record Result(List<PdfLayoutExtractor.Block> current, boolean absorbed) {
    }

    public static Result connect(List<PdfLayoutExtractor.Block> previous, float previousPageHeight,
                                 List<PdfLayoutExtractor.Block> current, float currentPageHeight) {
        int prevIndex = lastTableIndex(previous);
        int currIndex = firstTableIndex(current);
        if (prevIndex < 0 || currIndex < 0) {
            return new Result(current, false);
        }
        PdfLayoutExtractor.TableBlock prev = (PdfLayoutExtractor.TableBlock) previous.get(prevIndex);
        PdfLayoutExtractor.TableBlock curr = (PdfLayoutExtractor.TableBlock) current.get(currIndex);
        TableModel main = prev.table();
        TableModel next = curr.table();
        if (main.isEmpty() || next.isEmpty() || main.columns() != next.columns()) {
            return new Result(current, false);
        }
        if (!isContinuation(main, next, curr.top(), currentPageHeight)) {
            return new Result(current, false);
        }

        List<List<TableModel.Cell>> rows = new ArrayList<>(main.grid());
        List<List<TableModel.Cell>> nextRows = new ArrayList<>(next.grid());
        // 续页重复表头时去重，只保留一份表头
        if (!rows.isEmpty() && !nextRows.isEmpty() && sameRow(rows.get(0), nextRows.get(0))) {
            nextRows.remove(0);
        }
        rows.addAll(nextRows);
        TableModel merged = new TableModel(main.title(), main.page(), rows);
        previous.set(prevIndex, new PdfLayoutExtractor.TableBlock(prev.top(), merged, prev.bbox()));

        List<PdfLayoutExtractor.Block> rest = new ArrayList<>(current);
        rest.remove(currIndex);
        boolean absorbed = rest.stream().allMatch(PdfTableContinuation::isIgnorable);
        return new Result(absorbed ? List.of() : rest, absorbed);
    }

    /** 上一页最后一个表格块下标；表格之后只允许短文本。 */
    private static int lastTableIndex(List<PdfLayoutExtractor.Block> blocks) {
        for (int i = blocks.size() - 1; i >= 0; i--) {
            PdfLayoutExtractor.Block block = blocks.get(i);
            if (block instanceof PdfLayoutExtractor.TableBlock) {
                return i;
            }
            if (!isShortText(block)) {
                return -1;
            }
        }
        return -1;
    }

    /** 当前页第一个表格块下标；表格之前只允许短文本。 */
    private static int firstTableIndex(List<PdfLayoutExtractor.Block> blocks) {
        for (int i = 0; i < blocks.size(); i++) {
            PdfLayoutExtractor.Block block = blocks.get(i);
            if (block instanceof PdfLayoutExtractor.TableBlock) {
                return i;
            }
            if (!isShortText(block)) {
                return -1;
            }
        }
        return -1;
    }

    /** 续页重复表头，或表格位于页面顶部区域时判定为接续。 */
    private static boolean isContinuation(TableModel main, TableModel next,
                                          double nextTop, float pageHeight) {
        if (!main.grid().isEmpty() && !next.grid().isEmpty()
                && sameRow(main.grid().get(0), next.grid().get(0))) {
            return true;
        }
        return pageHeight > 0 && nextTop <= pageHeight * TOP_BAND_RATIO;
    }

    private static boolean sameRow(List<TableModel.Cell> a, List<TableModel.Cell> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!normalize(a.get(i).text()).equals(normalize(b.get(i).text()))) {
                return false;
            }
        }
        return true;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").strip();
    }

    private static boolean isShortText(PdfLayoutExtractor.Block block) {
        return block instanceof PdfLayoutExtractor.TextBlock text
                && normalize(text.text()).length() <= SHORT_TEXT_LIMIT;
    }

    /** 合并后可忽略的块：空白文本或短文本（页码/页眉残留）。 */
    private static boolean isIgnorable(PdfLayoutExtractor.Block block) {
        if (block instanceof PdfLayoutExtractor.TextBlock text) {
            return normalize(text.text()).isEmpty()
                    || normalize(text.text()).length() <= SHORT_TEXT_LIMIT;
        }
        return false;
    }
}
