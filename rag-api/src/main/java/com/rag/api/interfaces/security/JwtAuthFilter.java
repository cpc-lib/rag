package com.rag.api.interfaces.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.api.common.ApiResult;
import com.rag.api.common.ErrorCode;
import com.rag.api.common.TenantContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

/**
 * JWT + Redis 会话认证：JWT 仅作身份凭证（不依赖其 exp），会话有效期由 Redis 统一管理。
 * 会话 key：rag:jwt:sess:{jti}（值为会话 JSON，TTL = ttl-hours）；
 * 每次请求校验通过后，若剩余有效期 < renew-minutes 则滑动续期（重置为完整 TTL），
 * 实现"活跃用户不掉线、不活跃用户按 TTL 过期"，且登出/权限变更由后端即时管控。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Set<String> WHITELIST = Set.of("/api/v1/auth/login");
    /** Redis 会话 key 前缀 */
    private static final String SESS_PREFIX = "rag:jwt:sess:";
    /** Redis 黑名单 key 前缀 */
    private static final String BL_PREFIX = "rag:jwt:bl:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final SecretKey key;
    private final long ttlHours;
    private final long renewMinutes;

    public JwtAuthFilter(@Value("${rag.jwt.secret}") String secret,
                         @Value("${rag.jwt.ttl-hours:2}") long ttlHours,
                         @Value("${rag.jwt.renew-minutes:10}") long renewMinutes,
                         StringRedisTemplate redis,
                         ObjectMapper objectMapper) {
        this.ttlHours = ttlHours;
        this.renewMinutes = renewMinutes;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return WHITELIST.contains(path) || path.startsWith("/actuator") || path.startsWith("/error");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        String token;
        if (header != null && header.startsWith("Bearer ")) {
            token = header.substring(7);
        } else {
            // 媒体标签（<video>/<audio>）无法携带 Header，允许 ?token= 查询参数兜底
            token = request.getParameter("token");
            if (token == null || token.isBlank()) {
                writeError(response, ErrorCode.UNAUTHORIZED.httpStatus, "缺少 Bearer Token");
                return;
            }
        }
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            String jti = claims.getId();
            if (jti == null) {
                writeError(response, ErrorCode.UNAUTHORIZED.httpStatus, "凭证无效");
                return;
            }
            // 黑名单（登出后未过期的旧 token 立即失效）
            if (Boolean.TRUE.equals(redis.hasKey(BL_PREFIX + jti))) {
                writeError(response, ErrorCode.UNAUTHORIZED.httpStatus, "凭证已失效");
                return;
            }
            // 会话有效性以 Redis 为准：无会话即已过期/被登出，与 JWT 自身时间无关
            String sessJson = redis.opsForValue().get(SESS_PREFIX + jti);
            if (sessJson == null) {
                writeError(response, ErrorCode.UNAUTHORIZED.httpStatus, "凭证已失效");
                return;
            }
            // 会话数据取自 Redis（权威副本），租户/角色变更即时生效
            TenantContext.Session session = objectMapper.readValue(sessJson, TenantContext.Session.class);
            String tenantHeader = request.getHeader("X-Tenant-Id");
            if (!session.isPlatformAdmin() && tenantHeader != null && !tenantHeader.equals(session.tenantId())) {
                writeError(response, ErrorCode.FORBIDDEN.httpStatus, "X-Tenant-Id 与登录身份不一致");
                return;
            }
            TenantContext.set(session);
            // 滑动续期：剩余有效期不足 renew-minutes 时重置为完整 TTL
            renewIfNeeded(jti);
            chain.doFilter(request, response);
        } catch (Exception e) {
            writeError(response, ErrorCode.UNAUTHORIZED.httpStatus, "凭证无效");
        } finally {
            TenantContext.clear();
        }
    }

    /** 签发 JWT 并创建 Redis 会话（TTL = ttl-hours）。 */
    public String createToken(TenantContext.Session session) {
        Date now = new Date();
        // 会话有效期由 Redis 管理，JWT 不写 exp，避免"会话仍有效而 JWT 先过期"
        String token = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(session.userId()))
                .claim("tid", session.tenantId())
                .claim("ut", session.userType())
                .claim("name", session.username())
                .issuedAt(now)
                .signWith(key)
                .compact();
        redis.opsForValue().set(SESS_PREFIX + claimsId(token), toJson(session), Duration.ofHours(ttlHours));
        return token;
    }

    /** 登出：黑名单 + 删除 Redis 会话，双保险。 */
    public void blacklist(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            String jti = claims.getId();
            if (jti != null) {
                // 旧 token 有 exp 用其剩余时间；新 token 无 exp，兜底用会话 TTL
                //（黑名单仅冗余保险，会话删除才是权威判定）
                long remain = claims.getExpiration() != null
                        ? claims.getExpiration().getTime() - System.currentTimeMillis()
                        : ttlHours * 3600_000L;
                if (remain > 0) {
                    redis.opsForValue().set(BL_PREFIX + jti, "1", Duration.ofMillis(remain));
                }
                redis.delete(SESS_PREFIX + jti);
            }
        } catch (Exception ignored) {
        }
    }

    /** 剩余有效期 < renew-minutes 时重置为完整 TTL。 */
    private void renewIfNeeded(String jti) {
        Long remainSeconds = redis.getExpire(SESS_PREFIX + jti);
        if (remainSeconds != null && remainSeconds >= 0 && remainSeconds < renewMinutes * 60) {
            redis.expire(SESS_PREFIX + jti, Duration.ofHours(ttlHours));
        }
    }

    private String toJson(TenantContext.Session session) {
        try {
            return objectMapper.writeValueAsString(session);
        } catch (Exception e) {
            throw new IllegalStateException("会话序列化失败", e);
        }
    }

    private String claimsId(String token) {
        try {
            return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload().getId();
        } catch (Exception e) {
            throw new IllegalStateException("token 解析失败", e);
        }
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResult.fail(status, message)));
    }
}
