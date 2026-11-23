import { useEffect, useRef, useState } from 'react';
import { Button, Dropdown, Modal, Popconfirm, Progress, Slider, Spin } from 'antd';
import {
  AudioMutedOutlined, CaretRightOutlined, CloseOutlined, DownloadOutlined, FullscreenOutlined, FullscreenExitOutlined,
  PauseOutlined, SoundOutlined,
} from '@ant-design/icons';
import Hls from 'hls.js';
import { libraryApi } from '../../api/library';
import type { LibraryFile } from '../../api/types';
import { formatDuration, type MediaProgressEvent, type PlaybackPhase } from './media';

interface VideoPlayerModalProps {
  file: LibraryFile;
  token: string | null;
  /** 页面 WS 收到的转码进度；仅处理当前文件的事件 */
  progressEvent: MediaProgressEvent | null;
  onClose: () => void;
  onSubscribe: (fileId: number) => void;
  onStartTranscode: (fileId: number) => Promise<boolean>;
  onStopTranscode: (fileId: number) => Promise<boolean>;
}

/** 视频播放弹窗：HLS/直连、清晰度、全屏、窗口缩放、快捷键与恢复进度。 */
export default function VideoPlayerModal({
  file, token, progressEvent, onClose, onSubscribe, onStartTranscode, onStopTranscode,
}: VideoPlayerModalProps) {
  const [speed, setSpeed] = useState(1);
  const [playReady, setPlayReady] = useState<PlaybackPhase>('processing');
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

  /** 关闭弹窗时保存当前播放进度（每次播放会话产生一条新记录）。 */
  const saveProgress = () => {
    const v = mediaRef.current as HTMLVideoElement | null;
    if (!v) return;
    const pos = Math.round(v.currentTime * 1000);
    const dur = Math.round((v.duration || 0) * 1000);
    if (pos < 1000) return;
    libraryApi.savePlaybackPosition(file.id, pos, dur).catch(() => {});
  };

  const closeModal = () => {
    saveProgress();
    onClose();
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

  /** 键盘快捷键 */
  useEffect(() => {
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
  }, [file.id]);

  // 打开文件：重置状态并读取转码/播放进度
  useEffect(() => {
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
    onSubscribe(file.id);
    let stopped = false;
    libraryApi
      .playback(file.id)
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
  }, [file.id]);

  // 转码进度事件（页面 WS 转发）
  useEffect(() => {
    if (!progressEvent || progressEvent.fileId !== file.id) return;
    const pct = progressEvent.progress >= 0 ? progressEvent.progress : 0;
    if (progressEvent.status === 'PROCESSING') {
      setPlayReady('processing');
      setTranscodePct(pct);
    } else if (progressEvent.status === 'READY') {
      setPlayHls(true);
      setPlayReady('ready');
    } else if (progressEvent.status === 'NONE') {
      setPlayReady('idle');
    } else {
      setPlayReady('failed');
    }
  }, [progressEvent, file.id]);

  const startTranscode = async () => {
    onSubscribe(file.id);
    setPlayReady('processing');
    setTranscodePct(0);
    const ok = await onStartTranscode(file.id);
    if (!ok) setPlayReady('failed');
  };

  const stopTranscode = async () => {
    const ok = await onStopTranscode(file.id);
    if (ok) setPlayReady('idle');
  };

  useEffect(() => {
    if (mediaRef.current) mediaRef.current.playbackRate = speed;
  }, [speed, file.id]);

  // HLS 播放
  useEffect(() => {
    if (playReady !== 'ready' || !playHls) return;
    const video = mediaRef.current as HTMLVideoElement | null;
    if (!video) return;
    const src = `/api/v1/library/files/${file.id}/hls/master.m3u8`;
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
  }, [playReady, playHls, file.id, token]);

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
  }, [resumeSec, playReady, playHls, file.id]);

  /** 下载原文件：浏览器原生流式下载（?token= 鉴权），不经过内存 Blob。 */
  const handleDownload = () => {
    const a = document.createElement('a');
    a.href = `/api/v1/library/files/${file.id}/download?token=${encodeURIComponent(token ?? '')}`;
    a.download = file.fileName;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
  };

  return (
    <Modal
      title={null}
      open
      onCancel={closeModal}
      footer={null}
      centered
      destroyOnHidden
      closable={false}
      width={modalSize.w || Math.min(window.innerWidth * 0.9, 1280)}
      styles={{
        content: { background: '#000', padding: 0, borderRadius: 12, overflow: 'hidden' },
        body: { padding: 0 },
      }}
    >
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
            {file.fileName}
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
            <Button danger size="small" style={{ marginTop: 16 }} onClick={stopTranscode}>停止转码</Button>
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
              src={playHls ? undefined : `/api/v1/library/files/${file.id}/stream?token=${encodeURIComponent(token ?? '')}`}
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
                  onConfirm={handleDownload}
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
    </Modal>
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
