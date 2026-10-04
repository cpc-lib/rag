package com.rag.api.interfaces;

import com.rag.api.application.ChatSessionService;
import com.rag.api.common.ApiResult;
import com.rag.api.common.TenantContext;
import com.rag.api.interfaces.dto.Dtos;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 问答会话：历史列表、详情回看与删除（仅可访问本人的会话）。 */
@RestController
@RequestMapping("/api/v1/chat-sessions")
@RequiredArgsConstructor
public class ChatSessionController {

    private final ChatSessionService chatSessionService;

    @GetMapping
    public ApiResult<List<Dtos.ChatSessionView>> list() {
        TenantContext.Session s = TenantContext.require();
        return ApiResult.ok(chatSessionService.list(s.tenantId(), s.userId()));
    }

    @GetMapping("/{id}")
    public ApiResult<Dtos.ChatSessionDetail> get(@PathVariable long id) {
        TenantContext.Session s = TenantContext.require();
        return ApiResult.ok(chatSessionService.detail(s.tenantId(), s.userId(), id));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable long id) {
        TenantContext.Session s = TenantContext.require();
        chatSessionService.delete(s.tenantId(), s.userId(), id);
        return ApiResult.ok(null);
    }
}
