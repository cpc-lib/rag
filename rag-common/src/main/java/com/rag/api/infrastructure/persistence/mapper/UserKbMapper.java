package com.rag.api.infrastructure.persistence.mapper;

import com.rag.api.infrastructure.persistence.entity.UserKbEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface UserKbMapper {

    List<UserKbEntity> selectByUserIds(@Param("userIds") List<Long> userIds);

    List<UserKbEntity> selectByUserId(@Param("userId") Long userId);

    long countByUserIdAndKbId(@Param("userId") Long userId, @Param("kbId") Long kbId);

    int insert(UserKbEntity entity);

    int deleteByUserId(@Param("userId") Long userId);

    int deleteByKbId(@Param("kbId") Long kbId);
}
