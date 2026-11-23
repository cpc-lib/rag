package com.rag.api.infrastructure.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RAG-001/Rerank 降级：路径 404 时切换协议，鉴权失败不重试，原生路径请求体转义非 ASCII。 */
class RerankClientTest {

    private static final String NATIVE_PATH = "/api/v1/services/rerank/text-rerank/text-rerank";
    private static final String STANDARD_PATH = "/v1/rerank";
    private static final String RESPONSE = "{\"output\":{\"results\":["
            + "{\"index\":1,\"relevance_score\":0.91},{\"index\":0,\"relevance_score\":0.42}]}}";

    private HttpServer server;
    private final AtomicInteger nativeHits = new AtomicInteger();
    private final AtomicInteger standardHits = new AtomicInteger();
    private final AtomicReference<String> nativeBody = new AtomicReference<>();
    private volatile int standardStatus = 404;
    private RerankClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(NATIVE_PATH, exchange -> {
            nativeHits.incrementAndGet();
            nativeBody.set(readBody(exchange));
            respond(exchange, 200, RESPONSE);
        });
        server.createContext(STANDARD_PATH, exchange -> {
            standardHits.incrementAndGet();
            respond(exchange, standardStatus, standardStatus == 200
                    ? "{\"results\":[{\"index\":0,\"score\":0.8}]}" : "{\"error\":\"not found\"}");
        });
        server.start();
        client = new RerankClient(new ObjectMapper());
        ReflectionTestUtils.setField(client, "timeoutSeconds", 5);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void fallsBackToDashScopePathWhenBaseUrlIsV1() {
        // Base 配成 .../v1（该供应商只在原生路径暴露 rerank），标准路径 404 后应切换协议成功
        List<RerankClient.RerankHit> hits = client.rerank(base() + "/v1", "sk-test", "qwen3-rerank",
                "退款流程", List.of("支付宝退款接口", "天气预报"), 2);

        assertEquals(1, standardHits.get(), "先尝试标准路径");
        assertEquals(1, nativeHits.get(), "404 后切换原生路径");
        assertEquals(2, hits.size());
        assertEquals(1, hits.get(0).index(), "按得分降序");
        assertEquals(0.91, hits.get(0).score(), 1e-6);
        assertTrue(nativeBody.get().contains("\\u"), "原生路径请求体应转义非 ASCII: " + nativeBody.get());
    }

    @Test
    void authFailureIsNotRetried() {
        standardStatus = 401;

        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                client.rerank(base() + "/v1", "sk-bad", "qwen3-rerank",
                        "退款流程", List.of("支付宝退款接口"), 1));

        assertTrue(e.getMessage().contains("鉴权失败"), e.getMessage());
        assertEquals(0, nativeHits.get(), "鉴权失败不应重复请求其他路径");
    }

    @Test
    void compatibleModeBaseTriesNativeFirst() {
        List<RerankClient.RerankHit> hits = client.rerank(base() + "/compatible-mode/v1",
                "sk-test", "qwen3-rerank", "退款流程", List.of("支付宝退款接口"), 1);

        assertEquals(1, nativeHits.get());
        assertEquals(0, standardHits.get());
        assertEquals(1, hits.get(0).index());
    }

    @Test
    void allPathsMissingReportsAttemptedUrls() {
        // 两个候选路径都 404：原生路径上下文也返回 404
        server.removeContext(NATIVE_PATH);
        server.createContext(NATIVE_PATH, exchange -> respond(exchange, 404, "{}"));

        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                client.rerank(base() + "/v1", "sk-test", "qwen3-rerank",
                        "退款流程", List.of("支付宝退款接口"), 1));

        assertTrue(e.getMessage().contains("接口路径不存在"), e.getMessage());
        assertTrue(e.getMessage().contains(NATIVE_PATH), e.getMessage());
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
