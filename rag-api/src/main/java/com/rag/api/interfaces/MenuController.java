package com.rag.api.interfaces;

import com.rag.api.application.MenuService;
import com.rag.api.common.ApiResult;
import com.rag.api.interfaces.dto.Dtos;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/menus")
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;

    /** 当前用户侧边栏（目录/租户开关/个人授权均在后端过滤）。 */
    @GetMapping
    public ApiResult<List<Dtos.MenuView>> sidebar() {
        return ApiResult.ok(menuService.sidebar());
    }

    /** 可授权功能菜单目录 + 租户总开关（租户管理员）。 */
    @GetMapping("/grantable")
    public ApiResult<List<Dtos.GrantableMenuView>> grantable() {
        return ApiResult.ok(menuService.grantable());
    }

    /** 更新功能菜单租户总开关。 */
    @PutMapping("/grantable")
    public ApiResult<List<Dtos.GrantableMenuView>> updateTenantMenus(
            @RequestBody Dtos.TenantMenuReq req) {
        return ApiResult.ok(menuService.updateTenantMenus(req));
    }
}
