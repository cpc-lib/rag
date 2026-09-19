package com.rag.api.interfaces;

import com.rag.api.application.UserManageService;
import com.rag.api.common.ApiResult;
import com.rag.api.interfaces.dto.Dtos;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 租户内普通用户管理（仅租户管理员）。
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserManageController {

    private final UserManageService userManageService;

    @GetMapping
    public ApiResult<List<Dtos.UserView>> list() {
        return ApiResult.ok(userManageService.list());
    }

    @PostMapping
    public ApiResult<Dtos.UserCredentialResp> create(@RequestBody @Valid Dtos.UserCreateReq req) {
        return ApiResult.ok(userManageService.create(req));
    }

    @PutMapping("/{id}")
    public ApiResult<Dtos.UserView> update(@PathVariable long id, @RequestBody Dtos.UpdateUserReq req) {
        return ApiResult.ok(userManageService.update(id, req));
    }

    @PostMapping("/{id}/reset-password")
    public ApiResult<Dtos.ResetUserPasswordResp> resetPassword(@PathVariable long id) {
        return ApiResult.ok(userManageService.resetPassword(id));
    }

    @GetMapping("/{id}/kbs")
    public ApiResult<List<Long>> kbs(@PathVariable long id) {
        return ApiResult.ok(userManageService.grantedKbIds(id));
    }

    @PutMapping("/{id}/kbs")
    public ApiResult<Void> grantKbs(@PathVariable long id, @RequestBody Dtos.GrantKbReq req) {
        userManageService.grantKbs(id, req.kbIds(), req.promptIds());
        return ApiResult.ok();
    }
}
