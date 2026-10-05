import { useEffect, useRef, useState } from 'react';
import {
  App, Badge, Button, Card, Drawer, Dropdown, Input, Modal, Popconfirm, Popover, Progress, Select, Slider, Space, Spin, Table, Tabs, Tag, Typography, Upload,
} from 'antd';
import {
  AudioMutedOutlined, CaretRightOutlined, CloseOutlined, CloudUploadOutlined, DeleteOutlined, DownloadOutlined, EyeOutlined,
  FullscreenOutlined, FullscreenExitOutlined, PauseOutlined, ReloadOutlined, SearchOutlined, SoundOutlined, UploadOutlined,
} from '@ant-design/icons';
import Hls from 'hls.js';
import { createSHA256 } from 'hash-wasm';
import { libraryApi } from '../api/library';
import { uploadApi } from '../api/uploads';
import type { LibraryFile, PlaybackHistory } from '../api/types';
import { useAuthStore } from '../store/auth';
import SubtitleEditDrawer from '../components/SubtitleEditDrawer';

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
}

function formatTime(v: string): string {
  return new Date(v).toLocaleString('zh-CN', { hour12: false });
}

/** 秒数转 mm:ss / hh:mm:ss */
function formatDuration(sec: number): string {
  if (!isFinite(sec) || sec <= 0) return '00:00';
  const s = Math.floor(sec);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const ss = s % 60;
  const pad = (n: number) => String(n).padStart(2, '0');
  return h > 0 ? `${pad(h)}:${pad(m)}:${pad(ss)}` : `${pad(m)}:${pad(ss)}`;
}

const IMAGE_EXT = ['png', 'jpg', 'jpeg'];
const TEXT_EXT = ['txt', 'md', 'html', 'htm'];
const VIDEO_EXT = ['mp4', 'mkv', 'avi', 'ts'];
const AUDIO_EXT = ['mp3', 'flac'];

function extOf(name: string): string {
  const i = name.lastIndexOf('.');
  return i >= 0 ? name.slice(i + 1).toLowerCase() : '';
}

function previewKind(f: LibraryFile): 'image' | 'text' | 'video' | 'audio' | null {
  if (f.bizType === 'IMAGE') return 'image';
  if (f.bizType === 'SUBTITLE') return 'text';
  const ext = extOf(f.fileName);
  if (IMAGE_EXT.includes(ext)) return 'image';
  if (VIDEO_EXT.includes(ext)) return 'video';
  if (AUDIO_EXT.includes(ext)) return 'audio';
  return TEXT_EXT.includes(ext) ? 'text' : null;
}

export default function FileLibraryPage() {
  const { message } = App.useApp();
  const [files, setFiles] = useState<LibraryFile[]>([]);
  const [loading, setLoading] = useState(false);
  /** 上传任务列表：每个文件独立进度条，互不阻塞；tip 为阶段提示（如 loading） */
  const [uploads, setUploads] = useState<{ key: string; name: string; percent: number; tip?: string }[]>([]);
  const [viewing, setViewing] = useState<LibraryFile | null>(null);
  const [editing, setEditing] = useState<LibraryFile | null>(null);
  const [content, setContent] = useState('');
  const [loadingContent, setLoadingContent] = useState(false);
  const [imageUrl, setImageUrl] = useState('');
  const [kw, setKw] = useState('');
  const [speed, setSpeed] = useState(1);
  const [playReady, setPlayReady] = useState<'idle' | 'processing' | 'ready' | 'failed'>('processing');
  const [transcodePct, setTranscodePct] = useState(0);
  const [playHls, setPlayHls] = useState(false);
  const [levels, setLevels] = useState<{ value: number; label: string }[]>([]);
  const [quality, setQuality] = useState(-1);
  /** 视频分辨率（后端转码探测写入），用于清晰度标签展示 */
  const [videoRes, setVideoRes] = useState<{ w: number | null; h: number | null }>({ w: null, h: null });
  /** 恢复播放的起始位置（秒），0 表示从头 */
  const [resumeSec, setResumeSec] = useState(0);
  const mediaRef = useRef<HTMLMediaElement | null>(null);
  const hlsRef = useRef<Hls | null>(null);
  const token = useAuthStore((s) => s.token);
  const [transcodeJobs, setTranscodeJobs] = useState<Record<number, { status: string; progress: number }>>({});
  const wsRef = useRef<WebSocket | null>(null);
  const subscribedRef = useRef<Set<number>>(new Set());
  const viewingIdRef = useRef<number | null>(null);
  const kwRef = useRef('');
  /** 本次上传会话内已处理的 SHA-256：相同内容文件只保留一个任务 */
  const seenSha256Ref = useRef<Set<string>>(new Set());
  viewingIdRef.current = viewing?.id ?? null;
  kwRef.current = kw;

  /** 播放记录 */
  const [history, setHistory] = useState<PlaybackHistory[]>([]);
  const [activeTab, setActiveTab] = useState<'all' | 'history'>('all');

  // 播放器自定义状态
  const [paused, setPaused] = useState(false);
  const [curTime, setCurTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [bufEnd, setBufEnd] = useState(0);
  const [volume, setVolume] = useState(1);
  const [muted, setMuted] = useState(false);
  const [volOpen, setVolOpen] = useState(false);
  /** 弹窗伸缩后的宽高 */
  const [modalSize, setModalSize] = useState<{ w: number; h: number }>({ w: 0, h: 0 });
  const videoWrapRef = useRef<HTMLDivElement | null>(null);

  const handleProgress = (m: { fileId: number; status: string; progress: number }) => {
    const pct = m.progress >= 0 ? m.progress : 0;
    if (m.status === 'PROCESSING') {
      setTranscodeJobs((jobs) => ({ ...jobs, [m.fileId]: { status: 'PROCESSING', progress: pct } }));
      if (viewingIdRef.current === m.fileId) setTranscodePct(pct);
      return;
    }
    setTranscodeJobs((jobs) => {
      const n = { ...jobs };
      delete n[m.fileId];
      return n;
    });
    if (subscribedRef.current.has(m.fileId) && viewingIdRef.current !== m.fileId) {
      sendSub(m.fileId, true);
      subscribedRef.current.delete(m.fileId);
    }
    if (m.status === 'READY') {
      message.success('转码完成，可在线播放');
      if (viewingIdRef.current === m.fileId) {
        setPlayHls(true);
        setPlayReady('ready');
      }
    } else if (m.status === 'NONE') {
      if (viewingIdRef.current === m.fileId) setPlayReady('idle');
    } else if (viewingIdRef.current === m.fileId) {
      setPlayReady('failed');
    }
    load(kwRef.current);
  };

  const ensureWs = () => {
    const cur = wsRef.current;
    if (cur && cur.readyState !== WebSocket.CLOSED && cur.readyState !== WebSocket.CLOSING) return;
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    const ws = new WebSocket(
      `${proto}://${location.host}/ws/media-progress?token=${encodeURIComponent(token ?? '')}`,
    );
    wsRef.current = ws;
    ws.onopen = () => subscribedRef.current.forEach((id) => sendSub(id));
    ws.onmessage = (ev) => {
      try {
        handleProgress(JSON.parse(ev.data));
      } catch {
        // 忽略
      }
    };
    ws.onclose = () => {
      wsRef.current = null;
      if (subscribedRef.current.size > 0) setTimeout(ensureWs, 2000);
    };
    ws.onerror = () => ws.close();
  };

  const sendSub = (id: number, unsub = false) => {
    const ws = wsRef.current;
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(unsub ? { fileId: id, unsubscribe: true } : { fileId: id }));
    }
  };

  const subscribeFile = (id: number) => {
    subscribedRef.current.add(id);
    ensureWs();
    sendSub(id);
  };

  useEffect(
    () => () => {
      subscribedRef.current.clear();
      wsRef.current?.close();
      wsRef.current = null;
    },
    [],
  );

  const load = async (keyword?: string) => {
    setLoading(true);
    try {
      const list = await libraryApi.list(keyword);
      setFiles(list);
      list.forEach((f) => {
        if (f.playbackStatus === 'PROCESSING' && !subscribedRef.current.has(f.id)) {
          subscribeFile(f.id);
          setTranscodeJobs((m) => ({
            ...m,
            [f.id]: { status: 'PROCESSING', progress: f.playbackProgress ?? 0 },
          }));
        }
      });
    } finally {
      setLoading(false);
    }
  };

  const loadHistory = async () => {
    try {
      setHistory(await libraryApi.playbackHistory());
    } catch {
      // 忽略
    }
  };

  useEffect(() => {
    load();
  }, []);

  useEffect(() => {
    const timer = setTimeout(() => load(kw), 300);
    return () => clearTimeout(timer);
  }, [kw]);

  useEffect(() => {
    if (activeTab === 'history') loadHistory();
  }, [activeTab]);

  /** 关闭弹窗时保存当前播放进度（每次播放会话产生一条新记录）。 */
  const saveProgress = () => {
    const v = mediaRef.current as HTMLVideoElement | null;
    if (!v || !viewing) return;
    const pos = Math.round(v.currentTime * 1000);
    const dur = Math.round((v.duration || 0) * 1000);
    if (pos < 1000) return;
    libraryApi.savePlaybackPosition(viewing.id, pos, dur).catch(() => {});
  };

  /** 键盘快捷键 */
  useEffect(() => {
    const isVideoKind = viewing ? previewKind(viewing) === 'video' : false;
    if (!viewing || !isVideoKind) return;
    const onKey = (e: KeyboardEvent) => {
      const v = mediaRef.current as HTMLVideoElement | null;
      if (!v) return;
      switch (e.key) {
        case ' ':
        case 'k':
          e.preventDefault(); togglePlay(); break;
        case 'ArrowLeft':
          e.preventDefault(); seekBy(-10); break;
        case 'ArrowRight':
          e.preventDefault(); seekBy(10); break;
        case 'ArrowUp':
          e.preventDefault(); v.volume = Math.min(1, v.volume + 0.1); setVolume(v.volume); break;
        case 'ArrowDown':
          e.preventDefault(); v.volume = Math.max(0, v.volume - 0.1); setVolume(v.volume); break;
        case 'f':
          e.preventDefault(); toggleFullscreen(); break;
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [viewing]);

  // 只读查看
  useEffect(() => {
    if (!viewing) return;
    const kind = previewKind(viewing);
    if (kind === 'video' || kind === 'audio') {
      setSpeed(1);
      setPlayReady('processing');
      setPlayHls(false);
      setLevels([]);
      setQuality(-1);
      setTranscodePct(0);
      setResumeSec(0);
      setPaused(false);
      setCurTime(0);
      setDuration(0);
      if (kind === 'video') subscribeFile(viewing.id);
      let stopped = false;
      libraryApi
        .playback(viewing.id)
        .then((st) => {
          if (stopped) return;
          if (st.status === 'NATIVE' || st.status === 'READY') {
            setPlayHls(st.status === 'READY' && st.hls);
            setPlayReady('ready');
            setVideoRes({ w: st.videoWidth, h: st.videoHeight });
            if (st.positionMs && st.positionMs > 0) setResumeSec(st.positionMs / 1000);
          } else if (st.status === 'FAILED') setPlayReady('failed');
          else if (st.status === 'NONE') setPlayReady('idle');
          else setTranscodePct(st.progress ?? 0);
        })
        .catch(() => {
          if (!stopped) setPlayReady('failed');
        });
      return () => {
        stopped = true;
      };
    }
    let cancelled = false;
    let url = '';
    setContent('');
    setImageUrl('');
    setLoadingContent(true);
    const task =
      previewKind(viewing) === 'image'
        ? libraryApi.download(viewing.id).then((blob) => {
            if (!cancelled) {
              url = URL.createObjectURL(blob);
              setImageUrl(url);
            }
          })
        : libraryApi.content(viewing.id).then((c) => {
            if (!cancelled) setContent(c);
          });
    task.finally(() => {
      if (!cancelled) setLoadingContent(false);
    });
    return () => {
      cancelled = true;
      if (url) URL.revokeObjectURL(url);
    };
  }, [viewing]);

  const startTranscode = async () => {
    if (!viewing) return;
    subscribeFile(viewing.id);
    setPlayReady('processing');
    setTranscodePct(0);
    try {
      await libraryApi.transcode(viewing.id);
    } catch {
      setPlayReady('failed');
    }
  };

  const startRowTranscode = async (f: LibraryFile) => {
    try {
      subscribeFile(f.id);
      const st = await libraryApi.transcode(f.id);
      setTranscodeJobs((m) => ({ ...m, [f.id]: { status: 'PROCESSING', progress: st.progress ?? 0 } }));
    } catch {
      message.error('投递转码失败');
    }
  };

  const stopTranscode = async (id: number) => {
    try {
      await libraryApi.stopTranscode(id);
    } catch {
      message.error('停止转码失败');
      return;
    }
    setTranscodeJobs((m) => {
      const n = { ...m };
      delete n[id];
      return n;
    });
    if (subscribedRef.current.has(id)) {
      sendSub(id, true);
      subscribedRef.current.delete(id);
    }
    if (viewingIdRef.current === id) setPlayReady('idle');
    message.success('已停止转码');
    load(kwRef.current);
  };

  useEffect(() => {
    if (mediaRef.current) mediaRef.current.playbackRate = speed;
  }, [speed, viewing]);

  // HLS 播放
  useEffect(() => {
    if (playReady !== 'ready' || !playHls || !viewing || previewKind(viewing) !== 'video') return;
    const video = mediaRef.current as HTMLVideoElement | null;
    if (!video) return;
    const src = `/api/v1/library/files/${viewing.id}/hls/master.m3u8`;
    if (!Hls.isSupported()) {
      video.src = src;
      return;
    }
    const hls = new Hls({
      // 大分片/慢网络兜底：分片加载超时放宽到 2 分钟并多重试几次，避免高码率分片偶发慢下载直接 fatal
      fragLoadingTimeOut: 120000,
      fragLoadingMaxRetry: 6,
      manifestLoadingTimeOut: 30000,
      manifestLoadingMaxRetry: 4,
      levelLoadingTimeOut: 30000,
      xhrSetup: (xhr) => {
        xhr.setRequestHeader('Authorization', `Bearer ${token ?? ''}`);
      },
    });
    hlsRef.current = hls;
    hls.loadSource(src);
    hls.attachMedia(video);
    hls.on(Hls.Events.MANIFEST_PARSED, () => {
      setLevels(
        hls.levels.map((l, i) => ({
          value: i,
          label: l.height ? `${l.height}P` : `${Math.round(l.bitrate / 1000)}k`,
        })),
      );
      video.play().catch(() => {});
    });
    hls.on(Hls.Events.ERROR, (_e, data) => {
      if (!data.fatal) return;
      // 网络类致命错误（分片/列表加载失败）：自动续载重试，不轻易判死
      if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
        hls.startLoad();
        return;
      }
      // 媒体解码类致命错误：尝试恢复解码器
      if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
        hls.recoverMediaError();
        return;
      }
      setPlayReady('failed');
    });
    return () => {
      hls.destroy();
      hlsRef.current = null;
    };
  }, [playReady, playHls, viewing, token]);

  // 视频元数据加载后恢复播放进度
  useEffect(() => {
    const v = mediaRef.current as HTMLVideoElement | null;
    if (!v || !resumeSec) return;
    const onMeta = () => {
      if (resumeSec > 0 && resumeSec < (v.duration || Infinity)) {
        v.currentTime = resumeSec;
      }
    };
    v.addEventListener('loadedmetadata', onMeta);
    return () => v.removeEventListener('loadedmetadata', onMeta);
  }, [resumeSec, playReady, playHls, viewing]);

  /** 下载原文件：浏览器原生流式下载（?token= 鉴权），大视频不经过内存 Blob，避免超时。 */
  const handleDownload = (f: LibraryFile) => {
    const a = document.createElement('a');
    a.href = `/api/v1/library/files/${f.id}/download?token=${encodeURIComponent(token ?? '')}`;
    a.download = f.fileName;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
  };

  const handleUpload = async (file: File) => {
    const key = `upload-session:${file.name}:${file.size}`;
    const taskKey = `${file.name}:${file.size}:${Date.now()}`;
    setUploads((prev) => [...prev, { key: taskKey, name: file.name, percent: 0, tip: 'loading' }]);
    const setPct = (p: number) =>
      setUploads((prev) => prev.map((u) => (u.key === taskKey ? { ...u, percent: p, tip: undefined } : u)));
    try {
      // 秒传：流式分块计算 SHA-256（hash-wasm 增量模式，内存占用极低，大小无上限）
      let sha256Hex: string;
      {
        const hasher = await createSHA256();
        const CHUNK = 8 * 1024 * 1024;
        for (let off = 0; off < file.size; off += CHUNK) {
          const buf = await file.slice(off, Math.min(off + CHUNK, file.size)).arrayBuffer();
          hasher.update(new Uint8Array(buf));
          // 校验进度映射到 0-50%，避免长时间 0% 卡死感
          setUploads((prev) => prev.map((u) => (u.key === taskKey
            ? { ...u, percent: Math.round(((off + CHUNK) / file.size) * 50), tip: 'loading' }
            : u)));
        }
        sha256Hex = hasher.digest('hex');
        setUploads((prev) => prev.map((u) => (u.key === taskKey ? { ...u, percent: 50 } : u)));
      }
      // 批内去重：同一 sha256 只保留第一个任务，其余直接跳过（仅进行中去重，完成后由后端秒传兜底）
      if (seenSha256Ref.current.has(sha256Hex)) {
        setUploads((prev) => prev.map((u) => (u.key === taskKey ? { ...u, percent: 100, tip: '重复' } : u)));
        message.info(`${file.name} 与本次上传中的另一文件内容相同，已跳过`);
        return false;
      }
      seenSha256Ref.current.add(sha256Hex);
      try {
        let sessionId = Number(localStorage.getItem(key)) || 0;
        let chunkSize = 0;
        const done = new Set<number>();
        if (sessionId) {
          try {
            const s = await uploadApi.session(sessionId);
            if (s.status === 'UPLOADING') {
              chunkSize = s.chunkSize;
              s.uploadedParts.forEach((p) => done.add(p));
            } else {
              sessionId = 0;
            }
          } catch {
            sessionId = 0;
          }
        }
        if (!sessionId) {
          const r = await uploadApi.init(file.name, file.size, file.type || 'application/octet-stream', sha256Hex);
          if (r.instant) {
            // 秒传命中：文件库已有同内容条目，直接完成
            localStorage.removeItem(key);
            setUploads((prev) => prev.map((u) => (u.key === taskKey ? { ...u, percent: 100, tip: undefined } : u)));
            message.success(`${file.name} 秒传成功（文件已存在）`);
            await load(kw);
            return false;
          }
          sessionId = r.sessionId;
          chunkSize = r.chunkSize;
          localStorage.setItem(key, String(sessionId));
        }
        const total = Math.ceil(file.size / chunkSize);
        if (done.size > 0) setPct(Math.round((done.size / total) * 100));
        for (let i = 0; i < total; i++) {
          const part = i + 1;
          if (done.has(part)) continue;
          await uploadApi.uploadPart(sessionId, part, file.slice(i * chunkSize, (i + 1) * chunkSize));
          done.add(part);
          setPct(Math.round((done.size / total) * 100));
        }
        await uploadApi.complete(sessionId);
        localStorage.removeItem(key);
        message.success(`${file.name} 上传成功`);
        await load(kw);
      } finally {
        // 任务结束后从批内去重集合移除，允许用户删除后再次上传同一文件
        seenSha256Ref.current.delete(sha256Hex);
      }
    } catch {
      message.error(`${file.name} 上传失败，重新上传将自动从断点续传`);
    } finally {
      setTimeout(() => setUploads((prev) => prev.filter((u) => u.key !== taskKey)), 800);
    }
    return false;
  };

  const handleDelete = async (f: LibraryFile) => {
    await libraryApi.remove(f.id);
    message.success('文件已删除');
    if (viewing?.id === f.id) setViewing(null);
    if (editing?.id === f.id) setEditing(null);
    await load(kw);
  };

  // 关闭弹窗时保存进度
  const closeModal = () => {
    saveProgress();
    setViewing(null);
  };

  /** 播放/暂停切换 */
  const togglePlay = () => {
    const v = mediaRef.current as HTMLVideoElement | null;
    if (!v) return;
    if (v.paused) v.play().catch(() => {});
    else v.pause();
  };

  /** 快进/快退 N 秒 */
  const seekBy = (delta: number) => {
    const v = mediaRef.current as HTMLVideoElement | null;
    if (!v) return;
    v.currentTime = Math.max(0, Math.min(v.duration || 0, v.currentTime + delta));
  };

  /** 切换全屏 */
  const toggleFullscreen = () => {
    const el = videoWrapRef.current;
    if (!el) return;
    if (!document.fullscreenElement) {
      el.requestFullscreen?.().catch(() => {});
      setIsFullscreen(true);
    } else {
      document.exitFullscreen?.().catch(() => {});
      setIsFullscreen(false);
    }
  };

  /** 窗口伸缩：cx/cy 表示手柄所在角（0=左/上，1=右/下）。弹窗居中布局，尺寸变化两侧对称扩张，位移需 ×2 手柄才贴合光标。 */
  const onResizeStart = (e: React.MouseEvent, cx = 1, cy = 1) => {
    e.preventDefault();
    const startX = e.clientX;
    const startY = e.clientY;
    const startW = modalSize.w || Math.min(window.innerWidth * 0.9, 1280);
    const startH = modalSize.h || Math.min(window.innerHeight * 0.82, 720);
    const onMove = (ev: MouseEvent) => {
      const dx = (ev.clientX - startX) * (cx === 1 ? 1 : -1) * 2;
      const dy = (ev.clientY - startY) * (cy === 1 ? 1 : -1) * 2;
      setModalSize({
        w: Math.max(480, Math.min(window.innerWidth * 0.96, startW + dx)),
        h: Math.max(320, Math.min(window.innerHeight * 0.92, startH + dy)),
      });
    };
    const onUp = () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
  };

  const videoKind = viewing ? previewKind(viewing) : null;
  const isVideo = videoKind === 'video';

  return (
    <Card
      title="文件库"
      extra={
        <Space>
          <Input
            allowClear
            size="middle"
            prefix={<SearchOutlined style={{ color: '#bfbfbf' }} />}
            placeholder="按文件名搜索"
            value={kw}
            onChange={(e) => setKw(e.target.value)}
            style={{ width: 220 }}
          />
          <Button icon={<ReloadOutlined />} loading={loading} onClick={() => load(kw)}>
            刷新
          </Button>
          <Upload
            name="file"
            multiple
            showUploadList={false}
            beforeUpload={handleUpload}
          >
            <Button type="primary" icon={<UploadOutlined />}>
              上传文件
            </Button>
          </Upload>
          {uploads.length > 0 && (
            <Popover
              title="上传中"
              trigger="click"
              content={
                <div style={{ width: 320, maxHeight: 300, overflow: 'auto' }}>
                  {uploads.map((u) => (
                    <div key={u.key} style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
                      <Typography.Text
                        style={{ fontSize: 12, flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
                        title={u.name}
                      >
                        {u.name}
                      </Typography.Text>
                      <div style={{ width: 120 }}>
                        {u.tip ? (
                          <Tag style={{ fontSize: 11, marginInlineEnd: 0 }}>{u.tip}</Tag>
                        ) : (
                          <Progress percent={u.percent} size="small" style={{ margin: 0 }} />
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              }
            >
              <Badge count={uploads.length} size="small">
                <Button icon={<CloudUploadOutlined />} />
              </Badge>
            </Popover>
          )}
        </Space>
      }
      style={{ height: '100%' }}
    >
      <Tabs
        activeKey={activeTab}
        onChange={(k) => setActiveTab(k as 'all' | 'history')}
        items={[
          {
            key: 'all',
            label: '全部文件',
            children: (
              <Table
                rowKey="id"
                loading={loading}
                dataSource={files}
                locale={{ emptyText: kw.trim() ? '无匹配文件' : '暂无文件' }}
                pagination={{ pageSize: 20, showTotal: (t) => `共 ${t} 个` }}
                columns={[
                  { title: '文件名', dataIndex: 'fileName', ellipsis: true },
                  {
                    title: '业务类型',
                    dataIndex: 'bizType',
                    width: 110,
                    render: (v: string) => {
                      if (v === 'SUBTITLE') return <Tag color="blue">字幕文件</Tag>;
                      if (v === 'IMAGE') return <Tag color="purple">图片文件</Tag>;
                      if (v === 'DOCUMENT') return <Tag color="green">知识库文档</Tag>;
                      return <Tag>其他文件</Tag>;
                    },
                  },
                  { title: '大小', dataIndex: 'fileSize', width: 110, render: (v: number) => formatSize(v) },
                  { title: '保存时间', dataIndex: 'updatedAt', width: 200, render: (v: string) => formatTime(v) },
                  {
                    title: '操作',
                    width: 460,
                    render: (_, f: LibraryFile) => (
                      <Space>
                        {previewKind(f) !== null && (
                          <Button size="small" icon={<EyeOutlined />} onClick={() => setViewing(f)}>
                            查看
                          </Button>
                        )}
                        {VIDEO_EXT.includes(extOf(f.fileName)) &&
                          (() => {
                            const job = transcodeJobs[f.id];
                            const status = job?.status ?? f.playbackStatus;
                            if (status === 'READY') {
                              return (
                                <Popconfirm
                                  title="重新转码该视频？"
                                  description="将删除现有 HLS 产物并全量重转，转码期间无法播放"
                                  okText="重新转码"
                                  cancelText="取消"
                                  onConfirm={() => startRowTranscode(f)}
                                >
                                  <Button size="small" type="primary" ghost>重新转码</Button>
                                </Popconfirm>
                              );
                            }
                            if (status === 'PROCESSING') {
                              return (
                                <>
                                  <Button size="small" disabled loading>
                                    转码中 {job?.progress ?? f.playbackProgress ?? 0}%
                                  </Button>
                                  <Popconfirm
                                    title="停止转码？"
                                    description="将终止转码进程，可稍后重新开始"
                                    okText="停止"
                                    cancelText="继续转码"
                                    okButtonProps={{ danger: true }}
                                    onConfirm={() => stopTranscode(f.id)}
                                  >
                                    <Button size="small" danger>停止</Button>
                                  </Popconfirm>
                                </>
                              );
                            }
                            return (
                              <Button size="small" type="primary" ghost onClick={() => startRowTranscode(f)}>
                                {status === 'FAILED' ? '重新转码' : '开始转码'}
                              </Button>
                            );
                          })()}
                        {f.bizType === 'SUBTITLE' && (
                          <Button size="small" type="primary" ghost onClick={() => setEditing(f)}>
                            编辑
                          </Button>
                        )}
                        <Button size="small" icon={<DownloadOutlined />} onClick={() => handleDownload(f)}>
                          下载
                        </Button>
                        {f.deletable && (
                          <Popconfirm
                            title="删除该文件？"
                            description="将同时删除存储中的文件，不可恢复"
                            okText="删除"
                            okButtonProps={{ danger: true }}
                            cancelText="取消"
                            onConfirm={() => handleDelete(f)}
                          >
                            <Button type="link" danger size="small" icon={<DeleteOutlined />}>
                              删除
                            </Button>
                          </Popconfirm>
                        )}
                      </Space>
                    ),
                  },
                ]}
              />
            ),
          },
          {
            key: 'history',
            label: '播放记录',
            children: (
              <Table
                rowKey="fileId"
                dataSource={history}
                locale={{ emptyText: '暂无播放记录' }}
                pagination={{ pageSize: 20, showTotal: (t) => `共 ${t} 条` }}
                columns={[
                  { title: '文件名', dataIndex: 'fileName', ellipsis: true },
                  {
                    title: '播放进度',
                    width: 220,
                    render: (_, h: PlaybackHistory) => {
                      const pct = h.durationMs > 0 ? Math.min(100, Math.round((h.positionMs / h.durationMs) * 100)) : 0;
                      return (
                        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                          <Progress percent={pct} size="small" style={{ width: 120, margin: 0 }} />
                          <span style={{ fontSize: 12, color: '#888' }}>
                            {formatDuration(h.positionMs / 1000)} / {formatDuration(h.durationMs / 1000)}
                          </span>
                        </div>
                      );
                    },
                  },
                  { title: '大小', dataIndex: 'fileSize', width: 110, render: (v: number) => formatSize(v) },
                  { title: '最近播放', dataIndex: 'updatedAt', width: 200, render: (v: string) => formatTime(v) },
                  {
                    title: '操作',
                    width: 160,
                    render: (_, h: PlaybackHistory) => (
                      <Space>
                        {h.playbackStatus === 'DELETED' ? (
                          <Tag color="default">文件已删除</Tag>
                        ) : (
                          <Button
                            size="small"
                            type="primary"
                            icon={<CaretRightOutlined />}
                            onClick={async () => {
                              const list = await libraryApi.list();
                              const f = list.find((x) => x.id === h.fileId);
                              if (f) setViewing(f);
                              else message.warning('文件不存在或已被删除');
                            }}
                          >
                            继续播放
                          </Button>
                        )}
                      </Space>
                    ),
                  },
                ]}
              />
            ),
          },
        ]}
      />

      <SubtitleEditDrawer
        open={!!editing}
        fileId={editing?.id ?? 0}
        title={editing?.fileName ?? ''}
        onClose={() => setEditing(null)}
        onSaved={() => load(kw)}
      />

      {/* 文本/图片只读预览 */}
      <Drawer
        title={viewing?.fileName}
        width={viewing?.bizType === 'IMAGE' ? 820 : 640}
        open={!!viewing && ['text', 'image'].includes(previewKind(viewing) ?? '')}
        onClose={() => setViewing(null)}
        destroyOnHidden
      >
        {loadingContent ? (
          <div style={{ textAlign: 'center', padding: 40 }}>
            <Spin />
          </div>
        ) : viewing && previewKind(viewing) === 'image' ? (
          imageUrl ? (
            <img
              src={imageUrl}
              alt={viewing.fileName}
              style={{ maxWidth: '100%', borderRadius: 6, display: 'block', margin: '0 auto' }}
            />
          ) : null
        ) : (
          <Typography.Paragraph>
            <pre
              style={{
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-all',
                fontSize: 13,
                background: '#fafafa',
                padding: 12,
                borderRadius: 6,
                maxHeight: 'calc(100vh - 200px)',
                overflow: 'auto',
              }}
            >
              {content}
            </pre>
          </Typography.Paragraph>
        )}
      </Drawer>

      {/* 视频/音频弹窗 */}
      <Modal
        title={null}
        open={!!viewing && ['video', 'audio'].includes(previewKind(viewing) ?? '')}
        onCancel={closeModal}
        footer={null}
        centered
        destroyOnHidden
        closable={false}
        width={isVideo ? (modalSize.w || Math.min(window.innerWidth * 0.9, 1280)) : 480}
        styles={
          isVideo
            ? { content: { background: '#000', padding: 0, borderRadius: 12, overflow: 'hidden' },
                body: { padding: 0 } }
            : undefined
        }
      >
        {isVideo ? (
          <div
            ref={videoWrapRef}
            style={{
              position: 'relative',
              background: '#000',
              height: modalSize.h || Math.min(window.innerHeight * 0.82, 720),
              display: 'flex',
              flexDirection: 'column',
            }}
          >
            {/* 顶部标题栏（悬浮，关闭按钮） */}
            <div
              style={{
                position: 'absolute', top: 0, left: 0, right: 0, zIndex: 10,
                display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                padding: '10px 14px',
                background: 'linear-gradient(rgba(0,0,0,0.6), transparent)',
              }}
            >
              <span style={{ color: '#fff', fontSize: 14, fontWeight: 500, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1, marginRight: 12 }}>
                {viewing?.fileName}
              </span>
              <CloseOutlined
                onClick={closeModal}
                style={{ color: 'rgba(255,255,255,0.85)', fontSize: 18, cursor: 'pointer', padding: 4 }}
              />
            </div>

            {playReady === 'processing' ? (
              <div style={centerStyle}>
                <Spin />
                <div style={{ marginTop: 12 }}>正在转码，转好后自动开播…</div>
                <Progress percent={transcodePct} size="small" style={{ width: 280, margin: '12px auto 0' }} strokeColor="#f5222d" />
                <Button danger size="small" style={{ marginTop: 16 }} onClick={() => viewing && stopTranscode(viewing.id)}>停止转码</Button>
              </div>
            ) : playReady === 'idle' ? (
              <div style={centerStyle}>
                <div style={{ marginBottom: 16 }}>该视频尚未转码，转码后才能在线播放</div>
                <Button type="primary" onClick={startTranscode}>开始转码</Button>
              </div>
            ) : playReady === 'failed' ? (
              <div style={centerStyle}>
                <div style={{ marginBottom: 16 }}>转码失败，无法在线播放，请下载后观看</div>
                <Button onClick={startTranscode}>重新转码</Button>
              </div>
            ) : (
              <>
                <video
                  ref={(el) => { mediaRef.current = el; }}
                  autoPlay
                  preload="metadata"
                  playsInline
                  src={playHls ? undefined : `/api/v1/library/files/${viewing!.id}/stream?token=${encodeURIComponent(token ?? '')}`}
                  onPlay={() => setPaused(false)}
                  onPause={() => setPaused(true)}
                  onClick={togglePlay}
                  onTimeUpdate={(e) => {
                    const v = e.currentTarget;
                    setCurTime(v.currentTime);
                    try {
                      if (v.buffered.length > 0) setBufEnd(v.buffered.end(v.buffered.length - 1));
                    } catch { /* ignore */ }
                  }}
                  onLoadedMetadata={(e) => setDuration(e.currentTarget.duration || 0)}
                  onVolumeChange={(e) => { setVolume(e.currentTarget.volume); setMuted(e.currentTarget.muted); }}
                  style={{ flex: 1, width: '100%', background: '#000', objectFit: 'contain', cursor: 'pointer' }}
                />

                {/* 底部控制区：进度条 + 控制按钮 */}
                <div
                  style={{
                    position: 'absolute', bottom: 0, left: 0, right: 0, zIndex: 10,
                    background: 'linear-gradient(transparent, rgba(0,0,0,0.75))',
                    padding: '24px 12px 8px',
                  }}
                >
                  {/* 进度条：整宽，hover 变粗，可点击拖动 */}
                  <SeekBar
                    cur={curTime}
                    dur={duration}
                    buf={bufEnd}
                    onSeek={(s) => {
                      const v = mediaRef.current as HTMLVideoElement | null;
                      if (v) { v.currentTime = s; setCurTime(s); }
                    }}
                  />
                  {/* 控制按钮行 */}
                  <div style={{ display: 'flex', alignItems: 'center', gap: 2, marginTop: 2 }}>
                    {/* 播放/暂停（居中） */}
                    <CtrlBtn title={paused ? '播放 (空格)' : '暂停 (空格)'} onClick={togglePlay}>
                      {paused ? <CaretRightOutlined /> : <PauseOutlined />}
                    </CtrlBtn>
                    {/* 快退 10s（最左） */}
                    <CtrlBtn title="快退 10 秒 (←)" onClick={() => seekBy(-10)}>
                      <SkipIcon dir={-1} />
                    </CtrlBtn>
                    {/* 快进 10s（播放按钮右侧） */}
                    <CtrlBtn title="快进 10 秒 (→)" onClick={() => seekBy(10)}>
                      <SkipIcon dir={1} />
                    </CtrlBtn>
                    {/* 音量 */}
                    <div
                      style={{ display: 'flex', alignItems: 'center' }}
                      onMouseEnter={() => setVolOpen(true)}
                      onMouseLeave={() => setVolOpen(false)}
                    >
                      <CtrlBtn
                        title={muted || volume === 0 ? '取消静音' : '静音'}
                        onClick={() => {
                          const v = mediaRef.current as HTMLVideoElement | null;
                          if (v) v.muted = !v.muted;
                        }}
                      >
                        {muted || volume === 0 ? <AudioMutedOutlined /> : <SoundOutlined />}
                      </CtrlBtn>
                      <div style={{ width: volOpen ? 72 : 0, overflow: 'hidden', transition: 'width 0.2s ease', display: 'flex', alignItems: 'center' }}>
                        <Slider
                          min={0} max={1} step={0.05}
                          value={muted ? 0 : volume}
                          tooltip={{ open: false }}
                          onChange={(nv) => {
                            const v = mediaRef.current as HTMLVideoElement | null;
                            if (v) { v.volume = nv; v.muted = nv === 0; }
                          }}
                          style={{ flex: 1, margin: '0 8px' }}
                        />
                      </div>
                    </div>
                    <span style={{ color: 'rgba(255,255,255,0.9)', fontSize: 12, marginLeft: 8, fontVariantNumeric: 'tabular-nums', userSelect: 'none' }}>
                      {formatDuration(curTime)} / {formatDuration(duration)}
                    </span>
                    <div style={{ flex: 1 }} />
                    {/* 倍速 */}
                    <Dropdown
                      trigger={['click']}
                      menu={{
                        selectedKeys: [String(speed)],
                        onClick: ({ key }) => setSpeed(Number(key)),
                        items: [0.5, 0.75, 1, 1.25, 1.5, 2].map((v) => ({ key: String(v), label: `${v}x` })),
                      }}
                    >
                      <span style={pillStyle}>倍速 {speed}x</span>
                    </Dropdown>
                    {/* 清晰度（HLS 时显示）：标签优先用后端探测的分辨率，单档时直接展示 */}
                    {playHls && (() => {
                      const resLabel = videoRes.h ? `${videoRes.h}P` : null;
                      // 单档 HLS：无需选择，仅展示分辨率标签
                      if (levels.length <= 1) {
                        return resLabel ? <span style={pillStyle}>{resLabel}</span> : null;
                      }
                      const items = [
                        { key: '-1', label: resLabel ? `自动（${resLabel}）` : '自动' },
                        ...levels.map((l) => ({
                          key: String(l.value),
                          label: l.label.endsWith('k') && resLabel ? resLabel : l.label,
                        })),
                      ];
                      return (
                        <Dropdown
                          trigger={['click']}
                          menu={{
                            selectedKeys: [String(quality)],
                            onClick: ({ key }) => {
                              const v = Number(key);
                              setQuality(v);
                              if (hlsRef.current) hlsRef.current.currentLevel = v;
                            },
                            items,
                          }}
                        >
                          <span style={pillStyle}>{quality === -1 ? (resLabel ? `自动 ${resLabel}` : '自动') : items.find((l) => l.key === String(quality))?.label}</span>
                        </Dropdown>
                      );
                    })()}
                    {/* 下载需二次确认，避免误触大文件直接下载 */}
                    <Popconfirm
                      title="下载原视频？"
                      okText="下载"
                      cancelText="取消"
                      onConfirm={() => viewing && handleDownload(viewing)}
                    >
                      <span style={{ display: 'inline-flex' }}>
                        <CtrlBtn title="下载原视频">
                          <DownloadOutlined />
                        </CtrlBtn>
                      </span>
                    </Popconfirm>
                    <CtrlBtn title={isFullscreen ? '退出全屏 (f)' : '全屏 (f)'} onClick={toggleFullscreen}>
                      {isFullscreen ? <FullscreenExitOutlined /> : <FullscreenOutlined />}
                    </CtrlBtn>
                  </div>
                </div>
                {/* 四角伸缩手柄：完全透明，不遮挡内容 */}
                {[
                  { c: 'nw', cx: 0, cy: 0, cur: 'nwse-resize' },
                  { c: 'ne', cx: 1, cy: 0, cur: 'nesw-resize' },
                  { c: 'sw', cx: 0, cy: 1, cur: 'nesw-resize' },
                  { c: 'se', cx: 1, cy: 1, cur: 'nwse-resize' },
                ].map((h) => (
                  <div
                    key={h.c}
                    onMouseDown={(e) => onResizeStart(e, h.cx, h.cy)}
                    title="拖拽调整窗口大小"
                    style={{
                      position: 'absolute',
                      left: h.cx === 0 ? 0 : undefined,
                      right: h.cx === 1 ? 0 : undefined,
                      top: h.cy === 0 ? 0 : undefined,
                      bottom: h.cy === 1 ? 0 : undefined,
                      width: 18,
                      height: 18,
                      zIndex: 20,
                      cursor: h.cur,
                      background: 'transparent',
                    }}
                  />
                ))}
              </>
            )}
          </div>
        ) : viewing ? (
          <div style={{ padding: '12px 0' }}>
            <audio
              ref={(el) => { mediaRef.current = el; }}
              controls
              autoPlay
              preload="metadata"
              src={`/api/v1/library/files/${viewing.id}/stream?token=${encodeURIComponent(token ?? '')}`}
              style={{ width: '100%' }}
            />
            <Space style={{ marginTop: 12 }}>
              <span>倍速</span>
              <Select
                size="small"
                value={speed}
                style={{ width: 90 }}
                options={[0.5, 0.75, 1, 1.25, 1.5, 2].map((v) => ({ value: v, label: `${v}x` }))}
                onChange={setSpeed}
              />
            </Space>
          </div>
        ) : null}
      </Modal>
    </Card>
  );
}

const centerStyle: React.CSSProperties = {
  textAlign: 'center',
  flex: 1,
  color: '#aaa',
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  justifyContent: 'center',
};

/** 控制条上的文字型按钮（倍速/清晰度）。 */
const pillStyle: React.CSSProperties = {
  color: 'rgba(255,255,255,0.92)',
  fontSize: 13,
  padding: '4px 10px',
  borderRadius: 6,
  cursor: 'pointer',
  userSelect: 'none',
  whiteSpace: 'nowrap',
  background: 'rgba(255,255,255,0.08)',
  margin: '0 2px',
  fontVariantNumeric: 'tabular-nums',
};

/** 控制条图标按钮：透明圆底，hover 高亮。 */
function CtrlBtn({ title, onClick, children }: { title: string; onClick?: () => void; children: React.ReactNode }) {
  const [hov, setHov] = useState(false);
  return (
    <span
      title={title}
      onClick={onClick}
      onMouseEnter={() => setHov(true)}
      onMouseLeave={() => setHov(false)}
      style={{
        display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
        width: 34, height: 34, borderRadius: '50%', cursor: 'pointer',
        color: '#fff', fontSize: 17,
        background: hov ? 'rgba(255,255,255,0.18)' : 'transparent',
        transition: 'background 0.15s ease',
      }}
    >
      {children}
    </span>
  );
}

/** 快进/快退 10 秒图标（Material 风格：整圆逆时针箭头 + 10 字样，dir=1 快退，dir=-1 快进）。 */
function SkipIcon({ dir }: { dir: 1 | -1 }) {
  const flip = dir === 1;
  return (
    <svg width="22" height="22" viewBox="0 0 24 24"
      style={{ transform: flip ? 'scaleX(-1)' : undefined }}>
      {/* 圆环箭头：逆时针为快退（不翻转），水平翻转后顺时针为快进 */}
      <path d="M12 5V1L7 6l5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6H4c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8z" fill="currentColor" />
      {/* 10 字样：图标整体镜像时文字需再反向镜像保持正字 */}
      <text x="12" y="15.5" textAnchor="middle" fontSize="7.5" fill="currentColor" fontWeight="700"
        transform={flip ? 'translate(24,0) scale(-1,1)' : undefined}>10</text>
    </svg>
  );
}

/** 可拖动进度条：细线常态，hover 变粗出圆点，点击/拖动跳转。 */
function SeekBar({ cur, dur, buf, onSeek }: { cur: number; dur: number; buf: number; onSeek: (sec: number) => void }) {
  const barRef = useRef<HTMLDivElement | null>(null);
  const [hov, setHov] = useState(false);
  const [dragging, setDragging] = useState(false);
  const [hovX, setHovX] = useState(0);

  const pct = dur > 0 ? Math.min(100, (cur / dur) * 100) : 0;
  const bufPct = dur > 0 ? Math.min(100, (buf / dur) * 100) : 0;

  const posToSec = (clientX: number): number => {
    const el = barRef.current;
    if (!el || dur <= 0) return 0;
    const r = el.getBoundingClientRect();
    const ratio = Math.max(0, Math.min(1, (clientX - r.left) / r.width));
    return ratio * dur;
  };

  const onDown = (e: React.MouseEvent) => {
    e.preventDefault();
    setDragging(true);
    onSeek(posToSec(e.clientX));
    const onMove = (ev: MouseEvent) => onSeek(posToSec(ev.clientX));
    const onUp = (ev: MouseEvent) => {
      onSeek(posToSec(ev.clientX));
      setDragging(false);
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
  };

  const showThumb = hov || dragging;
  return (
    <div
      ref={barRef}
      onMouseDown={onDown}
      onMouseEnter={() => setHov(true)}
      onMouseLeave={() => setHov(false)}
      onMouseMove={(e) => {
        const el = barRef.current;
        if (el) setHovX(e.clientX - el.getBoundingClientRect().left);
      }}
      style={{ position: 'relative', height: 14, display: 'flex', alignItems: 'center', cursor: 'pointer', userSelect: 'none' }}
    >
      {/* 轨道 */}
      <div style={{
        position: 'relative', width: '100%', height: showThumb ? 5 : 3,
        background: 'rgba(255,255,255,0.25)', borderRadius: 3,
        transition: 'height 0.12s ease',
      }}>
        {/* 缓冲 */}
        <div style={{ position: 'absolute', left: 0, top: 0, bottom: 0, width: `${bufPct}%`, background: 'rgba(255,255,255,0.35)', borderRadius: 3 }} />
        {/* 已播放 */}
        <div style={{ position: 'absolute', left: 0, top: 0, bottom: 0, width: `${pct}%`, background: '#f5222d', borderRadius: 3 }} />
        {/* 圆点手柄 */}
        {showThumb && (
          <div style={{
            position: 'absolute', top: '50%', left: `${pct}%`,
            width: 12, height: 12, borderRadius: '50%', background: '#f5222d',
            transform: 'translate(-50%, -50%)',
          }} />
        )}
      </div>
      {/* hover 时间提示 */}
      {hov && dur > 0 && (
        <div style={{
          position: 'absolute', bottom: 18, left: hovX, transform: 'translateX(-50%)',
          background: 'rgba(0,0,0,0.85)', color: '#fff', fontSize: 12,
          padding: '2px 6px', borderRadius: 4, pointerEvents: 'none',
          fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap',
        }}>
          {formatDuration(posToSec((barRef.current?.getBoundingClientRect().left ?? 0) + hovX))}
        </div>
      )}
    </div>
  );
}
