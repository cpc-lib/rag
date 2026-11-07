package com.rag.worker.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.api.infrastructure.persistence.entity.PipelineTaskEntity;
import com.rag.api.infrastructure.persistence.mapper.PipelineTaskMapper;
import com.rag.worker.mq.RetryPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 僵尸任务恢复：Worker 崩溃/消息丢失后，把长时间未更新的 PENDING/RUNNING 任务重置为 PENDING 并重新投递。
 *
 * <p>阈值需大于正常单文档处理时长（默认 60 分钟）；重投后仍由 {@code IngestConsumer.claimPending}
 * 条件领取保证同一任务不会被重复执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleTaskRecovery {

    private final PipelineTaskMapper taskMapper;
    private final RetryPublisher retryPublisher;
    private final ObjectMapper objectMapper;

    @Value("${rag.worker.stale-task-minutes:60}")
    private int staleMinutes;

    @Scheduled(fixedDelayString = "${rag.worker.recovery-interval-ms:60000}",
            initialDelayString = "${rag.worker.recovery-initial-delay-ms:120000}")
    public void recoverStaleTasks() {
        List<PipelineTaskEntity> stale;
        try {
            stale = taskMapper.selectStaleTasks(staleMinutes);
        } catch (Exception e) {
            log.warn("扫描僵尸任务失败: {}", e.getMessage());
            return;
        }
        if (stale.isEmpty()) {
            return;
        }
        for (PipelineTaskEntity task : stale) {
            RetryPublisher.IngestMessage msg = parsePayload(task);
            if (msg == null) {
                log.error("僵尸任务 payload 不可解析，无法恢复 task={} status={} doc={}",
                        task.getId(), task.getStatus(), task.getDocumentId());
                continue;
            }
            String before = task.getStatus();
            task.setStatus("PENDING");
            try {
                taskMapper.updateById(task);
                retryPublisher.publishRetry(msg, 0);
                log.warn("僵尸任务已重置并重投 task={} 原状态={} doc={}",
                        task.getId(), before, task.getDocumentId());
            } catch (Exception e) {
                log.error("僵尸任务恢复失败 task={}: {}", task.getId(), e.getMessage());
            }
        }
    }

    private RetryPublisher.IngestMessage parsePayload(PipelineTaskEntity task) {
        if (task.getPayload() == null || task.getPayload().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(task.getPayload(), RetryPublisher.IngestMessage.class);
        } catch (Exception e) {
            return null;
        }
    }
}
