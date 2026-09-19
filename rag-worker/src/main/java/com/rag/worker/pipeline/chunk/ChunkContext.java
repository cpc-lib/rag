package com.rag.worker.pipeline.chunk;

import com.rag.worker.pipeline.ParseService;

import java.util.List;

/** 策略输入：文档归属信息 + 解析页 + 参数。objectKey 供 PDF_LAYOUT 重读原始文件。 */
public record ChunkContext(String tenantId,
                           long documentId,
                           String fileName,
                           String objectKey,
                           List<ParseService.RawPage> pages,
                           ChunkParams params) {
}
