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
}
