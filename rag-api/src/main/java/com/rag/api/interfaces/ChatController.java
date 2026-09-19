package com.rag.api.interfaces;

import com.rag.api.application.ChatOrchestrator;
import com.rag.api.application.UserManageService;
import com.rag.api.common.ErrorCode;
import com.rag.api.common.TenantContext;
import com.rag.api.interfaces.dto.Dtos;
import com.rag.api.interfaces.guard.SseConnectionGuard;
import com.rag.api.infrastructure.persistence.entity.TenantEntity;
import com.rag.api.infrastructure.persistence.mapper.TenantMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SSE 流式问答（spec 5）：建连限流 → 异步编排 → 五类事件。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatOrchestrator orchestrator;
    private final UserManageService userManageService;
    private final SseConnectionGuard sseGuard;
    private final TenantMapper tenantMapper;

    @Value("${rag.chat.sse-timeout-seconds:60}")
    private int sseTimeoutSeconds;

    private final ExecutorService chatExecutor = new ThreadPoolExecutor(
            8, 64, 60L, TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, "chat-sse-" + COUNTER.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody @Valid Dtos.ChatStreamReq req) {
        TenantContext.Session session = TenantContext.require();
        userManageService.requireMenu("chat");
        TenantEntity tenant = tenantMapper.selectById(session.tenantId());
        int maxConn = tenant == null || tenant.getMaxSseConnections() == null ? 20 : tenant.getMaxSseConnections();
        sseGuard.acquire(session.tenantId(), maxConn);

        SseEmitter emitter = new SseEmitter(sseTimeoutSeconds * 1000L);
        Runnable release = () -> sseGuard.release(session.tenantId());
        emitter.onCompletion(release);
        emitter.onTimeout(() -> {
            release.run();
            emitter.complete();
        });
        emitter.onError(e -> release.run());

        chatExecutor.execute(() -> {
            TenantContext.set(session);
            try {
                orchestrator.stream(session, req.kbId(), req.question(), req.promptId(),
                        req.sessionId(), emitter);
            } catch (Exception e) {
                log.error("问答编排异常", e);
                emitter.complete();
            } finally {
                TenantContext.clear();
            }
        });
        return emitter;
    }
}
