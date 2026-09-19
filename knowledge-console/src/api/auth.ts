import { http, unwrap } from './client';
import type { ChangePasswordReq, LoginReq, LoginResp, UserInfo } from './types';

export const authApi = {
  login: (req: LoginReq) => unwrap<LoginResp>(http.post('/auth/login', req)),
  logout: () => unwrap<void>(http.post('/auth/logout')),
  me: () => unwrap<UserInfo>(http.get('/auth/me')),
  changePassword: (req: ChangePasswordReq) =>
    unwrap<void>(http.post('/auth/change-password', req)),
};
