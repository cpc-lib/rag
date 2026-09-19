package com.rag.api.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Tavily 联网搜索。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TavilyClient {

    private final ObjectMapper objectMapper;
    private final WebClient webClient = WebClient.builder().build();

    public String search(String apiKey, String query) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("api_key", apiKey);
            body.put("query", query);
            body.put("max_results", 5);
            body.put("search_depth", "basic");
            JsonNode resp = webClient.post()
                    .uri("https://api.tavily.com/search")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(15));
            StringBuilder sb = new StringBuilder();
            int i = 1;
            JsonNode results = resp == null ? objectMapper.createArrayNode() : resp.path("results");
            for (JsonNode r : results) {
                sb.append('[').append(i++).append("] ").append(r.path("title").asText())
                        .append(" | ").append(r.path("url"))
                        .append(" | ").append(r.path("content").asText("").replace('\n', ' '))
                        .append('\n');
            }
            if (sb.isEmpty()) {
                return "联网搜索无结果";
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Tavily 搜索失败: {}", e.getMessage());
            return "联网搜索失败：" + e.getMessage();
        }
    }
}
