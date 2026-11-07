package com.rag.worker.pipeline;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 文档处理状态机（ISSUE RAG-011）。
 *
 * <pre>
 * UPLOADED → PARSING → CHUNKING → EMBEDDING → INDEXING → READY
 * 任意处理中状态可被用户停止（STOPPED）；异常由消费者置 FAILED。
 * STOPPED / FAILED / READY 可重新开始（PARSING 或已有切片的 EMBEDDING 断点续跑）。
 * </pre>
 */
public enum DocumentStage {
    UPLOADED, PARSING, CHUNKING, EMBEDDING, INDEXING, READY, FAILED, STOPPED;

    private static final Map<DocumentStage, Set<DocumentStage>> ALLOWED = Map.of(
            UPLOADED, EnumSet.of(PARSING, EMBEDDING),
            PARSING, EnumSet.of(PARSING, CHUNKING, FAILED, STOPPED),
            CHUNKING, EnumSet.of(CHUNKING, EMBEDDING, FAILED, STOPPED),
            EMBEDDING, EnumSet.of(EMBEDDING, INDEXING, FAILED, STOPPED),
            INDEXING, EnumSet.of(INDEXING, READY, FAILED, STOPPED),
            READY, EnumSet.of(PARSING, EMBEDDING, STOPPED),
            FAILED, EnumSet.of(PARSING, EMBEDDING),
            STOPPED, EnumSet.of(PARSING, EMBEDDING)
    );

    /** 未知历史状态返回 null，调用方按"不做约束"处理，避免旧数据阻断处理。 */
    public static DocumentStage of(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static boolean canTransit(DocumentStage from, DocumentStage to) {
        if (from == null || to == null) {
            return true;
        }
        Set<DocumentStage> targets = ALLOWED.get(from);
        return targets == null || targets.contains(to);
    }
}
