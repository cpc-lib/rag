package com.rag.api.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rag.api.common.BizException;
import com.rag.api.common.ErrorCode;
import com.rag.api.infrastructure.persistence.entity.ChatMessageEntity;
import com.rag.api.infrastructure.persistence.entity.DocumentEntity;
import com.rag.api.infrastructure.persistence.entity.TenantEntity;
import com.rag.api.infrastructure.persistence.mapper.ChatMessageMapper;
import com.rag.api.infrastructure.persistence.mapper.DocumentMapper;
import com.rag.api.infrastructure.persistence.mapper.TenantMapper;
import com.rag.api.interfaces.guard.SseConnectionGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配额（spec 3.1）：MinIO 存储量、Token 月度消耗、SSE 并发。
 */
@Service
@RequiredArgsConstructor
public class QuotaService {

    private final DocumentMapper documentMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final TenantMapper tenantMapper;
    private final SseConnectionGuard sseGuard;

    public Map<String, Object> usage(String tenantId) {
        TenantEntity tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) {
            throw BizException.notFound("租户不存在");
        }
        long storageUsed = storageUsedBytes(tenantId);
        long tokensUsed = tokensUsedThisMonth(tenantId);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("storageUsedMb", storageUsed / 1024 / 1024);
        map.put("storageMaxMb", tenant.getMaxStorageMb());
        map.put("tokensUsedThisMonth", tokensUsed);
        map.put("tokensMaxThisMonth", tenant.getMaxLlmTokensMonth());
        map.put("sseCurrentConnections", sseGuard.current(tenantId));
        map.put("sseMaxConnections", tenant.getMaxSseConnections());
        map.put("mqConcurrencyMax", tenant.getMaxMqConcurrency());
        return map;
    }

    public long storageUsedBytes(String tenantId) {
        QueryWrapper<DocumentEntity> qw = new QueryWrapper<DocumentEntity>()
                .select("COALESCE(SUM(file_size),0) AS total")
                .eq("tenant_id", tenantId);
        Object v = documentMapper.selectObjs(qw).stream().findFirst().orElse(0);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    public long tokensUsedThisMonth(String tenantId) {
        QueryWrapper<ChatMessageEntity> qw = new QueryWrapper<ChatMessageEntity>()
                .select("COALESCE(SUM(token_usage),0) AS total")
                .eq("tenant_id", tenantId)
                .ge("created_at", LocalDate.now().withDayOfMonth(1).atTime(LocalTime.MIN));
        Object v = chatMessageMapper.selectObjs(qw).stream().findFirst().orElse(0);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    public void checkStorage(String tenantId, long addBytes) {
        TenantEntity tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) {
            throw BizException.notFound("租户不存在");
        }
        long used = storageUsedBytes(tenantId) + addBytes;
        if (used > tenant.getMaxStorageMb() * 1024L * 1024L) {
            throw new BizException(ErrorCode.QUOTA_EXCEEDED,
                    "存储配额超限（%dMB/%dMB），请联系平台管理员调整".formatted(
                            used / 1024 / 1024, tenant.getMaxStorageMb()));
        }
    }

    public void checkTokenQuota(String tenantId, int estimatedPromptTokens) {
        TenantEntity tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) {
            throw BizException.notFound("租户不存在");
        }
        long used = tokensUsedThisMonth(tenantId);
        if (used + estimatedPromptTokens > tenant.getMaxLlmTokensMonth()) {
            throw new BizException(ErrorCode.TOKEN_QUOTA,
                    "Token 月度配额超限（已用 %d / %d）".formatted(used, tenant.getMaxLlmTokensMonth()));
        }
    }
}
