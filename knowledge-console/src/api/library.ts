import { http, unwrap } from './client';
import type { LibraryFile, PlaybackHistory, PlaybackState, SubtitleCue } from './types';

export const libraryApi = {
  /** 文件库列表（当前用户）；keyword 非空时后端按文件名模糊查询 */
  list: (keyword?: string) =>
    unwrap<LibraryFile[]>(
      http.get('/library/files', {
        params: keyword && keyword.trim() ? { keyword: keyword.trim() } : undefined,
      }),
    ),

  /** 文件库直接上传（任意文件）；大文件放宽超时到 10 分钟（全局默认 30s 会被大文件触发） */
  upload: (file: File) => {
    const form = new FormData();
    form.append('file', file);
    return unwrap<LibraryFile>(http.post('/library/files', form, { timeout: 600000 }));
  },

  /** 删除文件库条目（仅允许删除的文件返回 deletable=true） */
  remove: (id: number) => unwrap<void>(http.delete(`/library/files/${id}`)),

  /** 查询播放状态（纯查询）：NONE/PROCESSING/READY/FAILED + 转码进度 */
  playback: (id: number) => unwrap<PlaybackState>(http.get(`/library/files/${id}/playback`)),

  /** 开始/重新转码（幂等）：投递 Worker 转 HLS */
  transcode: (id: number) => unwrap<PlaybackState>(http.post(`/library/files/${id}/transcode`)),
  /** 停止转码：状态回到 NONE，可重新开始 */
  stopTranscode: (id: number) => unwrap<PlaybackState>(http.post(`/library/files/${id}/transcode/stop`)),

  /** 保存视频播放进度（当前用户+文件维度 upsert）；positionMs 小于 1 秒后端会忽略 */
  savePlaybackPosition: (id: number, positionMs: number, durationMs?: number) =>
    unwrap<void>(http.post(`/library/files/${id}/playback-position`, { positionMs, durationMs })),

  /** 当前用户的视频播放记录列表（按最近播放倒序，最多 100 条） */
  playbackHistory: () => unwrap<PlaybackHistory[]>(http.get('/library/files/playback-history')),

  /** 查看文本内容（SRT/VTT 等文本文件） */
  content: (id: number) => unwrap<string>(http.get(`/library/files/${id}/content`)),

  /** 读取归档字幕可编辑条目：优先字幕备份，字幕记录删除后仍可编辑；无备份时后端解析文件自身 */
  cues: (id: number) =>
    unwrap<SubtitleCue[]>(http.get(`/library/files/${id}/cues`)),

  /** 直接编辑归档文件：条目覆盖写回该文件本身（不新增版本），返回更新后的文件信息 */
  saveCues: (id: number, cues: SubtitleCue[]) =>
    unwrap<LibraryFile>(http.put(`/library/files/${id}/content`, { cues })),

  /** 下载文件，返回二进制 Blob；加时间戳参数防缓存 */
  download: (id: number) =>
    http
      .get<Blob>(`/library/files/${id}/download?t=${Date.now()}`, {
        responseType: 'blob',
        timeout: 60000,
      })
      .then((r) => r.data),
};
