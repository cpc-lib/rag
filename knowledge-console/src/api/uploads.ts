import { http, unwrap } from './client';
import type { LibraryFile, UploadInitResp, UploadSessionView } from './types';

/** 分片上传（大文件）：init → 逐片上传 → complete；会话落库，支持断点续传 */
export const uploadApi = {
  /**
   * 初始化分片会话；sha256 非空时后端做秒传匹配，命中则 instant=true。
   * biz=LIBRARY（默认，文件库）/ KB_DOCUMENT（知识库文档，需传 kbId）。
   */
  init: (fileName: string, fileSize: number, contentType: string, sha256?: string,
         biz?: 'LIBRARY' | 'KB_DOCUMENT', kbId?: number) =>
    unwrap<UploadInitResp>(http.post('/uploads/init', { fileName, fileSize, contentType, sha256, biz, kbId })),

  /** 查询会话（断点续传：返回已传分片号与分片大小） */
  session: (sessionId: number) =>
    unwrap<UploadSessionView>(http.get(`/uploads/${sessionId}`)),

  /** 上传单个分片（原始字节流）；单片默认 8MB，超时放宽到 2 分钟 */
  uploadPart: (sessionId: number, partNumber: number, chunk: Blob) =>
    http.post(`/uploads/${sessionId}/parts/${partNumber}`, chunk, {
      headers: { 'Content-Type': 'application/octet-stream' },
      timeout: 120000,
    }),

  /** 全部传完后合并分片，返回文件库条目 */
  complete: (sessionId: number) =>
    unwrap<LibraryFile>(http.post(`/uploads/${sessionId}/complete`, null, { timeout: 120000 })),

  /** 中止会话并清理已传分片 */
  abort: (sessionId: number) => unwrap<void>(http.delete(`/uploads/${sessionId}`)),
};
