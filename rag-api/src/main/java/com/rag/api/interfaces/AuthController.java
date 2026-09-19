package com.rag.api.interfaces;

import com.rag.api.application.AuthService;
import com.rag.api.common.ApiResult;
import com.rag.api.interfaces.dto.Dtos;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ApiResult<Dtos.LoginResp> login(@RequestBody @Valid Dtos.LoginReq req) {
        return ApiResult.ok(authService.login(req.username(), req.password(), req.tenantCode()));
    }

    @PostMapping("/logout")
    public ApiResult<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            authService.logout(authorization.substring(7));
        }
        return ApiResult.ok();
    }

    @GetMapping("/me")
    public ApiResult<Dtos.UserInfo> me() {
        return ApiResult.ok(authService.me());
    }

    @PostMapping("/change-password")
    public ApiResult<Void> changePassword(@RequestBody @Valid Dtos.ChangePasswordReq req) {
        authService.changePassword(req.oldPassword(), req.newPassword());
        return ApiResult.ok();
    }
}
