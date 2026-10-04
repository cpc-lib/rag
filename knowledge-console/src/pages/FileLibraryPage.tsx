import { useEffect, useRef, useState } from 'react';
import { App, Button, Card, Drawer, Input, Modal, Popconfirm, Progress, Select, Space, Spin, Table, Tag, Typography, Upload } from 'antd';
import { DeleteOutlined, DownloadOutlined, EyeOutlined, ReloadOutlined, SearchOutlined, UploadOutlined } from '@ant-design/icons';
import Hls from 'hls.js';
import { libraryApi } from '../api/library';
import { uploadApi } from '../api/uploads';
import type { LibraryFile } from '../api/types';
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

const IMAGE_EXT = ['png', 'jpg', 'jpeg'];
const TEXT_EXT = ['txt', 'md', 'html', 'htm'];
const VIDEO_EXT = ['mp4', 'mkv', 'avi', 'ts'];
const AUDIO_EXT = ['mp3', 'flac'];

function extOf(name: string): string {
  const i = name.lastIndexOf('.');
  return i >= 0 ? name.slice(i + 1).toLowerCase() : '';
}

/** 预览形态：图片走 Blob 预览，文本读全文，音视频走 /stream 流式播放；pdf/docx/xlsx 等二进制不支持在线查看（仅下载）。 */
function previewKind(f: LibraryFile): 'image' | 'text' | 'video' | 'audio' | null {
  if (f.bizType === 'IMAGE') return 'image';
  if (f.bizType === 'SUBTITLE') return 'text';
  // 知识库文档与直接上传文件按扩展名判断
  const ext = extOf(f.fileName);
  if (IMAGE_EXT.includes(ext)) return 'image';
  if (VIDEO_EXT.includes(ext)) return 'video';
  if (AUDIO_EXT.includes(ext)) return 'audio';
  return TEXT_EXT.includes(ext) ? 'text' : null;
}

/** 文件库：字幕保存等操作归档的文件列表，支持直接上传、查看文本内容与下载。 */
export default function FileLibraryPage() {
  const { message } = App.useApp();
  const [files, setFiles] = useState<LibraryFile[]>([]);
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  /** 分片上传进度（百分比），null 表示空闲 */
  const [percent, setPercent] = useState<number | null>(null);
  /** 查看：纯只读全文预览（所有文件通用） */
  const [viewing, setViewing] = useState<LibraryFile | null>(null);
  /** 编辑：字幕文件的条目编辑器 */
  const [editing, setEditing] = useState<LibraryFile | null>(null);
  const [content, setContent] = useState('');
  const [loadingContent, setLoadingContent] = useState(false);
  /** 图片查看：归档图片的本地 Blob 预览地址 */
  const [imageUrl, setImageUrl] = useState('');
  /** 文件名模糊查询关键字（后端 like 查询） */
  const [kw, setKw] = useState('');
  /** 媒体播放：倍速（0.5x~2x），通过 playbackRate 应用 */
  const [speed, setSpeed] = useState(1);
  /** 播放就绪状态：idle=未转码，processing=转码中（有进度），ready=可播放，failed=转码失败 */
  const [playReady, setPlayReady] = useState<'idle' | 'processing' | 'ready' | 'failed'>('processing');
  /** 转码进度 0-100（PROCESSING 时由 WebSocket 推送更新） */
  const [transcodePct, setTranscodePct] = useState(0);
  /** 产物为 HLS 时经 hls.js 播放（支持清晰度切换）；false 走 /stream 直读（音频/旧版 mp4 产物） */
  const [playHls, setPlayHls] = useState(false);
  /** HLS 清晰度档位（由 master.m3u8 解析），quality=-1 表示自动 */
  const [levels, setLevels] = useState<{ value: number; label: string }[]>([]);
  const [quality, setQuality] = useState(-1);
  const mediaRef = useRef<HTMLMediaElement | null>(null);
  const hlsRef = useRef<Hls | null>(null);
  const token = useAuthStore((s) => s.token);
  /** 行内转码任务状态：id → {status, progress}，由 WebSocket 进度事件驱动 */
  const [transcodeJobs, setTranscodeJobs] = useState<Record<number, { status: string; progress: number }>>({});
  /** WebSocket 单连接：订阅多个 fileId 的转码进度事件 */
  const wsRef = useRef<WebSocket | null>(null);
  const subscribedRef = useRef<Set<number>>(new Set());
  /** 供 WS 回调读取最新状态（避免闭包过期） */
  const viewingIdRef = useRef<number | null>(null);
  const kwRef = useRef('');
  viewingIdRef.current = viewing?.id ?? null;
  kwRef.current = kw;

  /** WS 进度事件：{fileId, status, progress(-1 表示无)}；驱动行内任务与播放弹窗状态 */
  const handleProgress = (m: { fileId: number; status: string; progress: number }) => {
    const pct = m.progress >= 0 ? m.progress : 0;
    if (m.status === 'PROCESSING') {
      setTranscodeJobs((jobs) => ({ ...jobs, [m.fileId]: { status: 'PROCESSING', progress: pct } }));
      if (viewingIdRef.current === m.fileId) setTranscodePct(pct);
      return;
    }
    // READY / FAILED / NONE（用户停止）：任务结束，移除任务标记并刷新列表
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

  /** 建立（或复用）WebSocket 连接；断线后若仍有订阅则 2 秒重连 */
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
        // 非预期消息忽略
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

  /** 订阅文件转码进度（连接未建立时先建立，onopen 统一补发订阅） */
  const subscribeFile = (id: number) => {
    subscribedRef.current.add(id);
    ensureWs();
    sendSub(id);
  };

  // 页面卸载：关闭 WS 并清空订阅
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
      // 页面加载/刷新后，对已处于转码中的文件自动建立 WS 订阅，恢复进度显示
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

  // 首次加载
  useEffect(() => {
    load();
  }, []);

  // 关键字防抖触发后端模糊查询
  useEffect(() => {
    const timer = setTimeout(() => load(kw), 300);
    return () => clearTimeout(timer);
  }, [kw]);

  // 只读查看：文本文件加载整个文本；图片文件下载字节生成 Blob 预览；音视频走流式播放不预载
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
      // 视频订阅转码进度（WebSocket 推送），并取一次当前状态快照；NONE/FAILED 由用户手动开始/重新转码
      if (kind === 'video') subscribeFile(viewing.id);
      let stopped = false;
      libraryApi
        .playback(viewing.id)
        .then((st) => {
          if (stopped) return;
          if (st.status === 'NATIVE' || st.status === 'READY') {
            setPlayHls(st.status === 'READY' && st.hls);
            setPlayReady('ready');
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
        ? libraryApi
            .download(viewing.id)
            .then((blob) => {
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

  /** 开始/重新转码（弹窗内）：订阅进度事件 + 投递 MQ，后续由 WebSocket 推送驱动 */
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

  /** 行内开始/重新转码（仅视频文件显示该按钮）：订阅进度事件 + 投递 MQ */
  const startRowTranscode = async (f: LibraryFile) => {
    try {
      subscribeFile(f.id);
      const st = await libraryApi.transcode(f.id);
      setTranscodeJobs((m) => ({ ...m, [f.id]: { status: 'PROCESSING', progress: st.progress ?? 0 } }));
    } catch {
      message.error('投递转码失败');
    }
  };

  /** 停止转码（行内与弹窗共用）：状态回 NONE，Worker 强杀 ffmpeg */
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

  // 倍速应用：媒体元素挂载/倍速变化时同步 playbackRate
  useEffect(() => {
    if (mediaRef.current) mediaRef.current.playbackRate = speed;
  }, [speed, viewing]);

  // HLS 播放：转码产物就绪后由 hls.js 接管（同源经 Vite 代理，xhrSetup 注入鉴权头）；
  // Safari 等原生支持 HLS 的浏览器直接设 src。关闭弹窗/切换文件时销毁实例。
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
      // 致命错误（产物缺失/网络异常）提示失败，非致命由 hls.js 自行恢复
      if (data.fatal) setPlayReady('failed');
    });
    return () => {
      hls.destroy();
      hlsRef.current = null;
    };
  }, [playReady, playHls, viewing, token]);

  const handleDownload = async (f: LibraryFile) => {
    const blob = await libraryApi.download(f.id);
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = f.fileName;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  };

  /**
   * 分片上传：init → 逐片上传 → complete，会话落库。
   * 断点续传：以 文件名+大小 为键在 localStorage 记忆会话，失败/刷新后重传自动跳过已传分片。
   */
  const handleUpload = async (file: File) => {
    setUploading(true);
    setPercent(0);
    try {
      const key = `upload-session:${file.name}:${file.size}`;
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
          sessionId = 0; // 会话不存在或已失效，重新初始化
        }
      }
      if (!sessionId) {
        const r = await uploadApi.init(file.name, file.size, file.type || 'application/octet-stream');
        sessionId = r.sessionId;
        chunkSize = r.chunkSize;
        localStorage.setItem(key, String(sessionId));
      }
      const total = Math.ceil(file.size / chunkSize);
      if (done.size > 0) setPercent(Math.round((done.size / total) * 100));
      for (let i = 0; i < total; i++) {
        const part = i + 1;
        if (done.has(part)) continue;
        await uploadApi.uploadPart(sessionId, part, file.slice(i * chunkSize, (i + 1) * chunkSize));
        done.add(part);
        setPercent(Math.round((done.size / total) * 100));
      }
      await uploadApi.complete(sessionId);
      localStorage.removeItem(key);
      message.success('上传成功');
      await load(kw);
    } catch {
      message.error('上传失败，重新上传将自动从断点续传');
    } finally {
      setUploading(false);
      setPercent(null);
    }
    return false; // 阻止 antd 自动上传
  };

  const handleDelete = async (f: LibraryFile) => {
    await libraryApi.remove(f.id);
    message.success('文件已删除');
    // 正在查看/编辑的文件被删除时关闭抽屉
    if (viewing?.id === f.id) setViewing(null);
    if (editing?.id === f.id) setEditing(null);
    await load(kw);
  };

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
          <Upload
            name="file"
            multiple={false}
            showUploadList={false}
            beforeUpload={handleUpload}
            disabled={uploading}
          >
            <Button type="primary" icon={<UploadOutlined />} loading={uploading}>
              上传文件
            </Button>
          </Upload>
          {percent !== null && (
            <Progress percent={percent} size="small" style={{ width: 160, margin: 0 }} />
          )}
          <Button icon={<ReloadOutlined />} onClick={() => load(kw)}>
            刷新
          </Button>
        </Space>
      }
      style={{ height: '100%' }}
    >
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
                {/* 视频转码控制：仅 mp4/mkv/avi/ts 显示；READY 后隐藏（查看即可播放） */}
                {VIDEO_EXT.includes(extOf(f.fileName)) &&
                  (() => {
                    const job = transcodeJobs[f.id];
                    const status = job?.status ?? f.playbackStatus;
                    if (status === 'READY') return null;
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
                    <Button size="small" danger icon={<DeleteOutlined />}>
                      删除
                    </Button>
                  </Popconfirm>
                )}
              </Space>
            ),
          },
        ]}
      />
      {/* 字幕文件：条目式编辑器，支持检索与直接修改，保存原地覆盖该文件 */}
      <SubtitleEditDrawer
        open={!!editing}
        fileId={editing?.id ?? 0}
        subtitleId={editing?.subtitleId ?? 0}
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
        destroyOnClose
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
      {/* 音视频弹窗播放：大窗居中，原生控制条自带全屏按钮；avi/ts 首次播放先转码 */}
      <Modal
        title={viewing?.fileName}
        open={!!viewing && ['video', 'audio'].includes(previewKind(viewing) ?? '')}
        onCancel={() => setViewing(null)}
        footer={null}
        centered
        destroyOnClose
        width={viewing && previewKind(viewing) === 'video' ? '94vw' : 480}
        styles={viewing && previewKind(viewing) === 'video' ? { content: { background: '#111', padding: 12 }, header: { background: '#111', color: '#fff' } } : undefined}
      >
        {viewing && previewKind(viewing) === 'video' ? (
          playReady === 'processing' ? (
            <div style={{ textAlign: 'center', height: 'min(50vw, 76vh)', background: '#000', borderRadius: 6, color: '#aaa', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center' }}>
              <Spin />
              <div style={{ marginTop: 12 }}>正在转码，转好后自动开播…</div>
              <Progress percent={transcodePct} size="small" style={{ width: 280, margin: '12px auto 0' }} strokeColor="#1677ff" />
              <Button danger size="small" style={{ marginTop: 16 }} onClick={() => viewing && stopTranscode(viewing.id)}>停止转码</Button>
            </div>
          ) : playReady === 'idle' ? (
            <div style={{ textAlign: 'center', height: 'min(50vw, 76vh)', background: '#000', borderRadius: 6, color: '#aaa', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center' }}>
              <div style={{ marginBottom: 16 }}>该视频尚未转码，转码后才能在线播放</div>
              <Button type="primary" onClick={startTranscode}>开始转码</Button>
            </div>
          ) : playReady === 'failed' ? (
            <div style={{ textAlign: 'center', height: 'min(50vw, 76vh)', background: '#000', borderRadius: 6, color: '#aaa', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center' }}>
              <div style={{ marginBottom: 16 }}>转码失败，无法在线播放，请下载后观看</div>
              <Button onClick={startTranscode}>重新转码</Button>
            </div>
          ) : (
            <div>
              <video
                ref={(el) => {
                  mediaRef.current = el;
                }}
                controls
                autoPlay
                preload="metadata"
                src={playHls ? undefined : `/api/v1/library/files/${viewing.id}/stream?token=${encodeURIComponent(token ?? '')}`}
                style={{ width: '100%', maxHeight: '82vh', background: '#000', borderRadius: 6 }}
              />
              <Space style={{ marginTop: 12, color: '#ddd' }}>
                <span>倍速</span>
                <Select
                  size="small"
                  value={speed}
                  style={{ width: 90 }}
                  options={[0.5, 0.75, 1, 1.25, 1.5, 2].map((v) => ({ value: v, label: `${v}x` }))}
                  onChange={setSpeed}
                />
                {playHls && levels.length > 0 && (
                  <>
                    <span>清晰度</span>
                    <Select
                      size="small"
                      value={quality}
                      style={{ width: 100 }}
                      options={[{ value: -1, label: '自动' }, ...levels]}
                      onChange={(v) => {
                        setQuality(v);
                        if (hlsRef.current) hlsRef.current.currentLevel = v;
                      }}
                    />
                  </>
                )}
              </Space>
            </div>
          )
        ) : viewing ? (
          <div style={{ padding: '12px 0' }}>
            <audio
              ref={(el) => {
                mediaRef.current = el;
              }}
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
