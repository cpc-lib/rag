package com.rag.worker.infrastructure.search;

import java.util.List;

/**
 * 按文档列出索引中的 chunkId 结果（索引一致性巡检用）。
 *
 * @param ids          已读取到的 chunkId
 * @param truncated    是否因达到上限而可能不完整（不完整时禁止依据该结果删除数据）
 * @param indexMissing 目标索引/集合不存在
 * @param calls        本次读取产生的后端调用次数（含存在性探测与分页查询），用于外部调用预算
 */
public record ChunkIdPage(List<Long> ids, boolean truncated, boolean indexMissing, int calls) {
}
