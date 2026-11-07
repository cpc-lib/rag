package com.rag.api.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.api.infrastructure.llm.LlmClient;
import com.rag.api.infrastructure.persistence.entity.ModelEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * RAG-013 Query Rewrite / Multi-Query。
 * 仅多轮对话时调用一次低成本非流式模型，把指代型问题改写为独立检索 Query；
 * 精确编号、错误码、接口路径、代码符号不改写；失败/超时/解析异常一律回退原始 Query。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryRewriteService {

    private static final String SYSTEM_PROMPT = """
            你是一个检索查询改写器。结合对话历史，把当前问题改写为不依赖上下文的独立检索查询。
            若问题存在多种合理表述，可给出最多 %d 条互补查询，避免同义重复。
            只输出 JSON：{"queries":["..."]}，不要解释，不要输出 Markdown。
            保留订单号、错误码、接口名等精确标识，不要翻译。
            """;

    /** 精确标识类问题不进入改写：UUID、URL/接口路径、错误码、snake_case、camelCase、长数字。 */
    private static final Pattern EXACT_TOKEN = Pattern.compile(
            "(?i).*(\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b"
                    + "|https?://\\S+|/api/\\S*"
                    + "|\\b[a-z]{2,}[-_]?\\d{2,}\\b"
                    + "|[a-z0-9]+_[a-z0-9_]+"
                    + "|\\b[a-z]+[A-Z][a-zA-Z0-9]*\\b"
                    + "|\\d{6,}).*");

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    @Value("${rag.query-rewrite.enabled:false}")
    private boolean enabled;
    @Value("${rag.query-rewrite.max-queries:3}")
    private int maxQueries;
    @Value("${rag.query-rewrite.timeout-seconds:8}")
    private int timeoutSeconds;

    /** 返回改写/扩展后的检索 Query；空列表表示使用原始 Query。 */
    public List<String> expand(ModelEntity cfg, List<LlmClient.LlmMessage> history, String question) {
        if (!enabled || maxQueries < 1 || question == null || question.isBlank()) {
            return List.of();
        }
        // 首轮没有指代上下文，改写无收益且会增加一次模型调用
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        if (EXACT_TOKEN.matcher(question).matches()) {
            log.debug("Query 含精确标识，跳过改写: {}", question);
            return List.of();
        }
        try {
            List<LlmClient.LlmMessage> messages = List.of(
                    new LlmClient.LlmMessage("system", SYSTEM_PROMPT.formatted(maxQueries)),
                    new LlmClient.LlmMessage("user", "对话历史：\n" + recentContext(history)
                            + "\n当前问题：" + question));
            JsonNode resp = llmClient.chatOnce(cfg.getBaseUrl(), cfg.getApiKey(), cfg.getModel(),
                    messages, null,
                    new LlmClient.ChatParams(BigDecimal.ZERO, BigDecimal.ONE, 256),
                    timeoutSeconds);
            List<String> queries = parse(resp == null ? null : textOrNull(resp.get("content")));
            log.info("Query 改写完成 queries={}", queries);
            return queries;
        } catch (Exception e) {
            log.warn("Query 改写失败，回退原始查询: {}", e.getMessage());
            return List.of();
        }
    }

    private List<String> parse(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        try {
            JsonNode node = objectMapper.readTree(extractJson(content));
            JsonNode array = node.isArray() ? node : node.get("queries");
            if (array != null && array.isArray()) {
                for (JsonNode item : array) {
                    addQuery(result, item.asText());
                }
            }
        } catch (Exception e) {
            log.debug("Query 改写结果解析失败: {}", e.getMessage());
        }
        return result;
    }

    private void addQuery(List<String> result, String raw) {
        if (raw == null || result.size() >= maxQueries) {
            return;
        }
        String query = raw.trim();
        if (!query.isEmpty() && query.length() <= 300 && !result.contains(query)) {
            result.add(query);
        }
    }

    /** 兼容模型输出前后附带说明或 Markdown 代码块的情况。 */
    private String extractJson(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        start = content.indexOf('[');
        end = content.lastIndexOf(']');
        return start >= 0 && end > start ? content.substring(start, end + 1) : content;
    }

    private String recentContext(List<LlmClient.LlmMessage> history) {
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, history.size() - 6);
        for (int i = from; i < history.size(); i++) {
            LlmClient.LlmMessage message = history.get(i);
            if (message.content() == null || message.content().isBlank()) {
                continue;
            }
            String text = message.content().length() > 300
                    ? message.content().substring(0, 300) : message.content();
            sb.append("assistant".equals(message.role()) ? "助手" : "用户")
                    .append("：").append(text).append('\n');
        }
        return sb.toString();
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }
}
