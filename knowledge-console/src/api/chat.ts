import { fetchEventSource, EventStreamContentType } from '@microsoft/fetch-event-source';
import type { ChatStreamReq, Citation } from './types';

/** 与后端 ChatOrchestrator 事件契约一一对应（spec 5.1） */
export interface ChatHandlers {
  onSession: (data: { sessionId: number }) => void;
  onSearchStart: (data: { stage: 'kb' | 'tool'; tool?: 'weather' | 'tavily' }) => void;
  onSearchResult: (data: { citations: Citation[] }) => void;
  onMessage: (data: { delta: string }) => void;
  onError: (data: { code: string; message: string }) => void;
  onDone: (data: { usage: { promptTokens: number; completionTokens: number } }) => void;
}

class FatalStreamError extends Error {}

/**
 * 发起 SSE 问答。
 * - POST + Bearer 鉴权（与 axios 常规请求一致）
 * - 任何异常都抛 FatalStreamError：spec 5.3 要求中断不自动重试，半成品由用户手动「重新生成」
 */
export async function streamChat(
  req: ChatStreamReq,
  token: string,
  tenantId: string,
  handlers: ChatHandlers,
  signal: AbortSignal,
): Promise<void> {
  await fetchEventSource('/api/v1/chat/stream', {
    method: 'POST',
    signal,
    headers: {
      Accept: EventStreamContentType,
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
      'X-Tenant-Id': tenantId,
    },
    body: JSON.stringify(req),
    // 静默库内部日志
    openWhenHidden: true,
    async onopen(res) {
      if (res.ok && res.headers.get('content-type')?.includes(EventStreamContentType)) {
        return;
      }
      // 建连阶段被拒（如 429 SSE 限流、401、403）：尝试读取 ApiResult 错误信息
      let msg = `连接失败（HTTP ${res.status}）`;
      try {
        const body = await res.clone().json();
        if (body?.message) msg = body.message;
      } catch {
        /* 非 JSON 响应，保留默认提示 */
      }
      throw new FatalStreamError(msg);
    },
    onmessage(ev) {
      let data: unknown = null;
      try {
        data = ev.data ? JSON.parse(ev.data) : {};
      } catch {
        data = {};
      }
      switch (ev.event) {
        case 'session':
          handlers.onSession(data as { sessionId: number });
          break;
        case 'search_start':
          handlers.onSearchStart(data as { stage: 'kb' | 'tool'; tool?: 'weather' | 'tavily' });
          break;
        case 'search_result':
          handlers.onSearchResult(data as { citations: Citation[] });
          break;
        case 'message':
          handlers.onMessage(data as { delta: string });
          break;
        case 'error':
          handlers.onError(data as { code: string; message: string });
          throw new FatalStreamError((data as { message?: string }).message || '生成失败');
        case 'done':
          handlers.onDone(
            data as { usage: { promptTokens: number; completionTokens: number } },
          );
          throw new FatalStreamError('__done__');
        default:
          break;
      }
    },
    onerror(err) {
      // done 是正常结束，静默；其余错误上抛，阻止库自动重试
      if (err instanceof FatalStreamError && err.message === '__done__') {
        throw err;
      }
      const msg = err instanceof FatalStreamError ? err.message : '连接中断，请手动重新生成';
      if (!(err instanceof FatalStreamError)) {
        handlers.onError({ code: 'STREAM_BROKEN', message: msg });
      }
      throw err instanceof Error ? err : new FatalStreamError(msg);
    },
  }).catch((err) => {
    // done 标记的正常结束吞掉；其余继续抛出
    if (err instanceof FatalStreamError && err.message === '__done__') {
      return;
    }
    throw err;
  });
}
