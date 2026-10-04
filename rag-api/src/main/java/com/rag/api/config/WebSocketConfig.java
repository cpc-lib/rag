package com.rag.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * WebSocket 配置：/ws/media-progress 转码进度推送。
 * 进度事件经 Redis pub/sub（rag:media:progress）从 Worker 跨进程传入，按 fileId 路由给订阅会话。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final MediaProgressWsHandler mediaProgressWsHandler;
    private final ObjectMapper objectMapper;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(mediaProgressWsHandler, "/ws/media-progress")
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
        }, new PatternTopic(MediaProgressChannel.CHANNEL));
        return container;
    }

    /** 进度事件通道名（与 rag-worker 约定一致）。 */
    public static final class MediaProgressChannel {
        public static final String CHANNEL = "rag:media:progress";

        private MediaProgressChannel() {
        }
    }
}
