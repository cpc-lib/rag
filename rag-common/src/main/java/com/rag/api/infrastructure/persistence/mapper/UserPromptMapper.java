package com.rag.api.infrastructure.persistence.mapper;

import com.rag.api.infrastructure.persistence.entity.UserPromptEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface UserPromptMapper {

    List<UserPromptEntity> selectByUserIds(@Param("userIds") List<Long> userIds);

    List<UserPromptEntity> selectByUserId(@Param("userId") Long userId);

    long countByUserIdAndPromptId(@Param("userId") Long userId, @Param("promptId") Long promptId);

    int insert(UserPromptEntity entity);

    int deleteByUserId(@Param("userId") Long userId);

    int deleteByPromptId(@Param("promptId") Long promptId);

    int deleteByPromptIds(@Param("promptIds") List<Long> promptIds);

    int deleteByKbId(@Param("kbId") Long kbId);
}
