import { http, unwrap } from './client';
import type { GrantableMenu, SidebarMenu } from './types';

export const menuApi = {
  /** 当前用户侧边栏（后端已按角色/租户开关/个人授权过滤） */
  sidebar: () => unwrap<SidebarMenu[]>(http.get('/menus')),
  /** 可授权功能菜单 + 租户总开关 */
  grantable: () => unwrap<GrantableMenu[]>(http.get('/menus/grantable')),
  /** 更新功能菜单租户总开关（全量替换） */
  updateTenant: (menuCodes: string[]) =>
    unwrap<GrantableMenu[]>(http.put('/menus/grantable', { menuCodes })),
};
