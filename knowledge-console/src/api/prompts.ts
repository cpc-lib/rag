import { http, unwrap } from './client';
import type { PromptReq, PromptTemplate } from './types';

export const promptApi = {
  list: (kbId?: number) =>
    unwrap<PromptTemplate[]>(
      http.get(kbId == null ? '/prompts' : `/prompts?kbId=${kbId}`),
    ),
  create: (req: PromptReq) =>
    unwrap<PromptTemplate>(http.post('/prompts', req)),
  update: (id: number, req: PromptReq) =>
    unwrap<PromptTemplate>(http.put(`/prompts/${id}`, req)),
  remove: (id: number) => unwrap<void>(http.delete(`/prompts/${id}`)),
};
