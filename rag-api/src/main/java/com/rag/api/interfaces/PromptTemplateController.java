package com.rag.api.interfaces;

import com.rag.api.application.PromptTemplateService;
import com.rag.api.application.UserManageService;
import com.rag.api.common.ApiResult;
import com.rag.api.common.TenantContext;
import com.rag.api.infrastructure.persistence.entity.PromptTemplateEntity;
import com.rag.api.interfaces.dto.Dtos;
import com.rag.api.interfaces.security.AuthGuard;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/prompts")
@RequiredArgsConstructor
public class PromptTemplateController {

    private final PromptTemplateService promptTemplateService;
    private final UserManageService userManageService;

    @GetMapping
    public ApiResult<List<PromptTemplateEntity>> list(@RequestParam(required = false) Long kbId) {
        // kbId 非空（问答页）：按知识库 + 用户授权过滤；否则为管理页/授权弹窗返回租户全部
        return ApiResult.ok(kbId == null
                ? promptTemplateService.list(TenantContext.require().tenantId())
                : userManageService.visiblePrompts(kbId));
    }

    @PostMapping
    public ApiResult<PromptTemplateEntity> create(@RequestBody @Valid Dtos.PromptReq req) {
        AuthGuard.requireTenantAdmin();
        return ApiResult.ok(promptTemplateService.create(TenantContext.require().tenantId(), req));
    }

    @PutMapping("/{id}")
    public ApiResult<PromptTemplateEntity> update(@PathVariable long id,
                                                  @RequestBody @Valid Dtos.PromptReq req) {
        AuthGuard.requireTenantAdmin();
        return ApiResult.ok(promptTemplateService.update(TenantContext.require().tenantId(), id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable long id) {
        AuthGuard.requireTenantAdmin();
        promptTemplateService.delete(TenantContext.require().tenantId(), id);
        return ApiResult.ok(null);
    }
}
