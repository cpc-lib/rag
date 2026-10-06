package com.rag.api.infrastructure.persistence.mapper;

import com.rag.api.infrastructure.persistence.entity.SysUserEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SysUserMapper {

    SysUserEntity selectById(@Param("id") Long id);

    List<SysUserEntity> selectList(@Param("tenantId") String tenantId,
                                   @Param("username") String username,
                                   @Param("status") Integer status,
                                   @Param("userType") Integer userType);

    long countByTenantIdAndUsername(@Param("tenantId") String tenantId, @Param("username") String username);

    long countAll();

    SysUserEntity selectOneByTenantIdAndUserType(@Param("tenantId") String tenantId, @Param("userType") Integer userType);

    int insert(SysUserEntity entity);

    int updateById(SysUserEntity entity);
}
