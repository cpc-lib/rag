package com.rag.api.infrastructure.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rag.api.common.BizException;
import com.rag.api.common.ErrorCode;
import io.netty.channel.ChannelOption;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.net.URI;
import java.time.Duration;

/**
 * 阿里云万相文生图客户端（同步 HTTP，千问平台 DashScope）：
 * POST /api/v1/services/aigc/multimodal-generation/generation（wan2.7-image-pro / wan2.7-image），
 * 解析 output.choices[0].message.content[*].image 取图。
 * <p>
 * parameters 对齐官方文档（万相-图像生成与编辑 2.7 API 参考）：
 * negative_prompt / size（1K/2K/4K 档位或 宽*高 像素）/ n / prompt_extend / watermark / seed。
 * 组图（enable_sequential）与交互式编辑（bbox_list）不适用本平台单图文生图流程，未启用。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ZImageClient {

    private static final String PATH = "/api/v1/services/aigc/multimodal-generation/generation";

    private final ObjectMapper objectMapper;

    // 4K 档 PNG 可达数十 MB，缓冲上限设为 64MB 避免下载时 DataBufferLimitException；
    // newConnection() 每次新建连接，避免复用被中间网关 RST 的空闲长连接导致 Connection reset（与 LlmClient 一致）
    private final WebClient webClient = WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(
                    HttpClient.create(ConnectionProvider.newConnection())
                            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)))
            .codecs(c -> c.defaultCodecs().maxInMemorySize(64 * 1024 * 1024))
            .build();

    /**
     * @param negativePrompt 反向提示词（negative_prompt，≤500 字符，超出上游自动截断）
     * @param size           分辨率档位（1K/2K/4K）或 宽*高 像素
     * @param promptExtend   prompt_extend：正向提示词智能改写（不影响反向提示词）
     * @param watermark      watermark：右下角"AI生成"水印
     * @param seed           随机种子 [0, 2147483647]，相同 seed+提示词结果相对稳定
     */
    public String generate(String baseUrl, String apiKey, String model,
                           String prompt, String negativePrompt, String size, Long seed,
                           Boolean promptExtend, Boolean watermark) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        ObjectNode input = body.putObject("input");
        ArrayNode messages = input.putArray("messages");
        ObjectNode msg = messages.addObject();
        msg.put("role", "user");
        msg.putArray("content").addObject().put("text", prompt);
        ObjectNode params = body.putObject("parameters");
        params.put("size", size);
        // 单图文生图流程固定 1 张（多图/组图涉及多条持久化，暂未启用）
        params.put("n", 1);
        if (negativePrompt != null && !negativePrompt.isBlank()) {
            params.put("negative_prompt", negativePrompt);
        }
        if (seed != null) {
            params.put("seed", seed);
        }
        if (promptExtend != null) {
            params.put("prompt_extend", promptExtend);
        }
        if (watermark != null) {
            params.put("watermark", watermark);
        }

        JsonNode resp = webClient.post()
                .uri(normalizeBase(baseUrl) + PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(raw -> Mono.error(new BizException(ErrorCode.UPSTREAM,
                                "文生图失败（HTTP " + r.statusCode().value() + "）：" + summarizeError(raw)))))
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(120));

        JsonNode usage = resp.path("usage");
        log.info("文生图完成 model={} requestId={} 图片数={} 实际分辨率={}",
                model, resp.path("request_id").asText(""),
                usage.path("image_count").asInt(0), usage.path("size").asText(""));

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
     * 错误响应摘要：上游错误体可能是 JSON（含 code/message）或纯文本/HTML（网关拦截），
     * 统一转字符串后优先提取 JSON 的 code/message，否则截断原文返回，保证真实原因不丢失。
     */
    private String summarizeError(String raw) {
        if (raw == null || raw.isBlank()) {
            return "上游无响应体";
        }
        try {
            JsonNode j = objectMapper.readTree(raw);
            String code = j.path("code").asText("");
            String message = j.path("message").asText("");
            if (!code.isEmpty() || !message.isEmpty()) {
                return (code + " " + message).trim();
            }
        } catch (Exception ignored) {
            // 非 JSON（如网关 text/plain / HTML），直接返回原文
        }
        String s = raw.strip();
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
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
