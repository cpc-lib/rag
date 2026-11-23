package com.rag.api.infrastructure.persistence.mapper;

import com.rag.api.infrastructure.persistence.entity.IndexCleanupTaskEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface IndexCleanupTaskMapper {

    int insert(IndexCleanupTaskEntity entity);

    List<IndexCleanupTaskEntity> selectPending(@Param("limit") int limit);

    int markDone(@Param("id") Long id);

    /** 失败重试：attempts+1，达到 3 次置 FAILED，否则保持 PENDING。 */
    int markFailed(@Param("id") Long id, @Param("errorMsg") String errorMsg);

    int logicDeleteByDocumentId(@Param("documentId") Long documentId);

    int logicDeleteByKbId(@Param("kbId") Long kbId);

    /** 清理已结束（DONE/FAILED）且超过保留期的任务。 */
    int purgeFinishedBefore(@Param("days") int days);
}
