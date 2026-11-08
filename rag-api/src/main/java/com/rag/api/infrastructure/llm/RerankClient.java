package com.rag.api.infrastructure.llm;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
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
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Qwen3-Rerank 二阶段精排客户端。
 *
 * <p>兼容两种协议：
 * <ul>
 *   <li>DashScope 原生（base 为 dashscope 域名或含 /compatible-mode 的阿里兼容地址）：
 *       POST {host}/api/v1/services/rerank/text-rerank/text-rerank，
 *       body {@code {model, input:{query, documents}, parameters:{top_n, return_documents}}}，
 *       序列化时转义非 ASCII（部分兼容网关按字符数读 body，多字节原文会被截断）</li>
 *   <li>OpenAI 兼容 / Jina / Cohere 风格（其他 base）：POST {base}/rerank，
 *       body {@code {model, query, documents, top_n, return_documents}}</li>
 * </ul>
 *
 * <p>本客户端只负责发起请求与解析，调用失败由检索层静默降级为 RRF 原排序。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RerankClient {

    /** index 为原始候选列表下标，score 越大越相关。 */
    public record RerankHit(int index, double score) {
    }

    private final ObjectMapper objectMapper;

    /** 部分兼容网关按字符数读取 body，原始多字节 UTF-8 会被截断报 "truncated JSON document"；原生路径序列化时转义非 ASCII。 */
    private static final ObjectMapper ESCAPING_MAPPER = new ObjectMapper(
            JsonFactory.builder().configure(JsonWriteFeature.ESCAPE_NON_ASCII, true).build());

    @Value("${rag.rerank.timeout-seconds:30}")
    private int timeoutSeconds;

    public List<RerankHit> rerank(String baseUrl, String apiKey, String model,
                                  String query, List<String> documents, int topN) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Rerank 模型缺少 Base URL");
        }
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        String url = resolveUrl(baseUrl);
        boolean dashScope = url.contains("/services/rerank/text-rerank/text-rerank");
        ObjectNode body = dashScope ? dashScopeBody(model, query, documents, topN)
                : openAiBody(model, query, documents, topN);
        Object payload = body;
        if (dashScope) {
            try {
                payload = ESCAPING_MAPPER.writeValueAsString(body);
            } catch (Exception e) {
                throw new IllegalStateException("Rerank 请求序列化失败: " + e.getMessage(), e);
            }
        }
        JsonNode resp;
        try {
            resp = client(apiKey).post()
                    .uri(URI.create(url))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(timeoutSeconds + 5L));
        } catch (WebClientResponseException e) {
            int status = e.getStatusCode().value();
            String reason = status == 401 || status == 403 ? "鉴权失败"
                    : status == 429 ? "触发限流" : "上游错误";
            throw new IllegalStateException("Rerank 调用失败（" + reason + "，HTTP " + status + "）", e);
        } catch (Exception e) {
            throw new IllegalStateException("Rerank 调用失败: " + e.getMessage(), e);
        }
        List<RerankHit> hits = parse(resp);
        hits.sort(Comparator.comparingDouble(RerankHit::score).reversed());
        return topN > 0 && hits.size() > topN ? new ArrayList<>(hits.subList(0, topN)) : hits;
    }

    private WebClient client(String apiKey) {
        HttpClient httpClient = HttpClient.create(ConnectionProvider.newConnection())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(Duration.ofSeconds(timeoutSeconds));
        return WebClient.builder()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + (apiKey == null ? "" : apiKey))
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                .build();
    }

    private ObjectNode dashScopeBody(String model, String query, List<String> documents, int topN) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        ObjectNode input = body.putObject("input");
        input.put("query", query);
        ArrayNode docs = input.putArray("documents");
        documents.forEach(docs::add);
        ObjectNode params = body.putObject("parameters");
        params.put("return_documents", false);
        if (topN > 0) {
            params.put("top_n", topN);
        }
        return body;
    }

    private ObjectNode openAiBody(String model, String query, List<String> documents, int topN) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("query", query);
        ArrayNode docs = body.putArray("documents");
        documents.forEach(docs::add);
        body.put("return_documents", false);
        if (topN > 0) {
            body.put("top_n", topN);
        }
        return body;
    }

    private List<RerankHit> parse(JsonNode resp) {
        if (resp == null) {
            return List.of();
        }
        JsonNode results = resp.path("output").path("results");
        if (!results.isArray()) {
            results = resp.path("results");
        }
        if (!results.isArray()) {
            results = resp.path("data");
        }
        if (!results.isArray()) {
            log.warn("Rerank 响应缺少 results 字段: {}", resp.toString().length() > 512
                    ? resp.toString().substring(0, 512) : resp);
            return List.of();
        }
        List<RerankHit> hits = new ArrayList<>();
        for (JsonNode item : results) {
            int index = item.path("index").asInt(-1);
            if (index < 0) {
                continue;
            }
            double score = item.has("relevance_score") ? item.path("relevance_score").asDouble()
                    : item.has("relevanceScore") ? item.path("relevanceScore").asDouble()
                    : item.path("score").asDouble();
            hits.add(new RerankHit(index, score));
        }
        return hits;
    }

    /** base 为 DashScope 官方域名或任何 Alibaba 兼容地址（含 /compatible-mode）时走原生 rerank 路径。 */
    private String resolveUrl(String baseUrl) {
        String base = stripSlash(baseUrl);
        int compatible = base.indexOf("/compatible-mode");
        if (base.contains("dashscope.aliyuncs.com") || compatible > 0) {
            String host = compatible > 0 ? base.substring(0, compatible) : base;
            if (host.contains("/api/v1")) {
                return host + "/services/rerank/text-rerank/text-rerank";
            }
            return host + "/api/v1/services/rerank/text-rerank/text-rerank";
        }
        return base + "/rerank";
    }

    private String stripSlash(String url) {
        String s = url.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
