package com.rag.api.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rag.api.common.BizException;
import com.rag.api.common.ErrorCode;
import com.rag.api.infrastructure.persistence.entity.ChatMessageEntity;
import com.rag.api.infrastructure.persistence.entity.TenantEntity;
import com.rag.api.infrastructure.persistence.mapper.ChatMessageMapper;
import com.rag.api.infrastructure.persistence.mapper.TenantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配额（spec 3.1）：MinIO 存储量、Token 月度消耗。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaService {

    private final ChatMessageMapper chatMessageMapper;
    private final TenantMapper tenantMapper;
    private final com.rag.api.infrastructure.storage.MinioStorage minioStorage;
    private final com.rag.api.infrastructure.persistence.mapper.LibraryFileMapper libraryFileMapper;

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
        return map;
    }

    public long storageUsedBytes(String tenantId) {
        // 优先 MinIO 真实用量（含文件库直传、AI 图片、字幕归档、HLS 转码产物等）
        try {
            return minioStorage.sumSizeByPrefix(tenantId + "/");
        } catch (Exception e) {
            log.warn("MinIO 用量统计失败，回退到 DB 求和: {}", e.getMessage());
        }
        // 回退：仅 library_file（document 上传时已双写到 library_file，避免重复计数；
        // 不含转码产物与 MinIO 残留对象）
        QueryWrapper<com.rag.api.infrastructure.persistence.entity.LibraryFileEntity> qwLib =
                new QueryWrapper<com.rag.api.infrastructure.persistence.entity.LibraryFileEntity>()
                        .select("COALESCE(SUM(file_size),0) AS total").eq("tenant_id", tenantId);
        Object vLib = libraryFileMapper.selectObjs(qwLib).stream().findFirst().orElse(0);
        return vLib instanceof Number n ? n.longValue() : 0L;
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
