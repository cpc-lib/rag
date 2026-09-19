import { http, unwrap } from './client';
import type { DocumentItem, PageResult } from './types';

export const documentApi = {
  upload: (kbId: number, file: File) => {
    const form = new FormData();
    form.append('file', file);
    return unwrap<DocumentItem>(
      http.post(`/knowledge-bases/${kbId}/documents`, form, {
        headers: { 'Content-Type': 'multipart/form-data' },
        timeout: 120000,
      }),
    );
  },
  list: (kbId: number, page = 1, size = 20) =>
    unwrap<PageResult<DocumentItem>>(
      http.get(`/knowledge-bases/${kbId}/documents`, { params: { page, size } }),
    ),
  downloadUrl: (id: number) =>
    unwrap<string>(http.get(`/documents/${id}/download-url`)),
  /** 删除文档：同时删除全部切片、索引及 MinIO 原文件 */
  remove: (id: number) => unwrap<void>(http.delete(`/documents/${id}`)),
};
