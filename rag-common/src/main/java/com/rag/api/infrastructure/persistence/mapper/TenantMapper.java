package com.rag.api.infrastructure.persistence.mapper;

import com.rag.api.infrastructure.persistence.entity.TenantEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface TenantMapper {

    TenantEntity selectById(@Param("id") String id);

    TenantEntity selectByCode(@Param("code") String code);

    List<TenantEntity> selectAll();

    long countByCode(@Param("code") String code);

    int insert(TenantEntity entity);

    int updateById(TenantEntity entity);
}
