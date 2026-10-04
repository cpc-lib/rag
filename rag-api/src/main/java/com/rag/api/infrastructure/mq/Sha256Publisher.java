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
 * SHA-256 指纹任务发布者：大文件/外部下载文件上传后不阻塞主流程，
 * 投递到 Worker 异步从 MinIO 流式计算 SHA-256 并回写 document/library_file/generated_image。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Sha256Publisher {

    /**
     * @param targetType DOCUMENT / LIBRARY_FILE / GENERATED_IMAGE
     * @param targetId   目标记录主键
     * @param objectKey  MinIO 对象键
     */
    public record Sha256Message(String targetType, long targetId, String objectKey) {
    }

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @Value("${rag.mq.exchange}")
    private String exchange;

    public void publish(Sha256Message msg) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(msg);
            MessageProperties props = new MessageProperties();
            props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            rabbitTemplate.send(exchange, "sha256", new Message(payload, props));
            log.info("SHA-256 任务已发送 type={} id={} key={}", msg.targetType(), msg.targetId(), msg.objectKey());
        } catch (Exception e) {
            // 指纹计算是增强能力，发送失败不阻断主流程
            log.warn("SHA-256 任务发送失败 type={} id={} err={}", msg.targetType(), msg.targetId(), e.getMessage());
        }
    }
}
