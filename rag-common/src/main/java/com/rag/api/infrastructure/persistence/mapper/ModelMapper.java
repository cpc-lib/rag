package com.rag.api.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rag.api.infrastructure.persistence.entity.ModelEntity;
import org.apache.ibatis.annotations.Param;

public interface ModelMapper {

    ModelEntity selectById(@Param("id") Long id);

    ModelEntity selectEnabledByTenantIdAndType(@Param("tenantId") String tenantId, @Param("type") String type);

    Page<ModelEntity> selectPage(Page<ModelEntity> page, @Param("tenantId") String tenantId, @Param("type") String type);

    int insert(ModelEntity entity);

    int updateById(ModelEntity entity);
}
