import { http, unwrap } from './client';
import type { KbCreateReq, KbUpdateReq, KnowledgeBase } from './types';

export const kbApi = {
  list: () => unwrap<KnowledgeBase[]>(http.get('/knowledge-bases')),
  get: (id: number) => unwrap<KnowledgeBase>(http.get(`/knowledge-bases/${id}`)),
  create: (req: KbCreateReq) =>
    unwrap<KnowledgeBase>(http.post('/knowledge-bases', req)),
  update: (id: number, req: KbUpdateReq) =>
    unwrap<KnowledgeBase>(http.put(`/knowledge-bases/${id}`, req)),
  remove: (id: number) => unwrap<void>(http.delete(`/knowledge-bases/${id}`)),
};
