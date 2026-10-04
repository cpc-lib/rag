package com.rag.worker.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 文档处理进度事件发布：经 Redis pub/sub 广播给 rag-api，由其推送给订阅的 WebSocket 会话。
 * 与媒体转码进度（rag:media:progress）同构，docId 路由。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DocProgressPublisher {

    /** 进度事件通道（与 rag-api WebSocketConfig 约定一致）。 */
    public static final String CHANNEL = "rag:doc:progress";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public void publish(long docId, String status, Integer progress) {
        try {
            Map<String, Object> msg = new HashMap<>();
            msg.put("docId", docId);
            msg.put("status", status);
            msg.put("progress", progress == null ? -1 : progress);
            redis.convertAndSend(CHANNEL, objectMapper.writeValueAsString(msg));
        } catch (Exception e) {
            // 进度推送失败不影响处理主流程
            log.warn("文档进度事件发布失败 docId={} err={}", docId, e.getMessage());
        }
    }
}
