import { http, unwrap } from './client';
import type { Subtitle, SubtitleListItem, SubtitleTranslateReq, SubtitleUpdateReq, TranslateLang } from './types';

export const subtitleApi = {
  /** 上传字幕文件（.vtt/.srt/.ass，服务端先做格式规范校验），返回解析后的 SRT 视图 */
  upload: (file: File) => {
    const form = new FormData();
    form.append('file', file);
    return unwrap<Subtitle>(
      http.post('/subtitles', form, {
        headers: { 'Content-Type': 'multipart/form-data' },
        timeout: 60000,
      }),
    );
  },
  /** 删除字幕记录（级联删除字幕条与文件库归档） */
  remove: (id: number) => unwrap<void>(http.delete(`/subtitles/${id}`)),

  list: () => unwrap<SubtitleListItem[]>(http.get('/subtitles')),

  get: (id: number) => unwrap<Subtitle>(http.get(`/subtitles/${id}`)),

  /** 翻译字幕到目标语言（语言来自维护列表），indices 为勾选序号（可空=全部）；大文件多批次耗时较长，放宽到 10 分钟 */
  translate: (id: number, req: SubtitleTranslateReq) =>
    unwrap<Subtitle>(http.post(`/subtitles/${id}/translate`, req, { timeout: 600000 })),

  /** 翻译目标语言列表（租户级维护） */
  listLangs: () => unwrap<TranslateLang[]>(http.get('/subtitles/langs')),

  /** 新增目标语言，返回更新后的列表 */
  addLang: (name: string) =>
    unwrap<TranslateLang[]>(http.post('/subtitles/langs', { name })),

  /** 删除目标语言（至少保留一个） */
  deleteLang: (id: number) => unwrap<void>(http.delete(`/subtitles/langs/${id}`)),

  /** 编辑字幕（原文/译文可人工修改，时间轴不可改） */
  update: (id: number, req: SubtitleUpdateReq) =>
    unwrap<Subtitle>(http.put(`/subtitles/${id}`, req)),

  /** 下载 SRT 文件，返回二进制 Blob；加时间戳参数防浏览器缓存旧文件 */
  download: (id: number) =>
    http
      .get<Blob>(`/subtitles/${id}/download?t=${Date.now()}`, {
        responseType: 'blob',
        timeout: 60000,
      })
      .then((r) => r.data),
};
