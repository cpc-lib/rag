package com.rag.api.infrastructure.persistence.mapper;

import com.rag.api.infrastructure.persistence.entity.SubtitleEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SubtitleMapper {

    SubtitleEntity selectById(@Param("id") Long id);

    int insert(SubtitleEntity entity);

    int updateById(SubtitleEntity entity);

    List<SubtitleEntity> selectListByTenantUser(@Param("tenantId") String tenantId,
                                                @Param("userId") Long userId);

    int logicDeleteById(@Param("id") Long id);
}
