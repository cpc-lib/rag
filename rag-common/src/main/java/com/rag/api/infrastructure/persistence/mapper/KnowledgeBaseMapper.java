package com.rag.api.infrastructure.persistence.mapper;

import com.rag.api.infrastructure.persistence.entity.KnowledgeBaseEntity;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

public interface KnowledgeBaseMapper {

    KnowledgeBaseEntity selectById(@Param("id") Long id);

    int insert(KnowledgeBaseEntity entity);

    int updateById(KnowledgeBaseEntity entity);

    List<KnowledgeBaseEntity> selectByIds(@Param("ids") Collection<Long> ids);

    List<KnowledgeBaseEntity> selectByTenantId(@Param("tenantId") String tenantId);

    int logicDeleteById(@Param("id") Long id);

    /** 条件置 BUILDING：仅当当前非 BUILDING 时成功，返回 1 表示本次重建被受理。 */
    int markIndexBuilding(@Param("id") Long id, @Param("targetVersion") Integer targetVersion);

    /** 重建成功：切换当前可读版本并清除目标版本。 */
    int finishIndexRebuild(@Param("id") Long id, @Param("version") Integer version);

    /** 重建终止：复位状态，目标版本置空（已切换的别名不受影响）。 */
    int resetIndexState(@Param("id") Long id);
}
