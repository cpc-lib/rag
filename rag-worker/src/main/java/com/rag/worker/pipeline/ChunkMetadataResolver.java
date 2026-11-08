package com.rag.worker.pipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * 切片元数据定位（ISSUE RAG-007）：把 child 文本映射回解析页的版面块区间，
 * 得到该切片在页面上的外接 bbox 与来源块标识。
 *
 * <p>切片内容由解析文本经切片策略重组，空白/换行可能与页文本不一致，
 * 因此双方都先去掉空白再做子串定位；定位失败（如表格切片重复表头后不再连续）
 * 只返回空 bbox，不影响入库与检索。</p>
 */
public final class ChunkMetadataResolver {

    /** 用于定位的内容前缀长度阶梯：长前缀更精确，失败后逐步放宽。 */
    private static final int[] PREFIX_STEPS = {40, 20, 10};

    private ChunkMetadataResolver() {
    }

    /** 定位结果：bbox 为 x,y,w,h 字符串（可为 null），sourceId 形如 p1-b2 或 p1-b2~p1-b5，blockType 为主要语义类别。 */
    public record Metadata(String bbox, String sourceId, String blockType) {

        public static final Metadata EMPTY = new Metadata(null, null, null);
    }

    public static Metadata resolve(ParseService.RawPage page, String content) {
        if (page == null || page.spans().isEmpty() || content == null || content.isBlank()) {
            return Metadata.EMPTY;
        }
        Norm pageNorm = normalize(page.text());
        Norm contentNorm = normalize(content);
        if (contentNorm.text.length() < 4 || pageNorm.text.isEmpty()) {
            return Metadata.EMPTY;
        }
        int pos = locate(pageNorm.text, contentNorm.text);
        if (pos < 0) {
            return Metadata.EMPTY;
        }
        int normEnd = Math.min(pageNorm.text.length(), pos + contentNorm.text.length());
        int start = pageNorm.orig[pos];
        int end = pageNorm.orig[normEnd - 1] + 1;

        List<String> ids = new ArrayList<>(2);
        BBox bbox = null;
        // 按块区间与 child 内容的重叠长度累计各类别权重，取占比最高的语义类别
        java.util.Map<com.rag.worker.pipeline.pdf.PdfLayoutExtractor.BlockKind, Integer> kindWeights =
                new java.util.EnumMap<>(com.rag.worker.pipeline.pdf.PdfLayoutExtractor.BlockKind.class);
        for (ParseService.BlockSpan span : page.spans()) {
            if (span.end() <= start || span.start() >= end) {
                continue;
            }
            bbox = BBox.union(bbox, span.bbox());
            if (ids.isEmpty() || !ids.get(ids.size() - 1).equals(span.blockId())) {
                ids.add(span.blockId());
            }
            int overlap = Math.min(span.end(), end) - Math.max(span.start(), start);
            if (overlap > 0 && span.kind() != null) {
                kindWeights.merge(span.kind(), overlap, Integer::sum);
            }
        }
        if (ids.isEmpty()) {
            return Metadata.EMPTY;
        }
        String sourceId = ids.size() == 1 ? ids.get(0) : ids.get(0) + "~" + ids.get(ids.size() - 1);
        String blockType = kindWeights.entrySet().stream()
                .max(java.util.Map.Entry.comparingByValue())
                .map(e -> e.getKey().name())
                .orElse(null);
        return new Metadata(bbox == null ? null : bbox.toDb(), sourceId, blockType);
    }

    /** 以内容前缀在页文本中定位起点，长前缀优先，降低短文本误匹配概率。 */
    private static int locate(String pageText, String contentText) {
        for (int step : PREFIX_STEPS) {
            if (contentText.length() < step) {
                continue;
            }
            int pos = pageText.indexOf(contentText.substring(0, step));
            if (pos >= 0) {
                return pos;
            }
        }
        // 内容短于最小阶梯时用全文匹配
        return contentText.length() < PREFIX_STEPS[PREFIX_STEPS.length - 1]
                ? pageText.indexOf(contentText) : -1;
    }

    /** 去空白后的文本及每个字符在原串中的下标（用于把匹配位置映射回原区间）。 */
    private record Norm(String text, int[] orig) {
    }

    private static Norm normalize(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        int[] orig = new int[raw.length()];
        int n = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c) || c == '\u3000') {
                continue;
            }
            sb.append(c);
            orig[n++] = i;
        }
        int[] trimmed = new int[n];
        System.arraycopy(orig, 0, trimmed, 0, n);
        return new Norm(sb.toString(), trimmed);
    }
}
