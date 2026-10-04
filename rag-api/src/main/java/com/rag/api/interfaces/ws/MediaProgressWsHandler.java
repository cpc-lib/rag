package com.rag.api.interfaces.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.api.infrastructure.persistence.entity.LibraryFileEntity;
import com.rag.api.infrastructure.persistence.mapper.LibraryFileMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.crypto.SecretKey;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 转码进度 WebSocket：浏览器连接 /ws/media-progress?token=xxx 后发送 {"fileId":N} 订阅，
 * 后端将 Worker 经 Redis 广播的进度事件按 fileId 推给已订阅的会话。
 * 鉴权：握手时解析 ?token=（浏览器 WS 无法携带 Header）；订阅时校验文件归属（租户+用户）。
 */
@Component
@Slf4j
public class MediaProgressWsHandler extends TextWebSocketHandler {

    /** fileId → 订阅该文件的会话集合。 */
    private final Map<Long, Set<WebSocketSession>> subscribers = new ConcurrentHashMap<>();

    private final LibraryFileMapper libraryFileMapper;
    private final ObjectMapper objectMapper;
    private final SecretKey key;

    public MediaProgressWsHandler(@Value("${rag.jwt.secret}") String secret,
                                  LibraryFileMapper libraryFileMapper,
                                  ObjectMapper objectMapper) {
        this.libraryFileMapper = libraryFileMapper;
        this.objectMapper = objectMapper;
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        try {
            String token = tokenOf(session.getUri());
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            session.getAttributes().put("userId", Long.parseLong(claims.getSubject()));
            session.getAttributes().put("tenantId", claims.get("tid", String.class));
        } catch (Exception e) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("凭证无效"));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        var node = objectMapper.readTree(message.getPayload());
        long fileId = node.path("fileId").asLong(0);
        if (fileId <= 0) {
            return;
        }
        Set<WebSocketSession> set = subscribers.get(fileId);
        if (node.path("unsubscribe").asBoolean(false)) {
            if (set != null) {
                set.remove(session);
            }
            return;
        }
        Long userId = (Long) session.getAttributes().get("userId");
        String tenantId = (String) session.getAttributes().get("tenantId");
        LibraryFileEntity e = libraryFileMapper.selectById(fileId);
        // 跨租户/跨用户订阅静默忽略，不泄露资源存在性
        if (e == null || tenantId == null || !tenantId.equals(e.getTenantId()) || !userId.equals(e.getUserId())) {
            return;
        }
        subscribers.computeIfAbsent(fileId, k -> ConcurrentHashMap.newKeySet()).add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        subscribers.values().forEach(set -> set.remove(session));
    }

    /** 推送进度事件（由 Redis 监听线程调用），payload 原样转发。 */
    public void push(long fileId, String payload) {
        Set<WebSocketSession> set = subscribers.get(fileId);
        if (set == null || set.isEmpty()) {
            return;
        }
        for (WebSocketSession s : set) {
            try {
                // TextWebSocketHandler 会话发送需串行化，避免并发写帧错乱
                synchronized (s) {
                    if (s.isOpen()) {
                        s.sendMessage(new TextMessage(payload));
                    }
                }
            } catch (Exception ignored) {
                // 单个会话发送失败不影响其他订阅者
            }
        }
    }

    /** 从 ws://host/ws/media-progress?token=xxx 提取 token。 */
    private String tokenOf(URI uri) {
        if (uri == null || uri.getQuery() == null) {
            throw new IllegalArgumentException("缺少 token");
        }
        for (String kv : uri.getQuery().split("&")) {
            if (kv.startsWith("token=")) {
                return URLDecoder.decode(kv.substring(6), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalArgumentException("缺少 token");
    }
}
