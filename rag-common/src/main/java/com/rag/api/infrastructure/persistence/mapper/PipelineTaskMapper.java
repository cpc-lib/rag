package com.rag.api.infrastructure.persistence.mapper;

import com.rag.api.infrastructure.persistence.entity.PipelineTaskEntity;
import org.apache.ibatis.annotations.Param;

public interface PipelineTaskMapper {

    PipelineTaskEntity selectById(@Param("id") Long id);

    int insert(PipelineTaskEntity entity);

    int updateById(PipelineTaskEntity entity);

    /** 停止处理时直接取消仍在排队（PENDING）的任务。 */
    int cancelPendingByDocumentId(@Param("documentId") Long documentId);

    int logicDeleteByDocumentId(@Param("documentId") Long documentId);

    int logicDeleteByKbId(@Param("kbId") Long kbId);
}
