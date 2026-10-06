package com.rag.api.infrastructure.persistence.mapper;

import com.rag.api.infrastructure.persistence.entity.ChatMessageEntity;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ChatMessageMapper {

    List<ChatMessageEntity> selectByTenantIdAndSessionId(@Param("tenantId") String tenantId, @Param("sessionId") Long sessionId);

    Long sumTokenUsageByTenantIdSince(@Param("tenantId") String tenantId, @Param("since") LocalDateTime since);

    int insert(ChatMessageEntity entity);

    int logicDeleteByTenantIdAndSessionId(@Param("tenantId") String tenantId, @Param("sessionId") Long sessionId);
}
