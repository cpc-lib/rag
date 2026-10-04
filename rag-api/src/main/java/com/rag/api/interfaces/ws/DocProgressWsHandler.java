package com.rag.api.interfaces.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.api.infrastructure.persistence.entity.DocumentEntity;
import com.rag.api.infrastructure.persistence.mapper.DocumentMapper;
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
 * 文档处理进度 WebSocket：浏览器连接 /ws/doc-progress?token=xxx 后发送 {"docId":N} 订阅，
 * 后端将 Worker 经 Redis 广播的进度事件按 docId 推给已订阅的会话。
 * 鉴权：握手时解析 ?token=；订阅时校验文档归属（租户）。
 */
@Component
@Slf4j
public class DocProgressWsHandler extends TextWebSocketHandler {

    /** docId → 订阅该文档的会话集合。 */
    private final Map<Long, Set<WebSocketSession>> subscribers = new ConcurrentHashMap<>();

    private final DocumentMapper documentMapper;
    private final ObjectMapper objectMapper;
    private final SecretKey key;

    public DocProgressWsHandler(@Value("${rag.jwt.secret}") String secret,
                                DocumentMapper documentMapper,
                                ObjectMapper objectMapper) {
        this.documentMapper = documentMapper;
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
        long docId = node.path("docId").asLong(0);
        if (docId <= 0) {
            return;
        }
        Set<WebSocketSession> set = subscribers.get(docId);
        if (node.path("unsubscribe").asBoolean(false)) {
            if (set != null) {
                set.remove(session);
            }
            return;
        }
        String tenantId = (String) session.getAttributes().get("tenantId");
        DocumentEntity e = documentMapper.selectById(docId);
        // 跨租户订阅静默忽略，不泄露资源存在性
        if (e == null || tenantId == null || !tenantId.equals(e.getTenantId())) {
            return;
        }
        subscribers.computeIfAbsent(docId, k -> ConcurrentHashMap.newKeySet()).add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        subscribers.values().forEach(set -> set.remove(session));
    }

    /** 推送进度事件（由 Redis 监听线程调用），payload 原样转发。 */
    public void push(long docId, String payload) {
        Set<WebSocketSession> set = subscribers.get(docId);
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

    /** 从 ws://host/ws/doc-progress?token=xxx 提取 token。 */
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
