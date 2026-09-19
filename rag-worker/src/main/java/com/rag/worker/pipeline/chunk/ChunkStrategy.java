package com.rag.worker.pipeline.chunk;

import java.util.List;

/**
 * 切片策略 SPI（指南 §42）。
 * 契约：plan 永远不返回 null；无内容返回空列表；无法执行（如缺配置）抛 IllegalStateException 并给出可读原因。
 */
public interface ChunkStrategy {

    ChunkMode mode();

    List<ChunkPlan> plan(ChunkContext ctx);
}
