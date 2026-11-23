package com.rag.worker.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** RAG-007：child 文本 → 版面块区间 → bbox / sourceId 定位。 */
class ChunkMetadataResolverTest {

    private static final String TEXT = "第一段内容。\n\n第二段内容。";

    private ParseService.RawPage page() {
        return new ParseService.RawPage(0, TEXT, List.of(
                new ParseService.BlockSpan(0, 6, "p1-b1", BBox.of(10, 100, 210, 120)),
                new ParseService.BlockSpan(8, 14, "p1-b2", BBox.of(10, 200, 210, 220))));
    }

    @Test
    void singleBlockMapsToItsBBox() {
        ChunkMetadataResolver.Metadata meta =
                ChunkMetadataResolver.resolve(page(), "第二段  内容。");

        assertEquals("10.0,200.0,200.0,20.0", meta.bbox());
        assertEquals("p1-b2", meta.sourceId());
    }

    @Test
    void crossBlockChildUnionsBBox() {
        ChunkMetadataResolver.Metadata meta =
                ChunkMetadataResolver.resolve(page(), "第一段内容。第二段内容。");

        assertEquals("10.0,100.0,200.0,120.0", meta.bbox(), "应取两个块的最小外接矩形");
        assertEquals("p1-b1~p1-b2", meta.sourceId());
    }

    @Test
    void unmatchedContentReturnsEmpty() {
        ChunkMetadataResolver.Metadata meta =
                ChunkMetadataResolver.resolve(page(), "页面上不存在的内容");

        assertNull(meta.bbox());
        assertNull(meta.sourceId());
    }

    @Test
    void pageWithoutSpansReturnsEmpty() {
        ChunkMetadataResolver.Metadata meta = ChunkMetadataResolver.resolve(
                new ParseService.RawPage(0, TEXT), "第一段内容。");

        assertNull(meta.bbox());
        assertNull(meta.sourceId());
    }
}
