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
 * 流水线任务发布：PARSE/REINDEX → 主队列；失败重试 → retry 队列（TTL 死信回主队列）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IngestPublisher {

    /**
     * @param resume true 表示断点续跑：已有切片时 Worker 跳过解析/切片，直接从向量化继续
     */
    public record IngestMessage(long taskId, String type, String tenantId, long kbId, long documentId,
                                String objectKey, boolean resume) {
    }

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @Value("${rag.mq.exchange}")
    private String exchange;

    public void publish(IngestMessage msg) {
        send(exchange, "PARSE".equalsIgnoreCase(msg.type()) ? "parse" : "reindex", msg, 0);
    }

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
            log.info("任务消息已发送 task={} type={} rk={} delay={}", msg.taskId(), msg.type(), routingKey, delayMs);
        } catch (Exception e) {
            log.error("任务消息发送失败 task={}", msg.taskId(), e);
            throw new IllegalStateException("MQ 消息发送失败: " + e.getMessage(), e);
        }
    }
}
