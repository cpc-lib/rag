package com.rag.api.infrastructure.persistence.mapper;

import com.rag.api.infrastructure.persistence.entity.UploadSessionEntity;
import org.apache.ibatis.annotations.Param;

public interface UploadSessionMapper {

    UploadSessionEntity selectById(@Param("id") Long id);

    int insert(UploadSessionEntity entity);

    int updateById(UploadSessionEntity entity);
}
