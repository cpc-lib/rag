package com.rag.api.application;

import com.rag.api.common.TenantContext;
import com.rag.api.infrastructure.persistence.entity.SysMenuEntity;
import com.rag.api.infrastructure.persistence.entity.TenantMenuEntity;
import com.rag.api.infrastructure.persistence.entity.UserFeatureEntity;
import com.rag.api.infrastructure.persistence.mapper.SysMenuMapper;
import com.rag.api.infrastructure.persistence.mapper.TenantMenuMapper;
import com.rag.api.infrastructure.persistence.mapper.UserFeatureMapper;
import com.rag.api.interfaces.dto.Dtos;
import com.rag.api.interfaces.security.AuthGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 菜单目录与侧边栏下发：sys_menu 为产品级权威定义，tenant_menu 为功能菜单租户总开关，
 * 普通用户最终可见 = 目录启用 AND 租户总开关 AND 个人授权。
 */
@Service
@RequiredArgsConstructor
public class MenuService {

    private final SysMenuMapper sysMenuMapper;
    private final TenantMenuMapper tenantMenuMapper;
    private final UserFeatureMapper userFeatureMapper;

    /** 当前登录用户的侧边栏菜单。 */
    public List<Dtos.MenuView> sidebar() {
        TenantContext.Session s = TenantContext.require();
        List<SysMenuEntity> rows = switch (s.userType()) {
            case 0 -> sysMenuMapper.selectList(true, true, null, null);
            case 1 -> sysMenuMapper.selectList(true, null, true, null);
            default -> sysMenuMapper.selectList(true, null, null, true);
        };
        if (s.userType() != 2) {
            return rows.stream().map(this::toView).toList();
        }
        Map<String, Boolean> tenantEnabled = tenantState(s.tenantId()).stream()
                .collect(Collectors.toMap(TenantMenuEntity::getMenuCode,
                        e -> Boolean.TRUE.equals(e.getEnabled())));
        Set<String> personal = userFeatureMapper.selectListByUserIdAndType(
                        s.userId(), UserManageService.TYPE_MENU).stream()
                .map(UserFeatureEntity::getCode).collect(Collectors.toSet());
        return rows.stream()
                .filter(m -> Boolean.TRUE.equals(tenantEnabled.get(m.getCode())))
                .filter(m -> personal.contains(m.getCode()))
                .map(this::toView)
                .toList();
    }

    /** 可授权功能菜单目录及租户总开关状态（租户管理员）。 */
    public List<Dtos.GrantableMenuView> grantable() {
        AuthGuard.requireTenantAdmin();
        TenantContext.Session s = TenantContext.require();
        Map<String, Boolean> enabled = tenantState(s.tenantId()).stream()
                .collect(Collectors.toMap(TenantMenuEntity::getMenuCode,
                        e -> Boolean.TRUE.equals(e.getEnabled())));
        return endUserCatalog().stream()
                .map(m -> new Dtos.GrantableMenuView(m.getCode(), m.getName(),
                        enabled.getOrDefault(m.getCode(), false)))
                .toList();
    }

    /** 更新功能菜单租户总开关（全量替换）。 */
    @Transactional
    public List<Dtos.GrantableMenuView> updateTenantMenus(Dtos.TenantMenuReq req) {
        AuthGuard.requireTenantAdmin();
        String tenantId = TenantContext.require().tenantId();
        Set<String> want = (req.menuCodes() == null ? List.<String>of() : req.menuCodes()).stream()
                .collect(Collectors.toCollection(HashSet::new));
        Set<String> catalogCodes = endUserCatalog().stream()
                .map(SysMenuEntity::getCode).collect(Collectors.toSet());
        if (!catalogCodes.containsAll(want)) {
            throw com.rag.api.common.BizException.badRequest("存在非法的功能菜单码");
        }
        Map<String, TenantMenuEntity> state = tenantState(tenantId).stream()
                .collect(Collectors.toMap(TenantMenuEntity::getMenuCode, Function.identity()));
        for (String code : catalogCodes) {
            TenantMenuEntity e = state.get(code);
            e.setEnabled(want.contains(code));
            tenantMenuMapper.updateById(e);
        }
        return grantable();
    }

    /** 普通用户可授权菜单目录（产品启用）。 */
    private List<SysMenuEntity> endUserCatalog() {
        return sysMenuMapper.selectEndUserCatalog();
    }

    /** 租户总开关状态；新租户缺失的开关行按默认开启补齐。 */
    private List<TenantMenuEntity> tenantState(String tenantId) {
        List<TenantMenuEntity> rows = tenantMenuMapper.selectByTenantId(tenantId);
        Set<String> existing = rows.stream()
                .map(TenantMenuEntity::getMenuCode).collect(Collectors.toSet());
        for (SysMenuEntity m : endUserCatalog()) {
            if (!existing.contains(m.getCode())) {
                TenantMenuEntity e = new TenantMenuEntity();
                e.setTenantId(tenantId);
                e.setMenuCode(m.getCode());
                e.setEnabled(true);
                tenantMenuMapper.insert(e);
                rows.add(e);
            }
        }
        return rows;
    }

    private Dtos.MenuView toView(SysMenuEntity m) {
        return new Dtos.MenuView(m.getCode(), m.getName(), m.getPath(), m.getIcon());
    }
}
