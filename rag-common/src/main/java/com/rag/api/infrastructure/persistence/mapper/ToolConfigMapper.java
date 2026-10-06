package com.rag.api.infrastructure.persistence.mapper;

import com.rag.api.infrastructure.persistence.entity.ToolConfigEntity;
import org.apache.ibatis.annotations.Param;

public interface ToolConfigMapper {

    ToolConfigEntity selectByTenantId(@Param("tenantId") String tenantId);

    int insert(ToolConfigEntity entity);

    int updateById(ToolConfigEntity entity);
}
