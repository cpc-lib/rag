import { http, unwrap } from './client';
import type { ChatSession, ChatSessionDetail } from './types';

export const chatSessionApi = {
  list: () =>
    unwrap<ChatSession[]>(http.get('/chat-sessions')),
  get: (id: number) =>
    unwrap<ChatSessionDetail>(http.get(`/chat-sessions/${id}`)),
};
