package com.rag.worker.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentStageTest {

    @Test
    void mainPipelineTransitionsAreLegal() {
        assertTrue(DocumentStage.canTransit(DocumentStage.UPLOADED, DocumentStage.PARSING));
        assertTrue(DocumentStage.canTransit(DocumentStage.PARSING, DocumentStage.CHUNKING));
        assertTrue(DocumentStage.canTransit(DocumentStage.CHUNKING, DocumentStage.EMBEDDING));
        assertTrue(DocumentStage.canTransit(DocumentStage.EMBEDDING, DocumentStage.INDEXING));
        assertTrue(DocumentStage.canTransit(DocumentStage.INDEXING, DocumentStage.READY));
        // 重新解析 / 断点续跑
        assertTrue(DocumentStage.canTransit(DocumentStage.READY, DocumentStage.PARSING));
        assertTrue(DocumentStage.canTransit(DocumentStage.STOPPED, DocumentStage.EMBEDDING));
        // 进度上报允许同状态重复
        assertTrue(DocumentStage.canTransit(DocumentStage.EMBEDDING, DocumentStage.EMBEDDING));
        // 僵尸任务恢复：中断在索引阶段的文档允许回退重跑
        assertTrue(DocumentStage.canTransit(DocumentStage.INDEXING, DocumentStage.PARSING));
        assertTrue(DocumentStage.canTransit(DocumentStage.INDEXING, DocumentStage.EMBEDDING));
    }

    @Test
    void illegalTransitionsAreRejected() {
        assertFalse(DocumentStage.canTransit(DocumentStage.READY, DocumentStage.CHUNKING));
        assertFalse(DocumentStage.canTransit(DocumentStage.INDEXING, DocumentStage.CHUNKING));
        assertFalse(DocumentStage.canTransit(DocumentStage.UPLOADED, DocumentStage.READY));
    }

    @Test
    void unknownStatusDoesNotBlockOldData() {
        assertNull(DocumentStage.of("LEGACY_STATUS"));
        assertNull(DocumentStage.of(null));
        assertTrue(DocumentStage.canTransit(null, DocumentStage.PARSING));
    }
}
