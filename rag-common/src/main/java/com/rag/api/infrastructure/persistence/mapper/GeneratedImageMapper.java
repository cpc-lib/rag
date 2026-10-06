package com.rag.api.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rag.api.infrastructure.persistence.entity.GeneratedImageEntity;
import org.apache.ibatis.annotations.Param;

public interface GeneratedImageMapper {

    GeneratedImageEntity selectById(@Param("id") Long id);

    int insert(GeneratedImageEntity entity);

    Page<GeneratedImageEntity> selectPageByTenantUser(Page<GeneratedImageEntity> page,
                                                      @Param("tenantId") String tenantId,
                                                      @Param("userId") Long userId,
                                                      @Param("keyword") String keyword);

    int updateFileSizeById(@Param("id") Long id,
                           @Param("fileSize") Long fileSize);

    int updateSha256ById(@Param("id") long id,
                         @Param("sha256") String sha256);

    int logicDeleteById(@Param("id") Long id);
}
