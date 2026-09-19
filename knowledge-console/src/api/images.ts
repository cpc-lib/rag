import { http, unwrap } from './client';
import type { GeneratedImage, ImageGenerateReq } from './types';

export const imageApi = {
  generate: (req: ImageGenerateReq) =>
    unwrap<GeneratedImage>(http.post('/images/generate', req)),
  list: (current = 1, size = 12) =>
    unwrap<import('./types').PageResult<GeneratedImage>>(
      http.get('/images', { params: { current, size } }),
    ),
  /** 鉴权下载原图，返回二进制 Blob */
  download: (id: number) =>
    http
      .get<Blob>(`/images/${id}/download`, {
        responseType: 'blob',
        timeout: 60000,
      })
      .then((r) => r.data),
};
