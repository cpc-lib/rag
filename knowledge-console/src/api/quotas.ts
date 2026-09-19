import { http, unwrap } from './client';
import type { QuotaUsage } from './types';

export const quotaApi = {
  usage: () => unwrap<QuotaUsage>(http.get('/quotas/usage')),
};
