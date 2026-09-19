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
        List<float[]> result = new ArrayList<>(texts.size());
        int batchesDone = 0;
        for (int from = 0; from < texts.size(); from += 10) {
            result.addAll(embedBatch(base, apiKey, model,
                    texts.subList(from, Math.min(from + 10, texts.size()))));
            batchesDone++;
            if (onBatchDone != null) {
                onBatchDone.accept(batchesDone);
            }
        }
        return result;
    }

    private List<float[]> embedBatch(String base, String apiKey, String model, List<String> texts) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        ArrayNode input = body.putArray("input");
        texts.forEach(input::add);

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(Duration.ofSeconds(timeoutSeconds));
        JsonNode resp = WebClient.builder()
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
