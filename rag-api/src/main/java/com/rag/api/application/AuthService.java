package com.rag.api.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rag.api.common.BizException;
import com.rag.api.common.ErrorCode;
import com.rag.api.common.TenantContext;
import com.rag.api.infrastructure.persistence.entity.SysUserEntity;
import com.rag.api.infrastructure.persistence.entity.TenantEntity;
import com.rag.api.infrastructure.persistence.mapper.SysUserMapper;
import com.rag.api.infrastructure.persistence.mapper.TenantMapper;
import com.rag.api.interfaces.dto.Dtos;
import com.rag.api.interfaces.security.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper userMapper;
    private final TenantMapper tenantMapper;
    private final UserManageService userManageService;
    private final PasswordEncoder passwordEncoder;
    private final JwtAuthFilter jwtAuthFilter;

    public Dtos.LoginResp login(String username, String password, String tenantCode) {
        QueryWrapper<SysUserEntity> qw = new QueryWrapper<SysUserEntity>().eq("username", username).eq("status", 1);
        if (tenantCode != null && !tenantCode.isBlank()) {
            TenantEntity tenant = tenantMapper.selectOne(
                    new QueryWrapper<TenantEntity>().eq("code", tenantCode.trim()));
            if (tenant == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "租户编码不存在");
            }
            qw.eq("tenant_id", tenant.getId());
        }
        List<SysUserEntity> users = userMapper.selectList(qw);
        if (users.isEmpty()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "账号或密码错误");
        }
        if (users.size() > 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该账号存在于多个租户，请在登录时指定租户ID");
        }
        SysUserEntity user = users.get(0);
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "账号或密码错误");
        }
        if (user.getUserType() != 0) {
            TenantEntity tenant = tenantMapper.selectById(user.getTenantId());
            if (tenant == null || tenant.getStatus() == null || tenant.getStatus() != 1) {
                throw new BizException(ErrorCode.FORBIDDEN, "所属租户已停用，请联系平台管理员");
            }
        }
        TenantContext.Session session = new TenantContext.Session(
                user.getId(), user.getTenantId(), user.getUserType(), user.getUsername());
        String token = jwtAuthFilter.createToken(session);
        return new Dtos.LoginResp(token, toUserInfo(user));
    }

    public void logout(String token) {
        jwtAuthFilter.blacklist(token);
    }

    public Dtos.UserInfo me() {
        TenantContext.Session s = TenantContext.require();
        SysUserEntity user = userMapper.selectById(s.userId());
        return user == null
                ? new Dtos.UserInfo(s.userId(), s.username(), s.tenantId(), s.userType(), null, List.of())
                : toUserInfo(user);
    }

    /** 修改本人密码：校验原密码 + 新密码长度，更新 BCrypt 哈希。 */
    public void changePassword(String oldPassword, String newPassword) {
        if (newPassword.length() < 8 || newPassword.length() > 64) {
            throw BizException.badRequest("新密码长度需在 8 ~ 64 位之间");
        }
        if (newPassword.equals(oldPassword)) {
            throw BizException.badRequest("新密码不能与原密码相同");
        }
        TenantContext.Session s = TenantContext.require();
        SysUserEntity user = userMapper.selectById(s.userId());
        if (user == null || !passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw BizException.badRequest("原密码错误");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
    }

    private Dtos.UserInfo toUserInfo(SysUserEntity user) {
        TenantEntity tenant = tenantMapper.selectById(user.getTenantId());
        return new Dtos.UserInfo(user.getId(), user.getUsername(), user.getTenantId(),
                user.getUserType(), tenant == null ? null : tenant.getName(),
                userManageService.menuCodesOf(user.getId()));
    }
}
