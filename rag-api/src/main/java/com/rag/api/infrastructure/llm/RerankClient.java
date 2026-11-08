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
 * <p>部分供应商（如阿里兼容网关）只在 DashScope 原生路径暴露 rerank，把 Base URL 配成
 * {@code .../v1} 或裸域名时标准路径会返回 404。因此请求按候选协议依次尝试，
 * 仅在 404/405（路径不匹配）时切换协议；超时、限流、鉴权失败直接失败，不重复请求。
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
        List<Endpoint> endpoints = resolveEndpoints(baseUrl);
        JsonNode resp = null;
        WebClientResponseException lastResponse = null;
        for (int i = 0; i < endpoints.size(); i++) {
            Endpoint endpoint = endpoints.get(i);
            try {
                resp = post(endpoint, apiKey, model, query, documents, topN);
                break;
            } catch (WebClientResponseException e) {
                int status = e.getStatusCode().value();
                if ((status == 404 || status == 405) && i + 1 < endpoints.size()) {
                    lastResponse = e;
                    log.warn("Rerank 路径不可用（HTTP {}）{}，改用备用协议 {}",
                            status, endpoint.url(), endpoints.get(i + 1).url());
                    continue;
                }
                throw describe(e);
            } catch (Exception e) {
                throw new IllegalStateException("Rerank 调用失败: " + e.getMessage(), e);
            }
        }
        if (resp == null) {
            String attempted = endpoints.stream().map(Endpoint::url).toList().toString();
            throw new IllegalStateException("Rerank 调用失败（接口路径不存在，已尝试 " + attempted
                    + "，请检查 Base URL 是否为该供应商的 rerank 地址）",
                    lastResponse);
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

    /** 一次协议尝试的地址与请求体风格。 */
    private record Endpoint(String url, boolean dashScope) {
    }

    /** 发起单次请求；HTTP 错误以 WebClientResponseException 抛出，供上层判断是否切换协议。 */
    private JsonNode post(Endpoint endpoint, String apiKey, String model, String query,
                          List<String> documents, int topN) {
        ObjectNode body = endpoint.dashScope() ? dashScopeBody(model, query, documents, topN)
                : openAiBody(model, query, documents, topN);
        Object payload = body;
        if (endpoint.dashScope()) {
            try {
                payload = ESCAPING_MAPPER.writeValueAsString(body);
            } catch (Exception e) {
                throw new IllegalStateException("Rerank 请求序列化失败: " + e.getMessage(), e);
            }
        }
        return client(apiKey).post()
                .uri(URI.create(endpoint.url()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(timeoutSeconds + 5L));
    }

    private IllegalStateException describe(WebClientResponseException e) {
        int status = e.getStatusCode().value();
        String reason = status == 401 || status == 403 ? "鉴权失败"
                : status == 429 ? "触发限流"
                : status == 404 || status == 405 ? "接口路径不存在（请检查 Base URL 是否支持 rerank）"
                : "上游错误";
        String url = e.getRequest() == null ? "" : e.getRequest().getURI().toString();
        return new IllegalStateException("Rerank 调用失败（" + reason + "，HTTP " + status
                + "）: " + url, e);
    }

    /**
     * 候选协议地址（按优先级）：DashScope 原生地址优先，标准 /rerank 兜底；
     * 其他 Base URL 则标准协议优先，再用其源站（scheme://host）尝试原生地址。
     */
    private List<Endpoint> resolveEndpoints(String baseUrl) {
        String base = stripSlash(baseUrl);
        List<Endpoint> endpoints = new ArrayList<>(2);
        int compatible = base.indexOf("/compatible-mode");
        if (base.contains("dashscope.aliyuncs.com") || compatible > 0) {
            String host = compatible > 0 ? base.substring(0, compatible) : base;
            endpoints.add(new Endpoint(nativeUrl(host), true));
            endpoints.add(new Endpoint(base + "/rerank", false));
        } else {
            endpoints.add(new Endpoint(base + "/rerank", false));
            String origin = origin(base);
            if (origin != null) {
                endpoints.add(new Endpoint(nativeUrl(origin), true));
            }
        }
        return endpoints;
    }

    /** 取 scheme://host[:port]，用于从任意 Base URL 还原供应商源站。 */
    private String origin(String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() == null || uri.getAuthority() == null) {
                return null;
            }
            return uri.getScheme() + "://" + uri.getAuthority();
        } catch (Exception e) {
            return null;
        }
    }

    private String nativeUrl(String host) {
        return host.contains("/api/v1")
                ? host + "/services/rerank/text-rerank/text-rerank"
                : host + "/api/v1/services/rerank/text-rerank/text-rerank";
    }

    private String stripSlash(String url) {
        String s = url.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
