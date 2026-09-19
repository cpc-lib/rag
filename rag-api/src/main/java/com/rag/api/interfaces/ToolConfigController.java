package com.rag.api.interfaces;

import com.rag.api.application.ToolConfigService;
import com.rag.api.common.ApiResult;
import com.rag.api.common.TenantContext;
import com.rag.api.interfaces.dto.Dtos;
import com.rag.api.interfaces.security.AuthGuard;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/tool-config")
@RequiredArgsConstructor
public class ToolConfigController {

    private final ToolConfigService toolConfigService;

    @GetMapping
    public ApiResult<Map<String, Object>> get() {
        AuthGuard.requireTenantAdmin();
        return ApiResult.ok(toolConfigService.mask(TenantContext.require().tenantId()));
    }

    @PutMapping
    public ApiResult<Map<String, Object>> update(@RequestBody @Valid Dtos.ToolConfigReq req) {
        AuthGuard.requireTenantAdmin();
        toolConfigService.update(TenantContext.require().tenantId(), req);
        return ApiResult.ok(toolConfigService.mask(TenantContext.require().tenantId()));
    }
}
