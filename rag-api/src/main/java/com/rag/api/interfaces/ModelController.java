package com.rag.api.interfaces;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rag.api.application.ModelService;
import com.rag.api.common.ApiResult;
import com.rag.api.interfaces.dto.Dtos;
import com.rag.api.interfaces.security.AuthGuard;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/models")
@RequiredArgsConstructor
public class ModelController {

    private final ModelService modelService;

    @GetMapping
    public ApiResult<Page<Dtos.ModelView>> page(@RequestParam(defaultValue = "1") long current,
                                                @RequestParam(defaultValue = "20") long size,
                                                @RequestParam(required = false) String type) {
        AuthGuard.requireTenantAdmin();
        return ApiResult.ok(modelService.page(current, size, type));
    }

    @GetMapping("/{id}")
    public ApiResult<Dtos.ModelView> get(@PathVariable long id) {
        AuthGuard.requireTenantAdmin();
        return ApiResult.ok(modelService.getView(id));
    }

    @PostMapping
    public ApiResult<Dtos.ModelView> create(@RequestBody @Valid Dtos.ModelReq req) {
        AuthGuard.requireTenantAdmin();
        return ApiResult.ok(modelService.create(req));
    }

    @PutMapping("/{id}")
    public ApiResult<Dtos.ModelView> update(@PathVariable long id,
                                            @RequestBody @Valid Dtos.ModelReq req) {
        AuthGuard.requireTenantAdmin();
        return ApiResult.ok(modelService.update(id, req));
    }

    @PostMapping("/{id}/enable")
    public ApiResult<Dtos.ModelView> enable(@PathVariable long id) {
        AuthGuard.requireTenantAdmin();
        return ApiResult.ok(modelService.enable(id));
    }

    @PostMapping("/{id}/disable")
    public ApiResult<Dtos.ModelView> disable(@PathVariable long id) {
        AuthGuard.requireTenantAdmin();
        return ApiResult.ok(modelService.disable(id));
    }
}
