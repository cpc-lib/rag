package com.rag.worker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Worker 统一线程池配置：所有 RabbitMQ 消费者的消息处理都通过此处的线程池异步执行，
 * 避免同步阻塞触发 RabbitMQ consumer_timeout（默认 30 分钟）导致通道关闭和消息重投。
 */
@Configuration
public class ExecutorConfig {

    /** 文档解析流水线：CPU/IO 混合型，核心 2 线程 */
    @Bean
    public Executor ingestExecutor() {
        return buildExecutor("ingest-", 2, 16);
    }

    /** SHA-256 计算：纯 IO 流式读取，轻量，核心 2 线程 */
    @Bean
    public Executor sha256Executor() {
        return buildExecutor("sha256-", 2, 16);
    }

    /** 媒体转码：ffmpeg 外部进程，长时间阻塞，核心 2 线程 */
    @Bean
    public Executor mediaTranscodeExecutor() {
        return buildExecutor("media-transcode-", 2, 8);
    }

    private Executor buildExecutor(String prefix, int poolSize, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(prefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
