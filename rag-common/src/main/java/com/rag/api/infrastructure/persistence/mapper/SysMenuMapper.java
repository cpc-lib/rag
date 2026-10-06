package com.rag.api.infrastructure.persistence.mapper;

import com.rag.api.infrastructure.persistence.entity.SysMenuEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SysMenuMapper {

    SysMenuEntity selectByCode(@Param("code") String code);

    List<SysMenuEntity> selectList(@Param("status") Boolean status,
                                   @Param("platformVisible") Boolean platformVisible,
                                   @Param("adminVisible") Boolean adminVisible,
                                   @Param("endUser") Boolean endUser);

    List<SysMenuEntity> selectEndUserCatalog();
}
