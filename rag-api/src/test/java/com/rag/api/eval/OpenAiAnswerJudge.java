package com.rag.api.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.List;

/**
 * RAG-009 在线判分器：调用 OpenAI 兼容 CHAT 接口，用 LLM-as-judge 给答案打 0~1 分。
 *
 * <p>只输出 JSON（{@code {"score":0.8}}），模型或网关异常时返回 {@link Double#NaN}，
 * 由评测报告记录失败样例，避免一条异常中断整批评测。</p>
 */
@Slf4j
public class OpenAiAnswerJudge implements AnswerJudge {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_CONTEXT_CHARS = 2000;
    private static final int MAX_TOTAL_CONTEXT_CHARS = 8000;

    private final WebClient client;
    private final String model;
    private final Duration timeout;

    public OpenAiAnswerJudge(String baseUrl, String apiKey, String model, int timeoutSeconds) {
        this.model = model;
        this.timeout = Duration.ofSeconds(Math.max(5, timeoutSeconds));
        this.client = WebClient.builder()
                .baseUrl(stripSlash(baseUrl))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create(ConnectionProvider.newConnection())
                                .responseTimeout(timeout)))
                .build();
    }

    @Override
    public double faithfulness(String query, String answer, List<String> contexts) {
        String system = "你是 RAG 答案评测员。判断【答案】中的每个事实是否都能被【上下文】直接支撑。"
                + "存在无法支撑的事实、与上下文矛盾或凭空补充时扣分；完全有依据给 1。"
                + "只输出 JSON：{\"score\": 0 到 1 的小数}";
        String user = "【问题】\n" + query + "\n\n【上下文】\n" + joinContexts(contexts)
                + "\n\n【答案】\n" + answer;
        return score(system, user);
    }

    @Override
    public double answerRelevance(String query, String answer) {
        String system = "你是 RAG 答案评测员。判断【答案】是否直接回应了【问题】。"
                + "答非所问、只重复问题或拒答但上下文有答案时扣分；直接且完整给 1。"
                + "只输出 JSON：{\"score\": 0 到 1 的小数}";
        String user = "【问题】\n" + query + "\n\n【答案】\n" + answer;
        return score(system, user);
    }

    /** 单次判分：解析失败、超时、鉴权错误统一返回 NaN，交由报告统计。 */
    private double score(String system, String user) {
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("model", model);
            body.put("temperature", 0);
            ArrayNode messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", system);
            messages.addObject().put("role", "user").put("content", user);

            JsonNode resp = client.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(timeout.plusSeconds(10));
            String content = resp == null ? ""
                    : resp.path("choices").path(0).path("message").path("content").asText("");
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start < 0 || end <= start) {
                log.warn("答案判分返回非 JSON: {}", content);
                return Double.NaN;
            }
            double value = MAPPER.readTree(content.substring(start, end + 1)).path("score").asDouble(Double.NaN);
            return Double.isNaN(value) ? Double.NaN : Math.max(0, Math.min(1, value));
        } catch (Exception e) {
            log.warn("答案判分失败: {}", e.getMessage());
            return Double.NaN;
        }
    }

    private String joinContexts(List<String> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return "（无检索上下文）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < contexts.size() && sb.length() < MAX_TOTAL_CONTEXT_CHARS; i++) {
            String text = contexts.get(i);
            if (text == null) {
                continue;
            }
            sb.append("[").append(i + 1).append("] ")
                    .append(text.length() > MAX_CONTEXT_CHARS ? text.substring(0, MAX_CONTEXT_CHARS) : text)
                    .append('\n');
        }
        return sb.toString();
    }

    private String stripSlash(String url) {
        String base = url == null ? "" : url.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }
}
