package com.rag.api.infrastructure.persistence.mapper;

import com.rag.api.infrastructure.persistence.entity.PipelineTaskEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface PipelineTaskMapper {

    PipelineTaskEntity selectById(@Param("id") Long id);

    /**
     * 条件领取：仅当任务仍为 PENDING 时原子置为 RUNNING。
     * 返回 1 表示领取成功；0 表示已被其他 Worker 领取或状态已变化（重复消息安全跳过）。
     */
    int claimPending(@Param("id") Long id);

    /** 扫描长时间未更新、可能因 Worker 崩溃而卡住的 PENDING/RUNNING 任务。 */
    List<PipelineTaskEntity> selectStaleTasks(@Param("minutes") int minutes);

    int insert(PipelineTaskEntity entity);

    int updateById(PipelineTaskEntity entity);

    /** 停止处理时直接取消仍在排队（PENDING）的任务。 */
    int cancelPendingByDocumentId(@Param("documentId") Long documentId);

    int logicDeleteByDocumentId(@Param("documentId") Long documentId);

    int logicDeleteByKbId(@Param("kbId") Long kbId);
}
