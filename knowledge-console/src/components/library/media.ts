import type { LibraryFile } from '../../api/types';

/** 播放器与文件库共用的媒体辅助函数。 */

/** 秒数转 mm:ss / hh:mm:ss */
export function formatDuration(sec: number): string {
  if (!isFinite(sec) || sec <= 0) return '00:00';
  const s = Math.floor(sec);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const ss = s % 60;
  const pad = (n: number) => String(n).padStart(2, '0');
  return h > 0 ? `${pad(h)}:${pad(m)}:${pad(ss)}` : `${pad(m)}:${pad(ss)}`;
}

export const IMAGE_EXT = ['png', 'jpg', 'jpeg'];
export const MARKDOWN_EXT = ['md', 'markdown'];
export const TEXT_EXT = ['txt', 'html', 'htm'];
export const VIDEO_EXT = ['mp4', 'mkv', 'avi', 'ts'];
export const AUDIO_EXT = ['mp3', 'flac'];

export function extOf(name: string): string {
  const i = name.lastIndexOf('.');
  return i >= 0 ? name.slice(i + 1).toLowerCase() : '';
}

export type PreviewKind = 'image' | 'markdown' | 'text' | 'video' | 'audio' | null;

export function previewKind(f: LibraryFile): PreviewKind {
  if (f.bizType === 'IMAGE') return 'image';
  if (f.bizType === 'SUBTITLE') return 'text';
  const ext = extOf(f.fileName);
  if (IMAGE_EXT.includes(ext)) return 'image';
  if (VIDEO_EXT.includes(ext)) return 'video';
  if (AUDIO_EXT.includes(ext)) return 'audio';
  if (MARKDOWN_EXT.includes(ext)) return 'markdown';
  return TEXT_EXT.includes(ext) ? 'text' : null;
}

/** 媒体转码进度消息（WS /ws/media-progress）。 */
export interface MediaProgressEvent {
  fileId: number;
  status: string;
  progress: number;
}

export type PlaybackPhase = 'idle' | 'processing' | 'ready' | 'failed';
