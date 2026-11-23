package com.rag.worker.pipeline.chunk;

import com.rag.worker.pipeline.ParseService;

import java.util.List;

/** 策略输入：文档归属信息 + 解析页 + 参数。objectKey 为原始文件存储键，供需要重读原件的策略使用。 */
public record ChunkContext(String tenantId,
                           long documentId,
                           String fileName,
                           String objectKey,
                           List<ParseService.RawPage> pages,
                           ChunkParams params) {
}
