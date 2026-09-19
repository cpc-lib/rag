package com.rag.worker.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Worker 侧消息发布：延迟重试（retry 队列 TTL 死信回主队列）/ DLQ。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetryPublisher {

    public record IngestMessage(long taskId, String type, String tenantId, long kbId, long documentId, String objectKey) {
    }

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @Value("${rag.mq.exchange}")
    private String exchange;

    public void publishRetry(IngestMessage msg, long delayMs) {
        send(exchange, "retry", msg, delayMs);
    }

    public void publishDlq(IngestMessage msg) {
        send(exchange, "dlq", msg, 0);
    }

    private void send(String exchangeName, String routingKey, IngestMessage msg, long delayMs) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(msg);
            MessageProperties props = new MessageProperties();
            props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            if (delayMs > 0) {
                props.setExpiration(String.valueOf(delayMs));
            }
            rabbitTemplate.send(exchangeName, routingKey, new Message(payload, props));
            log.info("消息已发送 task={} rk={} delay={}", msg.taskId(), routingKey, delayMs);
        } catch (Exception e) {
            log.error("消息发送失败 task={}", msg.taskId(), e);
            throw new IllegalStateException("MQ 消息发送失败: " + e.getMessage(), e);
        }
    }
}
