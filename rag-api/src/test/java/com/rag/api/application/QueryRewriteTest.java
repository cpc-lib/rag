package com.rag.api.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rag.api.infrastructure.llm.LlmClient;
import com.rag.api.infrastructure.persistence.entity.ModelEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** RAG-013：Query 改写的开关、精确标识跳过、Multi-Query 解析与失败回退。 */
@ExtendWith(MockitoExtension.class)
class QueryRewriteTest {

    @Mock
    private LlmClient llmClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private QueryRewriteService service;
    private ModelEntity cfg;

    @BeforeEach
    void setUp() {
        service = new QueryRewriteService(llmClient, objectMapper);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "maxQueries", 3);
        ReflectionTestUtils.setField(service, "timeoutSeconds", 5);
        cfg = new ModelEntity();
        cfg.setBaseUrl("http://llm");
        cfg.setApiKey("key");
        cfg.setModel("qwen");
    }

    @Test
    void disabledReturnsNoExtraQueries() {
        ReflectionTestUtils.setField(service, "enabled", false);

        assertTrue(service.expand(cfg, history(), "它的限制是什么").isEmpty());
        verifyNoInteractions(llmClient);
    }

    @Test
    void firstTurnSkipsRewrite() {
        assertTrue(service.expand(cfg, List.of(), "它的限制是什么").isEmpty());
        verifyNoInteractions(llmClient);
    }

    @Test
    void exactIdentifierSkipsRewrite() {
        assertTrue(service.expand(cfg, history(), "错误码 ERR_4012 是什么意思").isEmpty());
        verifyNoInteractions(llmClient);
    }

    @Test
    void rewritesFollowUpQuestionIntoStandaloneQueries() {
        when(llmClient.chatOnce(anyString(), anyString(), anyString(), anyList(), isNull(), any(), anyInt()))
                .thenReturn(message("{\"queries\":[\"RAG 平台的存储限制是什么\",\"RAG 平台容量上限\"]}"));

        List<String> queries = service.expand(cfg, history(), "它的限制是什么");

        assertEquals(List.of("RAG 平台的存储限制是什么", "RAG 平台容量上限"), queries);
    }

    @Test
    void limitsAndDeduplicatesQueries() {
        ReflectionTestUtils.setField(service, "maxQueries", 2);
        when(llmClient.chatOnce(anyString(), anyString(), anyString(), anyList(), isNull(), any(), anyInt()))
                .thenReturn(message("{\"queries\":[\"A\",\"A\",\"B\",\"C\"]}"));

        assertEquals(List.of("A", "B"), service.expand(cfg, history(), "继续"));
    }

    @Test
    void invalidResponseFallsBackToOriginalQuery() {
        when(llmClient.chatOnce(anyString(), anyString(), anyString(), anyList(), isNull(), any(), anyInt()))
                .thenReturn(message("这不是 JSON"));

        assertTrue(service.expand(cfg, history(), "继续说明").isEmpty());
    }

    @Test
    void llmFailureFallsBackToOriginalQuery() {
        when(llmClient.chatOnce(anyString(), anyString(), anyString(), anyList(), isNull(), any(), anyInt()))
                .thenThrow(new RuntimeException("timeout"));

        assertTrue(service.expand(cfg, history(), "继续说明").isEmpty());
    }

    private List<LlmClient.LlmMessage> history() {
        return List.of(new LlmClient.LlmMessage("user", "RAG 平台有哪些限制？"),
                new LlmClient.LlmMessage("assistant", "主要限制在存储与并发上。"));
    }

    private JsonNode message(String content) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("content", content);
        return node;
    }
}
