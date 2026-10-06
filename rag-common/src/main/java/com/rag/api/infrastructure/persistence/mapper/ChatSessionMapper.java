package com.rag.api.infrastructure.persistence.mapper;

import com.rag.api.infrastructure.persistence.entity.ChatSessionEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ChatSessionMapper {

    ChatSessionEntity selectById(@Param("id") Long id);

    List<ChatSessionEntity> selectByTenantIdAndUserId(@Param("tenantId") String tenantId, @Param("userId") Long userId);

    int insert(ChatSessionEntity entity);

    int touchUpdatedAt(@Param("id") Long id);

    int logicDeleteById(@Param("id") Long id);
}
