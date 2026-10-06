package com.rag.worker.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rag.api.infrastructure.persistence.entity.DocumentEntity;
import com.rag.api.infrastructure.persistence.entity.LibraryFileEntity;
import com.rag.api.infrastructure.persistence.mapper.DocumentMapper;
import com.rag.api.infrastructure.persistence.mapper.GeneratedImageMapper;
import com.rag.api.infrastructure.persistence.mapper.LibraryFileMapper;
import com.rag.worker.infrastructure.storage.MinioStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.concurrent.Executor;

/**
 * SHA-256 指纹消费者：收到消息立即 ack，提交 sha256Executor 异步执行；
 * 大文件/外部下载文件上传后，Worker 从 MinIO 流式计算 SHA-256
 * 并回写 document / library_file / generated_image，供后续秒传匹配。
 * 失败重试 3 次后放弃（指纹是增强能力，不阻断业务）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Sha256Consumer {

    /** 与 API 侧 Sha256Publisher 同构。 */
    public record Sha256Message(String targetType, long targetId, String objectKey) {
    }

    private static final int MAX_RETRY = 3;

    private final ObjectMapper objectMapper;
    private final MinioStorage minioStorage;
    private final DocumentMapper documentMapper;
    private final LibraryFileMapper libraryFileMapper;
    private final Executor sha256Executor;

    @RabbitListener(queues = "${rag.mq.sha256-queue}", containerFactory = "ingestFactory")
    public void onMessage(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        Sha256Message msg;
        try {
            msg = objectMapper.readValue(message.getBody(), Sha256Message.class);
        } catch (Exception e) {
            log.error("SHA-256 消息解析失败，直接丢弃: {}", e.getMessage());
            channel.basicAck(tag, false);
            return;
        }
        // 立即 ack：SHA-256 流式计算大文件是分钟级阻塞操作，若等计算完再 ack 会触发
        // RabbitMQ consumer_timeout（默认 30 分钟）关闭通道并重新投递，导致重复计算。
        channel.basicAck(tag, false);
        log.info("收到 SHA-256 任务 type={} id={}，已确认，异步执行", msg.targetType(), msg.targetId());
        sha256Executor.execute(() -> doCompute(msg));
    }

    private void doCompute(Sha256Message msg) {
        try {
            String sha256 = computeSha256(msg.objectKey());
            saveSha256(msg, sha256);
            log.info("SHA-256 计算完成 type={} id={} sha={}", msg.targetType(), msg.targetId(), sha256);
        } catch (Exception e) {
            log.warn("SHA-256 计算失败 type={} id={} err={}", msg.targetType(), msg.targetId(), e.getMessage());
        }
    }

    /** 从 MinIO 流式计算 SHA-256（不加载整个文件到内存）。 */
    private String computeSha256(String objectKey) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = minioStorage.download(objectKey)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) {
                md.update(buffer, 0, len);
            }
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private void saveSha256(Sha256Message msg, String sha256) {
        switch (msg.targetType()) {
            case "DOCUMENT" -> {
                DocumentEntity e = documentMapper.selectById(msg.targetId());
                if (e != null) {
                    e.setSha256(sha256);
                    documentMapper.updateById(e);
                }
                // 同步该文档归档到文件库的条目（document_id 关联）
                libraryFileMapper.updateSha256ByDocumentId(msg.targetId(), sha256);
            }
            case "LIBRARY_FILE" -> {
                LibraryFileEntity e = libraryFileMapper.selectById(msg.targetId());
                if (e != null) {
                    e.setSha256(sha256);
                    libraryFileMapper.updateById(e);
                }
            }
            case "GENERATED_IMAGE" -> {
                generatedImageMapper.updateSha256ById(msg.targetId(), sha256);
                // 同步该图片归档到文件库的条目（image_id 关联）
                libraryFileMapper.updateSha256ByImageId(msg.targetId(), sha256);
            }
            default -> log.warn("未知 SHA-256 目标类型: {}", msg.targetType());
        }
    }

    private final GeneratedImageMapper generatedImageMapper;
}
