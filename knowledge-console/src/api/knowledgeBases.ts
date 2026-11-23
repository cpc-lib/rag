import { http, unwrap } from './client';
import type { KbCreateReq, KbRebuildView, KbUpdateReq, KnowledgeBase } from './types';

export const kbApi = {
  list: () => unwrap<KnowledgeBase[]>(http.get('/knowledge-bases')),
  get: (id: number) => unwrap<KnowledgeBase>(http.get(`/knowledge-bases/${id}`)),
  create: (req: KbCreateReq) =>
    unwrap<KnowledgeBase>(http.post('/knowledge-bases', req)),
  update: (id: number, req: KbUpdateReq) =>
    unwrap<KnowledgeBase>(http.put(`/knowledge-bases/${id}`, req)),
  remove: (id: number) => unwrap<void>(http.delete(`/knowledge-bases/${id}`)),
  /** 触发别名级双版本重建（RAG-003），重建期间旧索引保持可读 */
  rebuildIndex: (id: number) =>
    unwrap<KbRebuildView>(http.post(`/knowledge-bases/${id}/rebuild-index`)),
};
