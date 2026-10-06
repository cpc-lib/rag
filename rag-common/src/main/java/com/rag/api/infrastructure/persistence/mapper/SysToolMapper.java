package com.rag.api.infrastructure.persistence.mapper;

import com.rag.api.infrastructure.persistence.entity.SysToolEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SysToolMapper {

    List<SysToolEntity> selectListByStatus(@Param("status") Boolean status);
}
