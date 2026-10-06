package com.rag.worker.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rag.api.infrastructure.persistence.entity.LibraryFileEntity;
import com.rag.api.infrastructure.persistence.mapper.LibraryFileMapper;
import com.rag.worker.media.MediaProgressPublisher;
import com.rag.worker.media.MediaTranscodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.Executor;

/**
 * 媒体转码消费者：收到消息立即 ack，提交 mediaTranscodeExecutor 异步执行；
 * 文件库视频 → FFmpeg 转 HLS。失败置 FAILED 不重试（用户重新点击播放会再次投递）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaTranscodeConsumer {

    /** 媒体转码消息（与 API 侧 MediaTranscodePublisher 同构）。 */
    public record MediaTranscodeMessage(long fileId) {
    }

    private final ObjectMapper objectMapper;
    private final MediaTranscodeService transcodeService;
    private final LibraryFileMapper libraryFileMapper;
    private final MediaProgressPublisher progressPublisher;
    private final Executor mediaTranscodeExecutor;

    @RabbitListener(queues = "${rag.mq.media-queue}", containerFactory = "ingestFactory")
    public void onMessage(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        MediaTranscodeMessage msg;
        try {
            msg = objectMapper.readValue(message.getBody(), MediaTranscodeMessage.class);
        } catch (Exception e) {
            log.error("转码消息解析失败，直接丢弃: {}", e.getMessage());
            channel.basicAck(tag, false);
            return;
        }
        // 立即 ack：ffmpeg 转码是分钟级阻塞操作，若等转码完再 ack 会触发 RabbitMQ
        // consumer_timeout（默认 30 分钟）关闭通道并重新投递，导致任务从头重复执行。
        // 幂等性由 DB 状态保证（PROCESSING 状态下重复投递会被忽略）。
        channel.basicAck(tag, false);
        log.info("收到媒体转码任务 fileId={}，已确认，异步执行", msg.fileId());
        mediaTranscodeExecutor.execute(() -> doTranscode(msg.fileId()));
    }

    private void doTranscode(long fileId) {
        try {
            transcodeService.transcode(fileId);
        } catch (MediaTranscodeService.StoppedException e) {
            // 用户主动停止：状态已由 API 置 NONE，只广播事件同步界面，不置 FAILED
            log.info("转码已被用户停止 fileId={}", fileId);
            progressPublisher.publish(fileId, "NONE", 0);
        } catch (Exception e) {
            log.error("媒体转码失败 fileId={}: {}", fileId, e.getMessage(), e);
            LibraryFileEntity entity = libraryFileMapper.selectById(fileId);
            if (entity != null) {
                entity.setPlaybackStatus("FAILED");
                libraryFileMapper.updateById(entity);
                progressPublisher.publish(fileId, "FAILED", null);
            }
        }
    }
}
