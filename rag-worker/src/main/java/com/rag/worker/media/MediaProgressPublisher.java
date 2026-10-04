package com.rag.worker.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 转码进度事件发布：经 Redis pub/sub 广播给 rag-api，由其推送给订阅的 WebSocket 会话。
 * progress 为 null 时用 -1 占位（JSON 不携带 null 语义）。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MediaProgressPublisher {

    /** 进度事件通道（与 rag-api WebSocketConfig 约定一致）。 */
    public static final String CHANNEL = "rag:media:progress";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public void publish(long fileId, String status, Integer progress) {
        try {
            Map<String, Object> msg = new HashMap<>();
            msg.put("fileId", fileId);
            msg.put("status", status);
            msg.put("progress", progress == null ? -1 : progress);
            redis.convertAndSend(CHANNEL, objectMapper.writeValueAsString(msg));
        } catch (Exception e) {
            // 进度推送失败不影响转码主流程
            log.warn("进度事件发布失败 fileId={} err={}", fileId, e.getMessage());
        }
    }
}
