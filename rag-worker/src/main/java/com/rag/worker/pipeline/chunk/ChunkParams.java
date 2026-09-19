package com.rag.worker.pipeline.chunk;

import java.util.List;

/** 切片参数（来自知识库配置，全部 token 口径）。 */
public record ChunkParams(int parentSize,
                          int childSize,
                          int overlap,
                          List<String> separators) {
}
