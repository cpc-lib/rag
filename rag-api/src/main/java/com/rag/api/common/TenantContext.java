package com.rag.api.common;

/**
 * 请求级登录上下文（JWT 解析结果）。
 * user_type：0=平台超级管理员（超级租户 000000），1=租户管理员，2=租户普通用户。
 */
public final class TenantContext {

    public record Session(long userId, String tenantId, int userType, String username) {
        public boolean isPlatformAdmin() {
            return userType == 0;
        }
    }

    private static final ThreadLocal<Session> HOLDER = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(Session session) {
        HOLDER.set(session);
    }

    public static Session get() {
        return HOLDER.get();
    }

    public static Session require() {
        Session s = HOLDER.get();
        if (s == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return s;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
