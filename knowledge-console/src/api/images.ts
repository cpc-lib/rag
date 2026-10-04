import { http, unwrap } from './client';
import type { GeneratedImage, ImageGenerateReq } from './types';

export const imageApi = {
  /** 文生图为长耗时同步请求（后端上游等待上限 120s），单独放宽到 180s，避免沿用全局 30s 提前超时 */
  generate: (req: ImageGenerateReq) =>
    unwrap<GeneratedImage>(http.post('/images/generate', req, { timeout: 180000 })),
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
