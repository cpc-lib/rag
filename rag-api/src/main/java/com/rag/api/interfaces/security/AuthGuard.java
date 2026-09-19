package com.rag.api.interfaces.security;

import com.rag.api.common.BizException;
import com.rag.api.common.ErrorCode;
import com.rag.api.common.TenantContext;

/**
 * RBAC 守卫（§13）：user_type 0=平台超级管理员，1=租户管理员，2=租户普通用户。
 */
public final class AuthGuard {

    private AuthGuard() {
    }

    public static void requirePlatform() {
        if (!TenantContext.require().isPlatformAdmin()) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅平台管理员可操作");
        }
    }

    public static void requireTenantAdmin() {
        TenantContext.Session s = TenantContext.require();
        if (s.userType() != 1) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅租户管理员可操作");
        }
    }
}
