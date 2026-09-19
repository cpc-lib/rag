import axios, { AxiosError } from 'axios';
import { message } from 'antd';
import type { ApiResult } from './types';
import { useAuthStore } from '../store/auth';

/**
 * 统一 Axios 客户端（spec 2.1：Axios 拦截器统一注入 Token 与 Tenant-ID）。
 * 开发环境经 vite proxy 转发到 rag-api:8080。
 */
export const http = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
});

http.interceptors.request.use((config) => {
  const { token, user } = useAuthStore.getState();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  // 租户用户必带且须与 JWT 一致；平台管理员(000000)也照发，后端仅做一致性校验
  if (user?.tenantId) {
    config.headers['X-Tenant-Id'] = user.tenantId;
  }
  return config;
});

http.interceptors.response.use(
  (resp) => {
    const body = resp.data as ApiResult<unknown>;
    // 二进制/非标准响应直接放行
    if (body == null || typeof body.code !== 'number') {
      return resp;
    }
    if (body.code !== 0) {
      message.error(body.message || '请求失败');
      return Promise.reject(new Error(body.message));
    }
    return resp;
  },
  (error: AxiosError<ApiResult<unknown>>) => {
    const status = error.response?.status;
    const msg = error.response?.data?.message || error.message || '网络异常';
    if (status === 401) {
      // 凭证失效：清理登录态并跳转登录页
      useAuthStore.getState().clearAuth();
      if (!location.pathname.endsWith('/login')) {
        location.href = '/login';
      }
    }
    message.error(msg);
    return Promise.reject(error);
  },
);

/** 取业务 data 的便捷方法 */
export async function unwrap<T>(p: Promise<{ data: ApiResult<T> }>): Promise<T> {
  const resp = await p;
  return resp.data.data;
}
