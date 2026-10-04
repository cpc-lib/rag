package com.rag.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.api.interfaces.ws.DocProgressWsHandler;
import com.rag.api.interfaces.ws.MediaProgressWsHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.nio.charset.StandardCharsets;

/**
 * WebSocket 配置：
 * /ws/media-progress 转码进度推送（fileId 路由）；
 * /ws/doc-progress 文档处理进度推送（docId 路由）。
 * 进度事件均经 Redis pub/sub 从 Worker 跨进程传入。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final MediaProgressWsHandler mediaProgressWsHandler;
    private final DocProgressWsHandler docProgressWsHandler;
    private final ObjectMapper objectMapper;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(mediaProgressWsHandler, "/ws/media-progress")
                .setAllowedOriginPatterns("*");
        registry.addHandler(docProgressWsHandler, "/ws/doc-progress")
                .setAllowedOriginPatterns("*");
    }

    /** 订阅 Worker 发布的转码进度事件，转发给 WebSocket 订阅会话。 */
    @Bean
    public RedisMessageListenerContainer mediaProgressListener(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            try {
                long fileId = objectMapper.readTree(body).path("fileId").asLong(0);
                if (fileId > 0) {
                    mediaProgressWsHandler.push(fileId, body);
                }
            } catch (Exception ignored) {
                // 非预期消息直接丢弃
            }
        }, new PatternTopic("rag:media:progress"));
        return container;
    }

    /** 订阅 Worker 发布的文档处理进度事件，转发给 WebSocket 订阅会话。 */
    @Bean
    public RedisMessageListenerContainer docProgressListener(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            try {
                long docId = objectMapper.readTree(body).path("docId").asLong(0);
                if (docId > 0) {
                    docProgressWsHandler.push(docId, body);
                }
            } catch (Exception ignored) {
                // 非预期消息直接丢弃
            }
        }, new PatternTopic("rag:doc:progress"));
        return container;
    }
}
