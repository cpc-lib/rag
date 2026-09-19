import { http, unwrap } from './client';
import type {
  TenantUser,
  UpdateUserReq,
  UserCredentialResp,
  ResetUserPasswordResp,
} from './types';

export const userApi = {
  list: () => unwrap<TenantUser[]>(http.get('/users')),
  create: (username: string) =>
    unwrap<UserCredentialResp>(http.post('/users', { username })),
  update: (id: number, req: UpdateUserReq) =>
    unwrap<TenantUser>(http.put(`/users/${id}`, req)),
  resetPassword: (id: number) =>
    unwrap<ResetUserPasswordResp>(http.post(`/users/${id}/reset-password`)),
  listKbs: (id: number) =>
    unwrap<number[]>(http.get(`/users/${id}/kbs`)),
  grantKbs: (id: number, kbIds: number[], promptIds: number[]) =>
    unwrap<void>(http.put(`/users/${id}/kbs`, { kbIds, promptIds })),
};
