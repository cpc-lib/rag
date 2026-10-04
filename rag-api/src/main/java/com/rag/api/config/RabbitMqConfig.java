package com.rag.api.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑（API 侧声明，Worker 侧同构声明保证幂等）：
 * exchange: rag.ingest (direct)
 *   - rk "parse"   -> rag.ingest.parse（主队列，REINDEX 同队列消费）
 *   - rk "reindex" -> rag.ingest.parse
 *   - rk "retry"   -> rag.ingest.retry（TTL 死信回 rag.ingest，rk parse）
 *   - rk "dlq"     -> rag.ingest.dlq
 */
@Configuration
public class RabbitMqConfig {

    @Bean
    public DirectExchange ragIngestExchange(@Value("${rag.mq.exchange}") String exchange) {
        return new DirectExchange(exchange, true, false);
    }

    @Bean
    public Queue parseQueue(@Value("${rag.mq.queue}") String queue) {
        return QueueBuilder.durable(queue).build();
    }

    @Bean
    public Binding parseBinding(DirectExchange ragIngestExchange, Queue parseQueue) {
        return BindingBuilder.bind(parseQueue).to(ragIngestExchange).with("parse");
    }

    @Bean
    public Binding reindexBinding(DirectExchange ragIngestExchange, Queue parseQueue) {
        return BindingBuilder.bind(parseQueue).to(ragIngestExchange).with("reindex");
    }

    @Bean
    public Queue retryQueue(@Value("${rag.mq.retry-queue}") String retryQueue,
                            @Value("${rag.mq.exchange}") String exchange,
                            @Value("${rag.mq.queue}") String parseQueue) {
        return QueueBuilder.durable(retryQueue)
                .withArgument("x-dead-letter-exchange", exchange)
                .withArgument("x-dead-letter-routing-key", "parse")
                .build();
    }

    @Bean
    public Binding retryBinding(DirectExchange ragIngestExchange, Queue retryQueue) {
        return BindingBuilder.bind(retryQueue).to(ragIngestExchange).with("retry");
    }

    @Bean
    public Queue dlqQueue(@Value("${rag.mq.dlq-queue}") String dlq) {
        return QueueBuilder.durable(dlq).build();
    }

    @Bean
    public Binding dlqBinding(DirectExchange ragIngestExchange, Queue dlqQueue) {
        return BindingBuilder.bind(dlqQueue).to(ragIngestExchange).with("dlq");
    }

    /** 媒体转码队列：文件库视频 → Worker 转 HLS。 */
    @Bean
    public Queue mediaTranscodeQueue(@Value("${rag.mq.media-queue}") String queue) {
        return QueueBuilder.durable(queue).build();
    }

    @Bean
    public Binding mediaTranscodeBinding(DirectExchange ragIngestExchange, Queue mediaTranscodeQueue) {
        return BindingBuilder.bind(mediaTranscodeQueue).to(ragIngestExchange).with("media.transcode");
    }

    /** SHA-256 计算队列：大文件指纹 → Worker 异步计算回写。 */
    @Bean
    public Queue sha256Queue(@Value("${rag.mq.sha256-queue}") String queue) {
        return QueueBuilder.durable(queue).build();
    }

    @Bean
    public Binding sha256Binding(DirectExchange ragIngestExchange, Queue sha256Queue) {
        return BindingBuilder.bind(sha256Queue).to(ragIngestExchange).with("sha256");
    }
}
