package com.rag.api.infrastructure.persistence.mapper;

import com.rag.api.infrastructure.persistence.entity.TenantMenuEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface TenantMenuMapper {

    List<TenantMenuEntity> selectByTenantId(@Param("tenantId") String tenantId);

    TenantMenuEntity selectOneByTenantIdAndMenuCode(@Param("tenantId") String tenantId, @Param("menuCode") String menuCode);

    int insert(TenantMenuEntity entity);

    int updateById(TenantMenuEntity entity);
}
