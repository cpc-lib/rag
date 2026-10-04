import { http, unwrap } from './client';
import type { ChatSession, ChatSessionDetail } from './types';

export const chatSessionApi = {
  list: () =>
    unwrap<ChatSession[]>(http.get('/chat-sessions')),
  get: (id: number) =>
    unwrap<ChatSessionDetail>(http.get(`/chat-sessions/${id}`)),
  /** 删除会话（后端级联删除该会话全部消息） */
  remove: (id: number) =>
    unwrap<void>(http.delete(`/chat-sessions/${id}`)),
};
