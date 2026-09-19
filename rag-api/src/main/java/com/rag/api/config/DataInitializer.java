package com.rag.api.config;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rag.api.common.TenantContext;
import com.rag.api.infrastructure.persistence.entity.SysUserEntity;
import com.rag.api.infrastructure.persistence.entity.TenantEntity;
import com.rag.api.infrastructure.persistence.mapper.SysUserMapper;
import com.rag.api.infrastructure.persistence.mapper.TenantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 启动种子：超级租户 000000 + 平台管理员 admin/admin123（首次启动创建）。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DataInitializer {

    public static final String SUPER_TENANT_ID = "000000";

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public ApplicationRunner seedRunner(TenantMapper tenantMapper, SysUserMapper userMapper,
                                        PasswordEncoder encoder) {
        return args -> {
            if (tenantMapper.selectById(SUPER_TENANT_ID) == null) {
                TenantEntity tenant = new TenantEntity();
                tenant.setId(SUPER_TENANT_ID);
                tenant.setName("超级平台租户");
                tenant.setStatus(1);
                tenant.setMaxStorageMb(102400);
                tenant.setMaxMqConcurrency(16);
                tenant.setMaxLlmTokensMonth(10_000_000L);
                tenant.setMaxSseConnections(100);
                tenantMapper.insert(tenant);
                log.info("已创建超级租户 000000");
            }
            Long count = userMapper.selectCount(new QueryWrapper<SysUserEntity>()
                    .eq("tenant_id", SUPER_TENANT_ID).eq("user_type", 0));
            if (count == null || count == 0) {
                SysUserEntity admin = new SysUserEntity();
                admin.setTenantId(SUPER_TENANT_ID);
                admin.setUsername("admin");
                admin.setPasswordHash(encoder.encode("admin123"));
                admin.setUserType(0);
                admin.setStatus(1);
                userMapper.insert(admin);
                log.info("已创建平台管理员账号 admin / admin123（请尽快修改密码）");
            }
            TenantContext.clear();
        };
    }
}
