package com.rag.api.interfaces;

import com.rag.api.application.QuotaService;
import com.rag.api.common.ApiResult;
import com.rag.api.common.TenantContext;
import com.rag.api.interfaces.security.AuthGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/quotas")
@RequiredArgsConstructor
public class QuotaController {

    private final QuotaService quotaService;

    @GetMapping("/usage")
    public ApiResult<Map<String, Object>> usage() {
        AuthGuard.requireTenantAdmin();
        return ApiResult.ok(quotaService.usage(TenantContext.require().tenantId()));
    }
}
