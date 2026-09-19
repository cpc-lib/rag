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
import java.util.Base64;

/**
 * 视觉大模型（OpenAI 兼容多模态 chat）：为图表/图片生成图文描述。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisionClient {

    private final ObjectMapper objectMapper;

    @Value("${rag.llm.timeout-seconds:60}")
    private int timeoutSeconds;

    public boolean isConfigured(String baseUrl, String model) {
        return baseUrl != null && !baseUrl.isBlank() && model != null && !model.isBlank();
    }

    public String describe(String baseUrl, String apiKey, String model, byte[] image, String mime) {
        try {
            String dataUri = "data:" + (mime == null ? "image/png" : mime) + ";base64,"
                    + Base64.getEncoder().encodeToString(image);
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("max_tokens", 1024);
            ArrayNode messages = body.putArray("messages");
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            ArrayNode content = user.putArray("content");
            content.addObject().put("type", "text")
                    .put("text", "请详细描述这张图片/图表中的文字与数据信息，用于知识库检索。");
            ObjectNode imagePart = content.addObject();
            imagePart.put("type", "image_url");
            imagePart.putObject("image_url").put("url", dataUri);

            HttpClient httpClient = HttpClient.create()
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                    .responseTimeout(Duration.ofSeconds(timeoutSeconds));
            JsonNode resp = WebClient.builder()
                    .baseUrl(baseUrl.trim().replaceAll("/+$", ""))
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + (apiKey == null ? "" : apiKey))
                    .clientConnector(new ReactorClientHttpConnector(httpClient))
                    .build()
                    .post().uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(timeoutSeconds));
            String text = resp.path("choices").path(0).path("message").path("content").asText(null);
            return text == null || text.isBlank() ? null : text;
        } catch (Exception e) {
            log.warn("视觉模型调用失败: {}", e.getMessage());
            return null;
        }
    }
}
