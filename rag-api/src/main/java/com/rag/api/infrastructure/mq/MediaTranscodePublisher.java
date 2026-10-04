package com.rag.api.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 媒体转码任务发布：文件库视频 → rag.media.transcode 队列，Worker 消费转 HLS。
 * 发送失败仅告警（状态保持 PROCESSING），用户重新点击播放会再次投递。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaTranscodePublisher {

    /** 媒体转码消息：按文件库条目ID定位源文件。 */
    public record MediaTranscodeMessage(long fileId) {
    }

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @Value("${rag.mq.exchange}")
    private String exchange;

    public void publish(long fileId) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(new MediaTranscodeMessage(fileId));
            MessageProperties props = new MessageProperties();
            props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            rabbitTemplate.send(exchange, "media.transcode", new Message(payload, props));
            log.info("媒体转码任务已发送 fileId={}", fileId);
        } catch (Exception e) {
            log.error("媒体转码消息发送失败 fileId={}", fileId, e);
        }
    }
}
