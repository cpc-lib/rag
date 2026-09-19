import { http, unwrap } from './client';
import type { ModelItem, ModelReq, ModelType, PageResult } from './types';

export const modelApi = {
  list: (params?: { current?: number; size?: number; type?: ModelType }) =>
    unwrap<PageResult<ModelItem>>(http.get('/models', { params })),
  get: (id: number) => unwrap<ModelItem>(http.get(`/models/${id}`)),
  create: (req: ModelReq) => unwrap<ModelItem>(http.post('/models', req)),
  update: (id: number, req: ModelReq) =>
    unwrap<ModelItem>(http.put(`/models/${id}`, req)),
  enable: (id: number) => unwrap<ModelItem>(http.post(`/models/${id}/enable`)),
  disable: (id: number) => unwrap<ModelItem>(http.post(`/models/${id}/disable`)),
};
