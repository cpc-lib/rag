package com.rag.worker.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rag.worker.mq.RetryPublisher;
import com.rag.worker.mq.TenantMqSemaphore;
import com.rag.worker.pipeline.NonRetryableException;
import com.rag.worker.pipeline.PipelineProcessor;
import com.rag.worker.pipeline.StoppedException;
import com.rag.api.infrastructure.persistence.entity.DocumentEntity;
import com.rag.api.infrastructure.persistence.entity.PipelineTaskEntity;
import com.rag.api.infrastructure.persistence.mapper.DocumentMapper;
import com.rag.api.infrastructure.persistence.mapper.KnowledgeBaseMapper;
import com.rag.api.infrastructure.persistence.mapper.PipelineTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 流水线消费者：收到消息立即 ack，提交 ingestExecutor 异步执行；
 * 失败按 retry_count 延迟重试（TTL 死信），超限入 DLQ 并置 FAILED。
 *
 * <p>可靠性约束（至少一次语义）：
 * <ul>
 *   <li>消息解析后立即 ack，避免分钟级处理触发 RabbitMQ consumer_timeout 重复投递；</li>
 *   <li>任务执行前用 {@link PipelineTaskMapper#claimPending} 条件领取，重复消息不会并发执行；</li>
 *   <li>租户并发额度不足、执行器拒绝时一律延迟重投，绝不静默丢弃；</li>
 *   <li>进程崩溃留下的 PENDING/RUNNING 由 {@code StaleTaskRecovery} 定期重投。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IngestConsumer {

    private final ObjectMapper objectMapper;
    private final PipelineTaskMapper taskMapper;
    private final DocumentMapper documentMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final PipelineProcessor processor;
    private final RetryPublisher retryPublisher;
    private final TenantMqSemaphore semaphore;
    private final Executor ingestExecutor;

    @Value("${rag.worker.max-retry:3}")
    private int maxRetry;

    @Value("${rag.worker.retry-base-delay-ms:10000}")
    private long retryBaseDelayMs;

    /** 并发饱和/执行器拒绝时的重新调度延迟，避免立即回队造成空转。 */
    @Value("${rag.worker.saturation-retry-delay-ms:5000}")
    private long saturationRetryDelayMs;

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
        // 立即 ack：解析/Embedding/索引是分钟级阻塞操作，若等处理完再 ack 会触发 RabbitMQ
        // consumer_timeout（默认 30 分钟）关闭通道并重新投递。
        // 至少一次语义由 DB 条件领取 + 并发饱和重投 + 僵尸任务恢复共同保证。
        channel.basicAck(tag, false);
        log.info("收到任务 task={} type={} doc={}，已确认，异步执行", msg.taskId(), msg.type(), msg.documentId());
        try {
            ingestExecutor.execute(() -> doProcess(msg));
        } catch (RejectedExecutionException e) {
            // 执行器饱和：消息已 ack，必须重新投递，否则任务永远停在 PENDING
            log.warn("执行器拒绝任务，转入延迟重投 task={}", msg.taskId());
            republishSafely(msg);
        }
    }

    private void doProcess(RetryPublisher.IngestMessage msg) {
        PipelineTaskEntity task = taskMapper.selectById(msg.taskId());
        if (task == null || !"PENDING".equals(task.getStatus())) {
            log.warn("任务不可执行（不存在或状态非 PENDING）task={} status={}",
                    msg.taskId(), task == null ? null : task.getStatus());
            return;
        }

        // 租户并发额度不足：延迟重投而非放弃，任务保持 PENDING 可恢复
        if (!semaphore.tryAcquire(msg.tenantId())) {
            log.warn("租户 {} MQ 并发已达上限，任务延迟重投 task={}", msg.tenantId(), msg.taskId());
            republishSafely(msg);
            return;
        }

        try {
            // 条件领取：重复投递/多 Worker 竞争时只有一方能把 PENDING 改为 RUNNING
            if (taskMapper.claimPending(msg.taskId()) != 1) {
                log.info("任务已被其他消费者领取或状态已变化，跳过 task={}", msg.taskId());
                return;
            }
            processor.process(msg, task);
            task.setStatus("SUCCESS");
            taskMapper.updateById(task);
            log.info("任务成功 task={}", msg.taskId());
        } catch (StoppedException e) {
            // 用户主动停止：任务置 CANCELLED，不进入重试
            task.setStatus("CANCELLED");
            taskMapper.updateById(task);
            resetKbIndexState(msg);
            log.info("任务已被用户停止 task={} doc={} errorCode=STOPPED_BY_USER",
                    msg.taskId(), msg.documentId());
        } catch (NonRetryableException e) {
            // 配置/输入错误重试无意义：直接终态，保留可查原因
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            task.setStatus("FAILED");
            task.setErrorMsg(abbreviate(error));
            taskMapper.updateById(task);
            DocumentEntity doc = documentMapper.selectById(msg.documentId());
            if (doc != null) {
                doc.setStatus("FAILED");
                doc.setErrorMsg(abbreviate(error));
                documentMapper.updateById(doc);
            }
            resetKbIndexState(msg);
            log.error("任务不可重试，已置 FAILED task={} doc={} errorCode=NON_RETRYABLE reason={}",
                    msg.taskId(), msg.documentId(), error);
        } catch (Exception e) {
            handleFailure(msg, task, e);
        } finally {
            semaphore.release(msg.tenantId());
        }
    }

    /** 重投失败只记录日志：任务仍是 PENDING/RUNNING，由恢复扫描兜底。 */
    private void republishSafely(RetryPublisher.IngestMessage msg) {
        try {
            retryPublisher.publishRetry(msg, saturationRetryDelayMs);
        } catch (Exception e) {
            log.error("任务重投失败 task={}，等待恢复扫描兜底: {}", msg.taskId(), e.getMessage());
        }
    }

    private void handleFailure(RetryPublisher.IngestMessage msg, PipelineTaskEntity task, Exception e) {
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
            log.info("任务将延迟 {}ms 后第 {}/{} 次重试 task={} doc={} errorCode=RETRYABLE",
                    delay, nextRetry, maxRetry, msg.taskId(), msg.documentId());
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
            resetKbIndexState(msg);
            log.error("任务重试超限，已入 DLQ task={} doc={} retry={} errorCode=RETRY_EXHAUSTED",
                    msg.taskId(), msg.documentId(), nextRetry);
        }
    }

    /**
     * 知识库重建任务终止（不可重试/重试耗尽）时复位 index_state，
     * 避免状态卡在 BUILDING 阻塞后续重建；读别名仍指向旧版本，不影响读取。
     */
    private void resetKbIndexState(RetryPublisher.IngestMessage msg) {
        if (!"REBUILD_KB".equalsIgnoreCase(msg.type())) {
            return;
        }
        try {
            kbMapper.resetIndexState(msg.kbId());
            log.warn("知识库索引重建终止，已复位索引状态 kb={} task={}", msg.kbId(), msg.taskId());
        } catch (Exception e) {
            log.error("知识库索引状态复位失败 kb={} task={}: {}", msg.kbId(), msg.taskId(), e.getMessage());
        }
    }

    private String abbreviate(String s) {
        return s.length() > 900 ? s.substring(0, 900) : s;
    }
}
