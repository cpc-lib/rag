package com.rag.api.interfaces;

import com.rag.api.application.ToolConfigService;
import com.rag.api.common.ApiResult;
import com.rag.api.common.TenantContext;
import com.rag.api.interfaces.dto.Dtos;
import com.rag.api.interfaces.security.AuthGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tools")
@RequiredArgsConstructor
public class ToolController {

    private final ToolConfigService toolConfigService;

    /** 工具目录 + 租户开关 + Key 状态（租户管理员）。 */
    @GetMapping
    public ApiResult<List<Dtos.ToolCatalogView>> catalog() {
        AuthGuard.requireTenantAdmin();
        return ApiResult.ok(toolConfigService.catalog(TenantContext.require().tenantId()));
    }
}
