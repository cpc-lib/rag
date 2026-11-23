package com.rag.worker.pipeline;

import java.util.Locale;

/**
 * 版面坐标（单位 pt）：x/y 为左上角，y 自页面向下，width/height 为宽高（ISSUE RAG-007）。
 * 用于把 PDF 解析出的文本/表格/图片块位置传递到切片元数据，支持引用溯源与版面预览。
 */
public record BBox(float x, float y, float width, float height) {

    public static BBox of(float x1, float top, float x2, float bottom) {
        return new BBox(x1, top, Math.max(0f, x2 - x1), Math.max(0f, bottom - top));
    }

    /** 合并两个区域为最小外接矩形；任一为空时返回另一个。 */
    public static BBox union(BBox a, BBox b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        float x1 = Math.min(a.x, b.x);
        float y1 = Math.min(a.y, b.y);
        float x2 = Math.max(a.x + a.width, b.x + b.width);
        float y2 = Math.max(a.y + a.height, b.y + b.height);
        return of(x1, y1, x2, y2);
    }

    /** 入库格式：x,y,width,height，保留 1 位小数。 */
    public String toDb() {
        return String.format(Locale.ROOT, "%.1f,%.1f,%.1f,%.1f", x, y, width, height);
    }
}
