package com.rag.worker.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.channel.ChannelOption;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Embedding 客户端（OpenAI 兼容：POST {base}/embeddings），批量 ≤10 分批串行。
 * baseUrl 留空回退 DashScope 官方兼容端点。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingClient {

    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    private final ObjectMapper objectMapper;

    @Value("${rag.llm.timeout-seconds:60}")
    private int timeoutSeconds;

    /** Embedding 批次并发度：批次间并行请求，缩短大文档向量化耗时 */
    @Value("${rag.worker.embedding-concurrency:4}")
    private int embeddingConcurrency;

    private java.util.concurrent.ExecutorService embeddingPool;

    @jakarta.annotation.PostConstruct
    void initPool() {
        embeddingPool = java.util.concurrent.Executors.newFixedThreadPool(
                Math.max(1, embeddingConcurrency),
                r -> {
                    Thread t = new Thread(r, "embedding-batch");
                    t.setDaemon(true);
                    return t;
                });
    }

    @jakarta.annotation.PreDestroy
    void shutdownPool() {
        if (embeddingPool != null) {
            embeddingPool.shutdownNow();
        }
    }

    public List<float[]> embed(String baseUrl, String apiKey, String model, List<String> texts) {
        return embed(baseUrl, apiKey, model, texts, null);
    }

    /**
     * @param onBatchDone 每完成一批（≤10 条）回调已完成批次数，用于上报进度；可为 null
     */
    public List<float[]> embed(String baseUrl, String apiKey, String model, List<String> texts,
                               java.util.function.IntConsumer onBatchDone) {
        String base = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL
                : baseUrl.trim().replaceAll("/+$", "");
        int batchCount = (texts.size() + 9) / 10;
        List<java.util.concurrent.CompletableFuture<List<float[]>>> futures = new ArrayList<>(batchCount);
        java.util.concurrent.Semaphore permits = new java.util.concurrent.Semaphore(Math.max(1, embeddingConcurrency));
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        for (int b = 0; b < batchCount; b++) {
            int from = b * 10;
            List<String> batch = texts.subList(from, Math.min(from + 10, texts.size()));
            futures.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    permits.acquire();
                    try {
                        return embedBatch(base, apiKey, model, batch);
                    } finally {
                        permits.release();
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Embedding 并发调度被中断", ie);
                }
            }, embeddingPool).whenComplete((r, e) -> {
                if (onBatchDone != null) {
                    onBatchDone.accept(done.incrementAndGet());
                }
            }));
        }
        List<float[]> result = new ArrayList<>(texts.size());
        for (java.util.concurrent.CompletableFuture<List<float[]>> f : futures) {
            result.addAll(f.join());
        }
        return result;
    }

    private List<float[]> embedBatch(String base, String apiKey, String model, List<String> texts) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        ArrayNode input = body.putArray("input");
        texts.forEach(input::add);

        // 瞬时错误（限流 403/429、网关 5xx、网络抖动）有限退避重试，避免单批失败拖垮整轮嵌入
        JsonNode resp = null;
        Exception lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpClient httpClient = HttpClient.create()
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                        .responseTimeout(Duration.ofSeconds(timeoutSeconds));
                resp = WebClient.builder()
                        .baseUrl(base)
                        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + (apiKey == null ? "" : apiKey))
                        .clientConnector(new ReactorClientHttpConnector(httpClient))
                        .build()
                        .post().uri("/embeddings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .block(Duration.ofSeconds(timeoutSeconds));
                lastError = null;
                break;
            } catch (Exception e) {
                lastError = e;
                if (attempt < 3) {
                    long backoff = 2000L << (attempt - 1);
                    log.warn("Embedding 批次失败（{}），{}ms 后第 {}/3 次重试: {}",
                            e.getClass().getSimpleName(), backoff, attempt + 1,
                            e.getMessage() == null ? "" : e.getMessage());
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Embedding 重试被中断", ie);
                    }
                }
            }
        }
        if (lastError != null) {
            throw new IllegalStateException("Embedding 批次重试 3 次仍失败: " + lastError.getMessage(), lastError);
        }

        List<JsonNode> items = new ArrayList<>();
        resp.withArray("data").forEach(items::add);
        items.sort(Comparator.comparingInt(n -> n.path("index").asInt(0)));
        List<float[]> vectors = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            JsonNode emb = item.path("embedding");
            float[] vec = new float[emb.size()];
            for (int i = 0; i < emb.size(); i++) {
                vec[i] = (float) emb.get(i).asDouble();
            }
            vectors.add(vec);
        }
        if (vectors.size() != texts.size()) {
            throw new IllegalStateException("Embedding 返回数量不一致: " + vectors.size() + " != " + texts.size());
        }
        return vectors;
    }
}
