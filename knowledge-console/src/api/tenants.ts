import { http, unwrap } from './client';
import type {
  ResetAdminResp,
  Tenant,
  TenantCreateReq,
  TenantUpdateReq,
} from './types';

export const tenantApi = {
  list: () => unwrap<Tenant[]>(http.get('/tenants')),
  get: (id: string) => unwrap<Tenant>(http.get(`/tenants/${id}`)),
  create: (req: TenantCreateReq) =>
    unwrap<ResetAdminResp>(http.post('/tenants', req)),
  update: (id: string, req: TenantUpdateReq) =>
    unwrap<Tenant>(http.put(`/tenants/${id}`, req)),
  /** 删除=停用 */
  remove: (id: string) => unwrap<void>(http.delete(`/tenants/${id}`)),
  resetAdmin: (id: string, username?: string) =>
    unwrap<ResetAdminResp>(http.post(`/tenants/${id}/admin`, username ? { username } : {})),
};
