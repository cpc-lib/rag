package com.rag.api.infrastructure.persistence.mapper;

import com.rag.api.infrastructure.persistence.entity.TranslateLangEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface TranslateLangMapper {

    TranslateLangEntity selectById(@Param("id") Long id);

    int insert(TranslateLangEntity entity);

    List<TranslateLangEntity> selectListByTenantId(@Param("tenantId") String tenantId);

    Long countByTenantIdAndName(@Param("tenantId") String tenantId,
                                @Param("name") String name);

    Long countByTenantId(@Param("tenantId") String tenantId);

    int logicDeleteById(@Param("id") Long id);
}
