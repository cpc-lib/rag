package com.rag.api.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.api.infrastructure.mq.IngestPublisher;
import com.rag.api.infrastructure.persistence.entity.PipelineTaskEntity;
import com.rag.api.infrastructure.persistence.mapper.PipelineTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 入库运维（ISSUE RAG-011）：队列积压观测与 DLQ 重放。
 * 仅平台管理员可调用，入口见 PipelineOpsController。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineOpsService {

    private static final int MAX_REPLAY = 200;

    private final RabbitTemplate rabbitTemplate;
    private final AmqpAdmin amqpAdmin;
    private final PipelineTaskMapper taskMapper;
    private final IngestPublisher publisher;
    private final ObjectMapper objectMapper;

    @Value("${rag.mq.queue}")
    private String parseQueue;

    @Value("${rag.mq.retry-queue}")
    private String retryQueue;

    @Value("${rag.mq.dlq-queue}")
    private String dlqQueue;

    /** 三个流水线队列的当前积压条数（RabbitMQ 不可达时值为 -1）。 */
    public Map<String, Integer> queueDepths() {
        Map<String, Integer> depths = new LinkedHashMap<>();
        depths.put("parse", depthOf(parseQueue));
        depths.put("retry", depthOf(retryQueue));
        depths.put("dlq", depthOf(dlqQueue));
        return depths;
    }

    private int depthOf(String queue) {
        try {
            var info = amqpAdmin.getQueueInfo(queue);
            return info == null ? -1 : info.getMessageCount();
        } catch (Exception e) {
            log.warn("读取队列积压失败 queue={}: {}", queue, e.getMessage());
            return -1;
        }
    }

    /**
     * 重放 DLQ：逐条取出重新投递主队列，并把任务重置为 PENDING（retryCount 归零）。
     * 文档状态保持 FAILED，Worker 侧允许 FAILED → PARSING/EMBEDDING 的恢复转换。
     */
    public int replayDlq(int limit) {
        int max = Math.max(1, Math.min(limit, MAX_REPLAY));
        int replayed = 0;
        for (int i = 0; i < max; i++) {
            Message raw = rabbitTemplate.receive(dlqQueue);
            if (raw == null) {
                break;
            }
            IngestPublisher.IngestMessage msg;
            try {
                msg = objectMapper.readValue(raw.getBody(), IngestPublisher.IngestMessage.class);
            } catch (Exception e) {
                log.warn("DLQ 消息无法解析，跳过: {}", e.getMessage());
                continue;
            }
            PipelineTaskEntity task = taskMapper.selectById(msg.taskId());
            if (task == null) {
                log.warn("DLQ 任务记录不存在，跳过 task={}", msg.taskId());
                continue;
            }
            task.setStatus("PENDING");
            task.setRetryCount(0);
            task.setErrorMsg(null);
            taskMapper.updateById(task);
            publisher.publish(msg);
            replayed++;
        }
        log.info("DLQ 重放完成 请求上限={} 实际重放={}", max, replayed);
        return replayed;
    }
}
