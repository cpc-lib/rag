package com.rag.worker.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rag.worker.infrastructure.persistence.entity.LibraryFileEntity;
import com.rag.worker.infrastructure.persistence.mapper.LibraryFileMapper;
import com.rag.worker.media.MediaProgressPublisher;
import com.rag.worker.media.MediaTranscodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 媒体转码消费者：文件库视频 → FFmpeg 转 HLS。
 * 手动 ack；失败置 FAILED 不重试（用户重新点击播放会再次投递）。
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
        log.info("收到媒体转码任务 fileId={}", msg.fileId());
        try {
            transcodeService.transcode(msg.fileId());
            channel.basicAck(tag, false);
        } catch (MediaTranscodeService.StoppedException e) {
            // 用户主动停止：状态已由 API 置 NONE，只广播事件同步界面，不置 FAILED
            log.info("转码已被用户停止 fileId={}", msg.fileId());
            progressPublisher.publish(msg.fileId(), "NONE", 0);
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("媒体转码失败 fileId={}: {}", msg.fileId(), e.getMessage(), e);
            LibraryFileEntity entity = libraryFileMapper.selectById(msg.fileId());
            if (entity != null) {
                entity.setPlaybackStatus("FAILED");
                libraryFileMapper.updateById(entity);
                progressPublisher.publish(msg.fileId(), "FAILED", null);
            }
            channel.basicAck(tag, false);
        }
    }
}
