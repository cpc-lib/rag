package com.rag.api.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rag.api.infrastructure.persistence.entity.DocumentEntity;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

public interface DocumentMapper {

    DocumentEntity selectById(@Param("id") Long id);

    int insert(DocumentEntity entity);

    int updateById(DocumentEntity entity);

    /** 秒传查询：同租户同知识库下相同 SHA-256 的文档（取最新一条）。 */
    DocumentEntity selectOneBySha256(@Param("tenantId") String tenantId,
                                     @Param("kbId") Long kbId,
                                     @Param("sha256") String sha256);

    /** 仅查停止标记（Worker 停止检查用）。 */
    DocumentEntity selectStopRequestedById(@Param("id") Long id);

    Page<DocumentEntity> selectPageByKbId(Page<DocumentEntity> page, @Param("kbId") Long kbId);

    List<DocumentEntity> selectByIds(@Param("ids") Collection<Long> ids);

    List<Long> selectIdsByKbId(@Param("kbId") Long kbId);

    /** 开始/继续处理：仅 UPLOADED/STOPPED/FAILED 状态可流转，清空错误并复位停止标记。 */
    int startProcessing(@Param("id") Long id,
                        @Param("status") String status,
                        @Param("progress") Integer progress);

    /** 重新解析：仅 READY/FAILED 状态可流转为 PARSING。 */
    int reparseById(@Param("id") Long id);

    int updateStatusById(@Param("id") Long id, @Param("status") String status);

    int updateStopRequestedById(@Param("id") Long id, @Param("stopRequested") Integer stopRequested);

    int logicDeleteById(@Param("id") Long id);

    int logicDeleteByKbId(@Param("kbId") Long kbId);
}
