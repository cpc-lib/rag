package com.rag.worker.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rag.worker.mq.RetryPublisher;
import com.rag.worker.mq.TenantMqSemaphore;
import com.rag.worker.pipeline.PipelineProcessor;
import com.rag.worker.infrastructure.persistence.entity.DocumentEntity;
import com.rag.worker.infrastructure.persistence.entity.PipelineTaskEntity;
import com.rag.worker.infrastructure.persistence.mapper.DocumentMapper;
import com.rag.worker.infrastructure.persistence.mapper.PipelineTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 流水线消费者：手动 ack；失败按 retry_count 延迟重试（TTL 死信），超限入 DLQ 并置 FAILED。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IngestConsumer {

    private final ObjectMapper objectMapper;
    private final PipelineTaskMapper taskMapper;
    private final DocumentMapper documentMapper;
    private final PipelineProcessor processor;
    private final RetryPublisher retryPublisher;
    private final TenantMqSemaphore semaphore;

    @Value("${rag.worker.max-retry:3}")
    private int maxRetry;

    @Value("${rag.worker.retry-base-delay-ms:10000}")
    private long retryBaseDelayMs;

    @RabbitListener(queues = "${rag.mq.queue}", containerFactory = "ingestFactory")
    public void onMessage(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        RetryPublisher.IngestMessage msg;
        try {
            msg = objectMapper.readValue(message.getBody(), RetryPublisher.IngestMessage.class);
        } catch (Exception e) {
            log.error("消息解析失败，直接丢弃: {}", e.getMessage());
            channel.basicAck(tag, false);
            return;
        }
        log.info("收到任务 task={} type={} doc={}", msg.taskId(), msg.type(), msg.documentId());

        PipelineTaskEntity task = taskMapper.selectById(msg.taskId());
        if (task == null || !"PENDING".equals(task.getStatus())) {
            log.warn("任务不可执行（不存在或状态非 PENDING）task={} status={}",
                    msg.taskId(), task == null ? null : task.getStatus());
            channel.basicAck(tag, false);
            return;
        }

        // 租户并发额度：超出则 requeue 稍后再试
        if (!semaphore.tryAcquire(msg.tenantId())) {
            log.info("租户 {} MQ 并发已达上限，消息重新入队", msg.tenantId());
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            channel.basicNack(tag, false, true);
            return;
        }

        try {
            task.setStatus("RUNNING");
            taskMapper.updateById(task);
            processor.process(msg, task);
            taskMapper.updateById(task);
            channel.basicAck(tag, false);
            log.info("任务成功 task={}", msg.taskId());
        } catch (Exception e) {
            handleFailure(msg, task, e, channel, tag);
        } finally {
            semaphore.release(msg.tenantId());
        }
    }

    private void handleFailure(RetryPublisher.IngestMessage msg, PipelineTaskEntity task,
                               Exception e, Channel channel, long tag) throws IOException {
        String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        log.error("任务失败 task={} retry={}: {}", msg.taskId(), task.getRetryCount(), error, e);
        int nextRetry = (task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1;
        task.setRetryCount(nextRetry);
        task.setErrorMsg(abbreviate(error));
        if (nextRetry <= maxRetry) {
            task.setStatus("PENDING");
            taskMapper.updateById(task);
            long delay = retryBaseDelayMs * nextRetry;
            retryPublisher.publishRetry(msg, delay);
            channel.basicAck(tag, false);
            log.info("任务将延迟 {}ms 后第 {} 次重试 task={}", delay, nextRetry, msg.taskId());
        } else {
            task.setStatus("FAILED");
            taskMapper.updateById(task);
            DocumentEntity doc = documentMapper.selectById(msg.documentId());
            if (doc != null) {
                doc.setStatus("FAILED");
                doc.setErrorMsg(abbreviate(error));
                documentMapper.updateById(doc);
            }
            try {
                retryPublisher.publishDlq(msg);
            } catch (Exception ignored) {
            }
            channel.basicAck(tag, false);
            log.error("任务重试超限，已入 DLQ task={}", msg.taskId());
        }
    }

    private String abbreviate(String s) {
        return s.length() > 900 ? s.substring(0, 900) : s;
    }
}
