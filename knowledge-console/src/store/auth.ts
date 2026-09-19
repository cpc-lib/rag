import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { UserInfo } from '../api/types';

interface AuthState {
  token: string | null;
  user: UserInfo | null;
  setAuth: (token: string, user: UserInfo) => void;
  setUser: (user: UserInfo) => void;
  clearAuth: () => void;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      token: null,
      user: null,
      setAuth: (token, user) => set({ token, user }),
      setUser: (user) => set({ user }),
      clearAuth: () => set({ token: null, user: null }),
    }),
    { name: 'rag-console-auth' },
  ),
);

/** 平台超级管理员（超级租户 000000，跨租户） */
export const isPlatformAdmin = (u: UserInfo | null) => u?.userType === 0;
/** 租户管理员 */
export const isTenantAdmin = (u: UserInfo | null) => u?.userType === 1;
