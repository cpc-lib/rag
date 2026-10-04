package com.rag.worker.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;

/**
 * 停止转码监听：API 经 Redis 广播 rag:media:stop，Worker 收到后强杀对应 ffmpeg 进程。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class MediaStopConfig {

    /** 停止转码频道（与 rag-api 侧一致）。 */
    public static final String STOP_CHANNEL = "rag:media:stop";

    private final MediaTranscodeService transcodeService;
    private final ObjectMapper objectMapper;

    @Bean
    public RedisMessageListenerContainer mediaStopListener(RedisConnectionFactory cf) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(cf);
        container.addMessageListener((message, pattern) -> {
            try {
                long fileId = objectMapper
                        .readTree(new String(message.getBody(), StandardCharsets.UTF_8))
                        .path("fileId").asLong(0);
                if (fileId > 0) {
                    log.info("收到停止转码请求 fileId={}", fileId);
                    transcodeService.requestStop(fileId);
                }
            } catch (Exception ignored) {
                // 非法消息直接忽略
            }
        }, new PatternTopic(STOP_CHANNEL));
        return container;
    }
}
