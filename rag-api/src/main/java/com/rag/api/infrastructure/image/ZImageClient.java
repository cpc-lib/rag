package com.rag.api.infrastructure.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rag.api.common.BizException;
import com.rag.api.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;

/**
 * 阿里云 Z-Image 文生图客户端（同步 HTTP）：
 * POST /api/v1/services/aigc/multimodal-generation/generation，
 * 解析 output.choices[0].message.content[*].image 取图。
 */
@Component
@RequiredArgsConstructor
public class ZImageClient {

    private static final String PATH = "/api/v1/services/aigc/multimodal-generation/generation";

    private final ObjectMapper objectMapper;

    private final WebClient webClient = WebClient.builder()
            .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
            .build();

    public String generate(String baseUrl, String apiKey, String model,
                           String prompt, String size, Long seed) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        ObjectNode input = body.putObject("input");
        ArrayNode messages = input.putArray("messages");
        ObjectNode msg = messages.addObject();
        msg.put("role", "user");
        msg.putArray("content").addObject().put("text", prompt);
        ObjectNode params = body.putObject("parameters");
        params.put("size", size);
        if (seed != null) {
            params.put("seed", seed);
        }

        JsonNode resp = webClient.post()
                .uri(normalizeBase(baseUrl) + PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(JsonNode.class)
                        .defaultIfEmpty(objectMapper.createObjectNode())
                        .flatMap(j -> Mono.error(new BizException(ErrorCode.UPSTREAM,
                                "文生图失败：" + j.path("code").asText("UPSTREAM")
                                        + " " + j.path("message").asText(r.statusCode().toString())))))
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(120));

        JsonNode content = resp.path("output").path("choices").path(0)
                .path("message").path("content");
        for (JsonNode item : content) {
            if (item.hasNonNull("image")) {
                return item.get("image").asText();
            }
        }
        throw new BizException(ErrorCode.UPSTREAM, "文生图响应中未包含图片");
    }

    /**
     * 下载生成图片字节（DashScope URL 24h 有效）。
     * 必须传 URI 对象：URL 的 Signature 查询参数含 %2F/%3D 等已编码字符，
     * WebClient 的 .uri(String) 会将其再次编码（%252F），导致 OSS 签名失效返回 403。
     */
    public byte[] download(String imageUrl) {
        return webClient.get().uri(URI.create(imageUrl))
                .retrieve()
                .bodyToMono(byte[].class)
                .block(Duration.ofSeconds(60));
    }

    /**
     * 归一化 Base URL：去掉末尾斜杠，以及用户容易误填的 OpenAI 兼容模式
     * 后缀（/compatible-mode/v1、/api/v1），随后拼接固定路径。
     */
    private String normalizeBase(String url) {
        String s = url.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.replaceAll("(/compatible-mode/v1|/compatible-mode|/api/v1)$", "");
    }
}
