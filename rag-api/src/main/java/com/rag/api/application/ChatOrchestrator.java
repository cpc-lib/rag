package com.rag.api.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.api.common.BizException;
import com.rag.api.common.TenantContext;
import com.rag.api.infrastructure.llm.LlmClient;
import com.rag.api.infrastructure.persistence.entity.ChatMessageEntity;
import com.rag.api.infrastructure.persistence.entity.ChatSessionEntity;
import com.rag.api.infrastructure.persistence.entity.KnowledgeBaseEntity;
import com.rag.api.infrastructure.persistence.entity.ModelEntity;
import com.rag.api.infrastructure.persistence.entity.PromptTemplateEntity;
import com.rag.api.infrastructure.persistence.entity.ToolConfigEntity;
import com.rag.api.infrastructure.persistence.mapper.ChatMessageMapper;
import com.rag.api.infrastructure.tool.TavilyClient;
import com.rag.api.infrastructure.tool.WeatherClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE 流式问答编排：
 * session → search_start → search_result(引用/工具) → message* → done|error。
 * 工具按标准 function-calling 实现：预判命中后，assistant 的 tool_calls 与 role=tool
 * 结果消息按协议回传，模型可连续调用（最多 {@link #MAX_TOOL_ROUNDS} 轮），最终答案流式生成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatOrchestrator {

    /** 单次问答中模型最多连续调用工具的轮数（预判为第 1 轮）。 */
    private static final int MAX_TOOL_ROUNDS = 2;

    private final ModelService modelService;
    private final ChatSessionService chatSessionService;
    private final UserManageService userManageService;
    private final PromptTemplateService promptTemplateService;
    private final ToolConfigService toolConfigService;
    private final RetrievalService retrievalService;
    private final QuotaService quotaService;
    private final LlmClient llmClient;
    private final WeatherClient weatherClient;
    private final TavilyClient tavilyClient;
    private final ChatMessageMapper chatMessageMapper;
    private final ObjectMapper objectMapper;

    @Value("${rag.llm.timeout-seconds:60}")
    private int timeoutSeconds;

    public void stream(TenantContext.Session session, long kbId, String question, Long promptId,
                       Long sessionId, SseEmitter emitter) {
        StringBuilder answer = new StringBuilder();
        List<RetrievalService.Citation> citations = new ArrayList<>();
        AtomicLong promptTokens = new AtomicLong();
        AtomicLong completionTokens = new AtomicLong();
        Long sid = null;
        try {
            ModelEntity cfg = modelService.requireEnabled(session.tenantId(), ModelService.CHAT);
            if (cfg.getBaseUrl() == null || cfg.getModel() == null) {
                throw BizException.badRequest("启用的对话模型缺少 Base URL 或模型名称");
            }
            KnowledgeBaseEntity kb = userManageService.requireKbAccess(kbId);

            // 会话：首轮发问自动创建（标题取问题前 40 字），继续会话则校验归属并取全部历史
            ChatSessionEntity chatSession = sessionId == null
                    ? chatSessionService.create(session.tenantId(), session.userId(), kbId, titleOf(question))
                    : chatSessionService.requireOwned(session.tenantId(), session.userId(), sessionId);
            sid = chatSession.getId();
            List<LlmClient.LlmMessage> history =
                    chatSessionService.historyMessages(session.tenantId(), session.userId(), sid);
            send(emitter, "session", Map.of("sessionId", sid));

            // 提示词模板（必须属于当前知识库且用户已获授权）
            PromptTemplateEntity template = promptId == null
                    ? promptTemplateService.getDefault(session.tenantId(), kbId)
                    : promptTemplateService.get(session.tenantId(), promptId);
            if (template.getKbId() != kbId) {
                throw BizException.badRequest("提示词模板与知识库不匹配");
            }
            userManageService.requirePrompt(template.getId());

            // 工具：租户总开关 + 用户个人授权（管理员不限）
            ToolConfigEntity toolsCfg = toolConfigService.getByTenant(session.tenantId());
            JsonNode toolDefs = buildToolDefs(session, toolsCfg);

            // Token 配额
            quotaService.checkTokenQuota(session.tenantId(), question.length() / 2);

            LlmClient.ChatParams params = new LlmClient.ChatParams(
                    cfg.getTemperature(), cfg.getTopP(), cfg.getMaxTokens());
            LlmClient.ChatParams intentParams = new LlmClient.ChatParams(
                    cfg.getTemperature(), cfg.getTopP(), Math.min(cfg.getMaxTokens(), 512));

            // 预判：这句话是否需要工具。失败静默降级为知识库问答
            boolean toolHit = false;
            if (toolDefs != null) {
                try {
                    JsonNode intent = llmClient.chatOnce(cfg.getBaseUrl(), cfg.getApiKey(), cfg.getModel(),
                            List.of(new LlmClient.LlmMessage("user", question)),
                            toolDefs, intentParams, timeoutSeconds);
                    JsonNode intentCalls = intent.path("tool_calls");
                    toolHit = intentCalls.isArray() && !intentCalls.isEmpty();
                    if (toolHit) {
                        runToolLoop(cfg, template, question, toolDefs, intentParams, params,
                                intent, toolsCfg, answer, promptTokens, completionTokens, emitter);
                    }
                } catch (Exception e) {
                    log.warn("工具调用失败（降级为纯知识库问答）: {}", e.getMessage());
                    toolHit = false;
                }
            }

            if (!toolHit) {
                // 知识库路径：混合检索 → 模板渲染 → 流式生成
                send(emitter, "search_start", Map.of("stage", "kb"));
                RetrievalService.RetrievalResult rr = retrievalService.retrieve(session, kbId, question);
                citations = rr.citations();
                send(emitter, "search_result", Map.of("citations", citations));

                String systemPrompt = promptTemplateService.render(
                        template, buildMaterial(citations), "", question);
                List<LlmClient.LlmMessage> messages = new ArrayList<>();
                messages.add(new LlmClient.LlmMessage("system", systemPrompt));
                messages.addAll(history);
                messages.add(new LlmClient.LlmMessage("user", question));
                runFinalStream(cfg, messages, null, null, params, answer,
                        promptTokens, completionTokens, emitter);
            }

            long usage = promptTokens.get() + completionTokens.get();
            send(emitter, "done", Map.of("usage", Map.of(
                    "promptTokens", promptTokens.get(), "completionTokens", completionTokens.get())));
            persist(session, sid, kbId, question, answer.toString(), citations, (int) usage);
            emitter.complete();
        } catch (BizException e) {
            log.warn("问答中断 tenant={} kb={}: {}", session.tenantId(), kbId, e.getMessage());
            safeSendError(emitter, e.getErrorCode().name(), e.getMessage());
            persist(session, sid, kbId, question, answer.toString(), citations, 0);
            emitter.complete();
        } catch (Exception e) {
            log.error("问答异常 tenant={} kb={}", session.tenantId(), kbId, e);
            safeSendError(emitter, "LLM_ERROR", "生成过程发生异常: " + e.getMessage());
            persist(session, sid, kbId, question, answer.toString(), citations, 0);
            emitter.complete();
        }
    }

    /**
     * 标准 function-calling 工具循环（跳过知识库检索）：
     * system + user 起步，追加预判的 assistant tool_calls，执行工具后以 role=tool 回传结果；
     * 模型若在非流式轮直接给出完整答案则直接采用，否则最终强制不调工具、流式生成。
     */
    private void runToolLoop(ModelEntity cfg, PromptTemplateEntity template, String question,
                             JsonNode toolDefs, LlmClient.ChatParams intentParams,
                             LlmClient.ChatParams params, JsonNode intent, ToolConfigEntity toolsCfg,
                             StringBuilder answer, AtomicLong promptTokens, AtomicLong completionTokens,
                             SseEmitter emitter) {
        String systemPrompt = promptTemplateService.render(
                template, buildMaterial(List.of()), "", question);
        List<LlmClient.LlmMessage> messages = new ArrayList<>();
        messages.add(new LlmClient.LlmMessage("system", systemPrompt));
        messages.add(new LlmClient.LlmMessage("user", question));

        // 预判的 assistant 消息（含 tool_calls）+ 工具执行结果
        messages.add(assistantFromResponse(intent));
        executeCalls(intent.path("tool_calls"), toolsCfg, messages, emitter);

        int round = 1;
        boolean answered = false;
        while (round < MAX_TOOL_ROUNDS) {
            JsonNode resp;
            try {
                resp = llmClient.chatOnce(cfg.getBaseUrl(), cfg.getApiKey(), cfg.getModel(),
                        messages, toolDefs, intentParams, timeoutSeconds);
            } catch (Exception e) {
                log.warn("工具循环第{}轮调用失败，直接生成最终答案: {}", round + 1, e.getMessage());
                break;
            }
            messages.add(assistantFromResponse(resp));
            JsonNode calls = resp.path("tool_calls");
            if (!calls.isArray() || calls.isEmpty()) {
                String content = textOrNull(resp.get("content"));
                if (content != null && !content.isEmpty()) {
                    answer.append(content);
                    send(emitter, "message", Map.of("delta", content));
                    answered = true;
                }
                break;
            }
            round++;
            executeCalls(calls, toolsCfg, messages, emitter);
        }

        if (!answered) {
            // 带上工具定义并强制不调用，保证含 tool 消息的历史合法，最终答案流式输出
            runFinalStream(cfg, messages, toolDefs, "none", params, answer,
                    promptTokens, completionTokens, emitter);
        }
    }

    /** 执行一轮内的全部工具调用，结果以 role=tool 消息追加。 */
    private void executeCalls(JsonNode toolCalls, ToolConfigEntity toolsCfg,
                              List<LlmClient.LlmMessage> messages, SseEmitter emitter) {
        for (JsonNode call : toolCalls) {
            String id = call.path("id").asText();
            String name = call.path("function").path("name").asText();
            JsonNode args = safeParse(call.path("function").path("arguments").asText("{}"));
            String result = switch (name) {
                case "query_weather" -> {
                    String location = args.path("location").asText();
                    int days = args.path("days").asInt(1);
                    send(emitter, "search_start", Map.of("stage", "tool", "tool", "weather"));
                    yield weatherClient.query(location, days);
                }
                case "tavily_search" -> {
                    String query = args.path("query").asText();
                    send(emitter, "search_start", Map.of("stage", "tool", "tool", "tavily"));
                    yield tavilyClient.search(toolsCfg.getTavilyApiKey(), query);
                }
                default -> "工具不存在：" + name;
            };
            messages.add(new LlmClient.LlmMessage("tool", result, id, null));
        }
    }

    /** 最终生成（流式），tools/toolChoice 非空时随请求携带；最后一帧的 usage 写入 token 统计。 */
    private void runFinalStream(ModelEntity cfg, List<LlmClient.LlmMessage> messages,
                                JsonNode tools, String toolChoice, LlmClient.ChatParams params,
                                StringBuilder answer, AtomicLong promptTokens, AtomicLong completionTokens,
                                SseEmitter emitter) {
        llmClient.chatStream(cfg.getBaseUrl(), cfg.getApiKey(), cfg.getModel(),
                        messages, params, tools, toolChoice, timeoutSeconds)
                .doOnNext(delta -> {
                    if (delta.content() != null && !delta.content().isEmpty()) {
                        answer.append(delta.content());
                        send(emitter, "message", Map.of("delta", delta.content()));
                    }
                    if (delta.promptTokens() != null) {
                        promptTokens.set(delta.promptTokens());
                        completionTokens.set(delta.completionTokens());
                    }
                })
                .blockLast();
    }

    /** 按开关与用户授权组装可用工具定义；都不可用返回 null。 */
    private JsonNode buildToolDefs(TenantContext.Session session, ToolConfigEntity tools) {
        boolean weatherOn = userManageService.toolAllowed("weather",
                Boolean.TRUE.equals(tools.getWeatherEnabled()));
        boolean tavilyOn = userManageService.toolAllowed("tavily",
                Boolean.TRUE.equals(tools.getTavilyEnabled())
                        && tools.getTavilyApiKey() != null && !tools.getTavilyApiKey().isBlank());
        if (!weatherOn && !tavilyOn) {
            return null;
        }
        List<JsonNode> defs = new ArrayList<>();
        if (weatherOn) {
            defs.add(llmClient.weatherTool());
        }
        if (tavilyOn) {
            defs.add(llmClient.tavilyTool());
        }
        return objectMapper.valueToTree(defs);
    }

    /** 把模型返回的 assistant 消息转为可回传的 LlmMessage（含 tool_calls 时 content 可为 null）。 */
    private LlmClient.LlmMessage assistantFromResponse(JsonNode message) {
        JsonNode callsNode = message.path("tool_calls");
        if (callsNode.isArray() && !callsNode.isEmpty()) {
            List<LlmClient.ToolCall> calls = new ArrayList<>();
            for (JsonNode tc : callsNode) {
                calls.add(new LlmClient.ToolCall(tc.path("id").asText(),
                        tc.path("function").path("name").asText(),
                        tc.path("function").path("arguments").asText("{}")));
            }
            return new LlmClient.LlmMessage("assistant", textOrNull(message.get("content")), null, calls);
        }
        String content = textOrNull(message.get("content"));
        return new LlmClient.LlmMessage("assistant", content == null ? "" : content);
    }

    /** 节点为 null/显式 null 时返回 null，否则返回文本。 */
    private String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    /** 知识库召回内容块（注入模板的 {{资料}}）。 */
    private String buildMaterial(List<RetrievalService.Citation> citations) {
        if (citations.isEmpty()) {
            return "（知识库未召回相关内容）";
        }
        StringBuilder sb = new StringBuilder();
        for (RetrievalService.Citation c : citations) {
            sb.append('[').append(c.seq()).append("] （").append(c.documentName())
                    .append(" 第").append(c.page() + 1).append("页）\n")
                    .append(c.content()).append("\n\n");
        }
        return sb.toString().stripTrailing();
    }

    private void persist(TenantContext.Session session, Long sessionId, long kbId,
                         String question, String answer,
                         List<RetrievalService.Citation> citations, int tokenUsage) {
        try {
            ChatMessageEntity msg = new ChatMessageEntity();
            msg.setTenantId(session.tenantId());
            msg.setUserId(session.userId());
            msg.setSessionId(sessionId);
            msg.setKbId(kbId);
            msg.setQuestion(question);
            msg.setAnswer(answer);
            msg.setCitations(objectMapper.writeValueAsString(citations));
            msg.setTokenUsage(tokenUsage);
            chatMessageMapper.insert(msg);
            if (sessionId != null) {
                chatSessionService.touch(sessionId);
            }
        } catch (Exception e) {
            log.warn("问答留痕失败: {}", e.getMessage());
        }
    }

    /** 会话标题：问题去除首尾空白后取前 40 字。 */
    private String titleOf(String question) {
        String q = question.strip();
        return q.length() <= 40 ? q : q.substring(0,40);
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event)
                    .data(objectMapper.writeValueAsString(data),
                            org.springframework.http.MediaType.APPLICATION_JSON));
        } catch (IllegalStateException ignored) {
            // 客户端已断开
        } catch (Exception e) {
            throw new BizException(com.rag.api.common.ErrorCode.INTERNAL, "SSE 推送失败: " + e.getMessage());
        }
    }

    private void safeSendError(SseEmitter emitter, String code, String message) {
        try {
            send(emitter, "error", Map.of("code", code, "message", message));
        } catch (Exception ignored) {
        }
    }

    private JsonNode safeParse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }
}
