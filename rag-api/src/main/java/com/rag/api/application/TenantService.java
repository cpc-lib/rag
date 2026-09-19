package com.rag.api.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rag.api.common.BizException;
import com.rag.api.common.ErrorCode;
import com.rag.api.infrastructure.persistence.entity.SysUserEntity;
import com.rag.api.infrastructure.persistence.entity.TenantEntity;
import com.rag.api.infrastructure.persistence.mapper.SysUserMapper;
import com.rag.api.infrastructure.persistence.mapper.TenantMapper;
import com.rag.api.interfaces.dto.Dtos;
import com.rag.api.interfaces.security.AuthGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.Random;

/**
 * 租户生命周期与配额管理（平台管理员，spec 3.1）。
 */
@Service
@RequiredArgsConstructor
public class TenantService {

    private static final Random RANDOM = new SecureRandom();

    /** 租户编码：仅字母/数字/下划线，10-20 位，创建后不可修改。 */
    private static final java.util.regex.Pattern CODE_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_]{10,20}$");

    private final TenantMapper tenantMapper;
    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public Dtos.ResetAdminResp create(Dtos.TenantCreateReq req) {
        AuthGuard.requirePlatform();
        String code = req.code().trim();
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw BizException.badRequest("租户编码仅允许字母、数字、下划线，长度 10 ~ 20 位");
        }
        if (tenantMapper.selectCount(new QueryWrapper<TenantEntity>().eq("code", code)) > 0) {
            throw BizException.badRequest("租户编码已存在");
        }
        String tenantId = generateTenantId();
        TenantEntity tenant = new TenantEntity();
        tenant.setId(tenantId);
        tenant.setCode(code);
        tenant.setName(req.name());
        tenant.setStatus(1);
        tenant.setMaxStorageMb(req.maxStorageMb() == null ? 1024 : req.maxStorageMb());
        tenant.setMaxMqConcurrency(req.maxMqConcurrency() == null ? 4 : req.maxMqConcurrency());
        tenant.setMaxLlmTokensMonth(req.maxLlmTokensMonth() == null ? 1_000_000L : req.maxLlmTokensMonth());
        tenant.setMaxSseConnections(req.maxSseConnections() == null ? 20 : req.maxSseConnections());
        tenantMapper.insert(tenant);
        String username = req.adminUsername() == null || req.adminUsername().isBlank()
                ? "admin_" + tenantId : req.adminUsername();
        String initialPassword = randomPassword();
        createAdmin(tenantId, username, initialPassword);
        return new Dtos.ResetAdminResp(0, username, initialPassword);
    }

    public List<TenantEntity> list() {
        AuthGuard.requirePlatform();
        return tenantMapper.selectList(new QueryWrapper<>());
    }

    public TenantEntity get(String id) {
        AuthGuard.requirePlatform();
        TenantEntity tenant = tenantMapper.selectById(id);
        if (tenant == null) {
            throw BizException.notFound("租户不存在");
        }
        return tenant;
    }

    public TenantEntity update(String id, Dtos.TenantUpdateReq req) {
        AuthGuard.requirePlatform();
        TenantEntity tenant = get(id);
        if (req.name() != null) {
            tenant.setName(req.name());
        }
        if (req.status() != null) {
            tenant.setStatus(req.status());
        }
        if (req.maxStorageMb() != null) {
            tenant.setMaxStorageMb(req.maxStorageMb());
        }
        if (req.maxMqConcurrency() != null) {
            tenant.setMaxMqConcurrency(req.maxMqConcurrency());
        }
        if (req.maxLlmTokensMonth() != null) {
            tenant.setMaxLlmTokensMonth(req.maxLlmTokensMonth());
        }
        if (req.maxSseConnections() != null) {
            tenant.setMaxSseConnections(req.maxSseConnections());
        }
        tenantMapper.updateById(tenant);
        return tenant;
    }

    /** 删除 = 停用（保留数据可审计）。 */
    public void delete(String id) {
        AuthGuard.requirePlatform();
        if ("000000".equals(id)) {
            throw BizException.forbidden("超级租户不可停用");
        }
        TenantEntity tenant = get(id);
        tenant.setStatus(0);
        tenantMapper.updateById(tenant);
    }

    /** 创建/重置租户管理员，初始密码仅返回一次。 */
    @Transactional
    public Dtos.ResetAdminResp resetAdmin(String tenantId, Dtos.ResetAdminReq req) {
        AuthGuard.requirePlatform();
        get(tenantId);
        SysUserEntity existing = userMapper.selectOne(new QueryWrapper<SysUserEntity>()
                .eq("tenant_id", tenantId).eq("user_type", 1).last("limit 1"));
        String initialPassword = randomPassword();
        if (existing == null) {
            String username = req == null || req.username() == null || req.username().isBlank()
                    ? "admin_" + tenantId : req.username();
            return new Dtos.ResetAdminResp(createAdmin(tenantId, username, initialPassword), username, initialPassword);
        }
        existing.setPasswordHash(passwordEncoder.encode(initialPassword));
        existing.setStatus(1);
        userMapper.updateById(existing);
        return new Dtos.ResetAdminResp(existing.getId(), existing.getUsername(), initialPassword);
    }

    private long createAdmin(String tenantId, String username, String rawPassword) {
        SysUserEntity user = new SysUserEntity();
        user.setTenantId(tenantId);
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setUserType(1);
        user.setStatus(1);
        userMapper.insert(user);
        return user.getId();
    }

    private String generateTenantId() {
        String id;
        do {
            id = String.format("%06d", RANDOM.nextInt(1000000));
        } while (tenantMapper.selectById(id) != null);
        return id;
    }

    private String randomPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
