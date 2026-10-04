package com.rag.api.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.channel.ChannelOption;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 对话与向量均走 OpenAI 兼容协议（chat completions 流式/非流式/function-calling、embeddings）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmClient {

    /** 模型发起的一次工具调用。 */
    public record ToolCall(String id, String name, String arguments) {
    }

    /**
     * 对话消息。普通消息只填 role/content；
     * 模型请求工具的 assistant 消息填 toolCalls（content 可为 null）；
     * 工具执行结果 role="tool"，填 toolCallId。
     */
    public record LlmMessage(String role, String content, String toolCallId, List<ToolCall> toolCalls) {
        public LlmMessage(String role, String content) {
            this(role, content, null, null);
        }
    }

    public record ChatParams(BigDecimal temperature, BigDecimal topP, int maxTokens) {
    }

    /** 流式增量：content 为空时表示该帧仅携带 usage。 */
    public record StreamDelta(String content, Long promptTokens, Long completionTokens) {
    }

    private final ObjectMapper objectMapper;

    @Value("${rag.llm.timeout-seconds:60}")
    private int timeoutSeconds;

    private static final String DEFAULT_EMBED_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    private WebClient build(String baseUrl, String apiKey, int timeoutSeconds) {
        // 不使用连接池：LLM 调用低频，避免跨问答复用到被云端 LB/防火墙 RST 的空闲连接（Connection reset）
        HttpClient httpClient = HttpClient.create(ConnectionProvider.newConnection())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(Duration.ofSeconds(timeoutSeconds));
        return WebClient.builder()
                .baseUrl(baseUrl == null ? "" : stripSlash(baseUrl))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + (apiKey == null ? "" : apiKey))
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    private String stripSlash(String url) {
        String s = url.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** 从 WebClientResponseException 中提取 HTTP 状态与响应体，便于定位上游 4xx/5xx 根因。 */
    private String upstreamError(WebClientResponseException e) {
        HttpStatusCode code = e.getStatusCode();
        String body = e.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return "HTTP " + code.value() + " " + e.getStatusText();
        }
        // 响应体可能很长，截断到 2KB 避免日志/异常膨胀
        if (body.length() > 2048) {
            body = body.substring(0, 2048) + "...(truncated)";
        }
        return "HTTP " + code.value() + " " + e.getStatusText() + ", response body: " + body;
    }

    /** 非流式对话（用于工具循环），返回 choices[0].message 节点。 */
    public JsonNode chatOnce(String baseUrl, String apiKey, String model, List<LlmMessage> messages,
                             JsonNode tools, ChatParams params, int timeoutSeconds) {
        ObjectNode body = buildBody(model, messages, params, false, tools, "auto");
        try {
            JsonNode resp = build(baseUrl, apiKey, timeoutSeconds).post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(timeoutSeconds));
            if (resp == null || !resp.has("choices")) {
                throw new IllegalStateException("LLM 响应缺少 choices");
            }
            return resp.path("choices").get(0).path("message");
        } catch (WebClientResponseException e) {
            throw new IllegalStateException("LLM 调用失败: " + upstreamError(e), e);
        } catch (Exception e) {
            throw new IllegalStateException("LLM 调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 流式对话，逐块产出增量文本；最后一帧可能携带 usage。
     * tools 非空时随请求携带工具定义；toolChoice 非空时设置 tool_choice（如 "none" 强制不调工具）。
     */
    public reactor.core.publisher.Flux<StreamDelta> chatStream(String baseUrl, String apiKey, String model,
                                                               List<LlmMessage> messages, ChatParams params,
                                                               JsonNode tools, String toolChoice,
                                                               int timeoutSeconds) {
        ObjectNode body = buildBody(model, messages, params, true, tools, toolChoice);
        return build(baseUrl, apiKey, timeoutSeconds).post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .onErrorMap(WebClientResponseException.class,
                        e -> new IllegalStateException("LLM 调用失败: " + upstreamError(e), e))
                .flatMap(payload0 -> {
                    // bodyToFlux(String) 对 text/event-stream 由 SSE reader 解码，元素已剥掉 "data:" 前缀
                    String payload = payload0.trim();
                    if (payload.isEmpty() || "[DONE]".equals(payload)) {
                        return reactor.core.publisher.Mono.empty();
                    }
                    try {
                        JsonNode node = objectMapper.readTree(payload);
                        if (node.hasNonNull("error")) {
                            String msg = node.path("error").path("message").asText("模型返回错误");
                            return reactor.core.publisher.Mono.error(new IllegalStateException(msg));
                        }
                        JsonNode delta = node.path("choices").path(0).path("delta");
                        String content = delta.path("content").asText(null);
                        JsonNode usage = node.get("usage");
                        Long prompt = usage == null ? null : usage.path("prompt_tokens").asLong(0);
                        Long completion = usage == null ? null : usage.path("completion_tokens").asLong(0);
                        if (content == null && prompt == null) {
                            return reactor.core.publisher.Mono.empty();
                        }
                        return reactor.core.publisher.Mono.just(new StreamDelta(content, prompt, completion));
                    } catch (Exception e) {
                        log.debug("忽略无法解析的 SSE 帧: {}", payload);
                        return reactor.core.publisher.Mono.empty();
                    }
                });
    }

    /** Embeddings：OpenAI 兼容 HTTP（POST {base}/embeddings），按 batch<=10 分批串行。 */
    public List<float[]> embed(String baseUrl, String apiKey, String model, List<String> texts) {
        String base = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_EMBED_BASE : baseUrl;
        List<float[]> result = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += 10) {
            result.addAll(embedBatch(base, apiKey, model,
                    texts.subList(from, Math.min(from + 10, texts.size()))));
        }
        return result;
    }

    private List<float[]> embedBatch(String baseUrl, String apiKey, String model, List<String> texts) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        ArrayNode input = body.putArray("input");
        texts.forEach(input::add);
        JsonNode resp;
        try {
            resp = build(baseUrl, apiKey, timeoutSeconds).post()
                    .uri("/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (WebClientResponseException e) {
            throw new IllegalStateException("Embedding 调用失败: " + upstreamError(e), e);
        } catch (Exception e) {
            throw new IllegalStateException("Embedding 调用失败: " + e.getMessage(), e);
        }
        List<JsonNode> items = new ArrayList<>();
        resp.path("data").forEach(items::add);
        items.sort(Comparator.comparingInt(n -> n.path("index").asInt()));
        List<float[]> vectors = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            JsonNode embedding = item.path("embedding");
            float[] vec = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vec[i] = (float) embedding.get(i).asDouble();
            }
            vectors.add(vec);
        }
        if (vectors.size() != texts.size()) {
            throw new IllegalStateException("Embedding 返回数量不一致: " + vectors.size() + " != " + texts.size());
        }
        return vectors;
    }

    private ObjectNode buildBody(String model, List<LlmMessage> messages, ChatParams params, boolean stream,
                                 JsonNode tools, String toolChoice) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        ArrayNode msgArr = body.putArray("messages");
        for (LlmMessage m : messages) {
            ObjectNode n = msgArr.addObject();
            n.put("role", m.role());
            if (m.toolCalls() != null) {
                // 模型请求工具：content 通常为 null，tool_calls 原样回传
                n.put("content", m.content());
                ArrayNode callArr = n.putArray("tool_calls");
                for (ToolCall tc : m.toolCalls()) {
                    ObjectNode callNode = callArr.addObject();
                    callNode.put("id", tc.id()).put("type", "function");
                    callNode.putObject("function")
                            .put("name", tc.name()).put("arguments", tc.arguments());
                }
            } else {
                n.put("content", m.content());
            }
            if ("tool".equals(m.role())) {
                n.put("tool_call_id", m.toolCallId());
            }
        }
        if (params != null) {
            if (params.temperature() != null) {
                body.put("temperature", params.temperature().doubleValue());
            }
            if (params.topP() != null) {
                body.put("top_p", params.topP().doubleValue());
            }
            body.put("max_tokens", params.maxTokens());
        }
        body.put("stream", stream);
        if (stream) {
            body.putObject("stream_options").put("include_usage", true);
        }
        if (tools != null && tools.isArray() && !tools.isEmpty()) {
            body.set("tools", tools);
            // tool_choice 必须与 tools 成对出现：无 tools 时发送 tool_choice 会被上游 400 拒绝
            if (toolChoice != null) {
                body.put("tool_choice", toolChoice);
            }
        }
        return body;
    }

    /** 构建工具定义 JSON（OpenAI function-calling 格式）。 */
    public JsonNode weatherTool() {
        ObjectNode location = objectMapper.createObjectNode()
                .put("type", "string").put("description", "城市名，如：北京");
        ObjectNode days = objectMapper.createObjectNode()
                .put("type", "integer")
                .put("description", "查询天数：1=实时天气（默认），2~7=含今天在内的未来多天预报");
        days.put("minimum", 1);
        days.put("maximum", 7);
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("location", location);
        properties.set("days", days);
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        parameters.set("properties", properties);
        parameters.set("required", objectMapper.createArrayNode().add("location"));
        return tool("query_weather", "查询指定城市的实时天气或未来多天预报，气象类问题使用", parameters);
    }

    public JsonNode tavilyTool() {
        ObjectNode query = objectMapper.createObjectNode()
                .put("type", "string").put("description", "搜索关键词");
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("query", query);
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        parameters.set("properties", properties);
        parameters.set("required", objectMapper.createArrayNode().add("query"));
        return tool("tavily_search", "联网搜索最新资讯，当用户询问实时信息或知识库无相关内容时使用", parameters);
    }

    private ObjectNode tool(String name, String description, ObjectNode parameters) {
        ObjectNode fn = objectMapper.createObjectNode();
        fn.put("name", name).put("description", description).set("parameters", parameters);
        ObjectNode t = objectMapper.createObjectNode();
        t.put("type", "function").set("function", fn);
        return t;
    }
}
