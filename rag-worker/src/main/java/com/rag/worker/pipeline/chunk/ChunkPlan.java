package com.rag.worker.pipeline.chunk;

import java.util.List;

/**
 * 策略输出的单义契约：
 * parentChild=true  → 落 1 条 PARENT（parentContent）+ N 条 CHILD（children 关联 parent）
 * parentChild=false → children 各自落为独立 CHILD（无 parent）
 * 章节字段用于元数据；contentHash 仅 parent 行写入。
 */
public record ChunkPlan(boolean parentChild,
                        String sectionTitle,
                        String sectionPath,
                        String contentHash,
                        String parentContent,
                        List<PlannedChild> children) {

    public static ChunkPlan independent(String sectionTitle, String sectionPath, List<PlannedChild> children) {
        return new ChunkPlan(false, sectionTitle, sectionPath, null, null, children);
    }

    public static ChunkPlan parentChild(String sectionTitle, String sectionPath, String contentHash,
                                        String parentContent, List<PlannedChild> children) {
        return new ChunkPlan(true, sectionTitle, sectionPath, contentHash, parentContent, children);
    }
}
