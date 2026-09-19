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
 * JWT 认证：解析 Bearer Token → Redis 黑名单校验 → X-Tenant-Id 一致性校验 → 写入 TenantContext。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Set<String> WHITELIST = Set.of("/api/v1/auth/login");

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final SecretKey key;
    private final long ttlHours;

    public JwtAuthFilter(@Value("${rag.jwt.secret}") String secret,
                         @Value("${rag.jwt.ttl-hours:2}") long ttlHours,
                         StringRedisTemplate redis,
                         ObjectMapper objectMapper) {
        this.ttlHours = ttlHours;
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
        if (header == null || !header.startsWith("Bearer ")) {
            writeError(response, ErrorCode.UNAUTHORIZED.httpStatus, "缺少 Bearer Token");
            return;
        }
        String token = header.substring(7);
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            String jti = claims.getId();
            if (jti != null && Boolean.TRUE.equals(redis.hasKey("rag:jwt:bl:" + jti))) {
                writeError(response, ErrorCode.UNAUTHORIZED.httpStatus, "凭证已失效");
                return;
            }
            TenantContext.Session session = new TenantContext.Session(
                    Long.parseLong(claims.getSubject()),
                    claims.get("tid", String.class),
                    claims.get("ut", Integer.class),
                    claims.get("name", String.class));
            String tenantHeader = request.getHeader("X-Tenant-Id");
            if (!session.isPlatformAdmin() && tenantHeader != null && !tenantHeader.equals(session.tenantId())) {
                writeError(response, ErrorCode.FORBIDDEN.httpStatus, "X-Tenant-Id 与登录身份不一致");
                return;
            }
            TenantContext.set(session);
            chain.doFilter(request, response);
        } catch (Exception e) {
            writeError(response, ErrorCode.UNAUTHORIZED.httpStatus, "凭证无效");
        } finally {
            TenantContext.clear();
        }
    }

    public String createToken(TenantContext.Session session) {
        Date now = new Date();
        Date expire = new Date(now.getTime() + ttlHours * 3600_000L);
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(session.userId()))
                .claim("tid", session.tenantId())
                .claim("ut", session.userType())
                .claim("name", session.username())
                .issuedAt(now)
                .expiration(expire)
                .signWith(key)
                .compact();
    }

    public void blacklist(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            long remain = claims.getExpiration().getTime() - System.currentTimeMillis();
            if (remain > 0 && claims.getId() != null) {
                redis.opsForValue().set("rag:jwt:bl:" + claims.getId(), "1", Duration.ofMillis(remain));
            }
        } catch (Exception ignored) {
        }
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResult.fail(status, message)));
    }
}
