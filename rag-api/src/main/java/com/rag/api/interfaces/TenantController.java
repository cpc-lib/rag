package com.rag.api.interfaces;

import com.rag.api.application.TenantService;
import com.rag.api.common.ApiResult;
import com.rag.api.infrastructure.persistence.entity.TenantEntity;
import com.rag.api.interfaces.dto.Dtos;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;

    @PostMapping
    public ApiResult<Dtos.ResetAdminResp> create(@RequestBody @Valid Dtos.TenantCreateReq req) {
        return ApiResult.ok(tenantService.create(req));
    }

    @GetMapping
    public ApiResult<List<TenantEntity>> list() {
        return ApiResult.ok(tenantService.list());
    }

    @GetMapping("/{id}")
    public ApiResult<TenantEntity> get(@PathVariable String id) {
        return ApiResult.ok(tenantService.get(id));
    }

    @PutMapping("/{id}")
    public ApiResult<TenantEntity> update(@PathVariable String id,
                                          @RequestBody @Valid Dtos.TenantUpdateReq req) {
        return ApiResult.ok(tenantService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable String id) {
        tenantService.delete(id);
        return ApiResult.ok();
    }

    @PostMapping("/{id}/admin")
    public ApiResult<Dtos.ResetAdminResp> resetAdmin(@PathVariable String id,
                                                     @RequestBody(required = false) Dtos.ResetAdminReq req) {
        return ApiResult.ok(tenantService.resetAdmin(id, req));
    }
}
