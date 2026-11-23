import { useEffect, useRef, useState } from 'react';
import {
  App, Badge, Button, Card, Input, Popconfirm, Popover, Progress, Space, Table, Tabs, Tag, Typography, Upload,
} from 'antd';
import {
  CaretRightOutlined, CloudUploadOutlined, DeleteOutlined, DownloadOutlined, EyeOutlined, ReloadOutlined, SearchOutlined, UploadOutlined,
} from '@ant-design/icons';
import { libraryApi } from '../api/library';
import type { LibraryFile, PlaybackHistory } from '../api/types';
import { useAuthStore } from '../store/auth';
import SubtitleEditDrawer from '../components/SubtitleEditDrawer';
import DocumentPreviewModal from '../components/library/DocumentPreviewModal';
import VideoPlayerModal from '../components/library/VideoPlayerModal';
import { extOf, formatDuration, previewKind, VIDEO_EXT, type MediaProgressEvent } from '../components/library/media';
import { useMultipartUpload } from '../hooks/useMultipartUpload';

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
}

function formatTime(v: string): string {
  return new Date(v).toLocaleString('zh-CN', { hour12: false });
}

export default function FileLibraryPage() {
  const { message } = App.useApp();
  const [files, setFiles] = useState<LibraryFile[]>([]);
  const [loading, setLoading] = useState(false);
  const [viewing, setViewing] = useState<LibraryFile | null>(null);
  const [editing, setEditing] = useState<LibraryFile | null>(null);
  const [kw, setKw] = useState('');
  const token = useAuthStore((s) => s.token);
  const [transcodeJobs, setTranscodeJobs] = useState<Record<number, { status: string; progress: number }>>({});
  const wsRef = useRef<WebSocket | null>(null);
  const subscribedRef = useRef<Set<number>>(new Set());
  const viewingIdRef = useRef<number | null>(null);
  const kwRef = useRef('');
  viewingIdRef.current = viewing?.id ?? null;
  kwRef.current = kw;

  /** 播放记录 */
  const [history, setHistory] = useState<PlaybackHistory[]>([]);
  const [activeTab, setActiveTab] = useState<'all' | 'history'>('all');

  /** 页面 WS 收到的最近一次转码进度事件，转发给视频弹窗 */
  const [progressEvent, setProgressEvent] = useState<MediaProgressEvent | null>(null);

  const { uploads, beforeUpload } = useMultipartUpload({ onCompleted: () => load(kw) });

  const handleProgress = (m: MediaProgressEvent) => {
    setProgressEvent(m);
    const pct = m.progress >= 0 ? m.progress : 0;
    if (m.status === 'PROCESSING') {
      setTranscodeJobs((jobs) => ({ ...jobs, [m.fileId]: { status: 'PROCESSING', progress: pct } }));
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

  /** 下载原文件：浏览器原生流式下载（?token= 鉴权），大视频不经过内存 Blob，避免超时。 */
  const handleDownload = (f: LibraryFile) => {
    const a = document.createElement('a');
    a.href = `/api/v1/library/files/${f.id}/download?token=${encodeURIComponent(token ?? '')}`;
    a.download = f.fileName;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
  };

  /** 投递转码：订阅 WS 并写回任务进度；失败由调用方决定提示文案 */
  const requestTranscode = async (id: number): Promise<boolean> => {
    try {
      subscribeFile(id);
      const st = await libraryApi.transcode(id);
      setTranscodeJobs((m) => ({ ...m, [id]: { status: 'PROCESSING', progress: st.progress ?? 0 } }));
      return true;
    } catch {
      return false;
    }
  };

  const startRowTranscode = async (f: LibraryFile) => {
    const ok = await requestTranscode(f.id);
    if (!ok) message.error('投递转码失败');
  };

  const stopTranscode = async (id: number): Promise<boolean> => {
    try {
      await libraryApi.stopTranscode(id);
    } catch {
      message.error('停止转码失败');
      return false;
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
    message.success('已停止转码');
    load(kwRef.current);
    return true;
  };

  const handleDelete = async (f: LibraryFile) => {
    await libraryApi.remove(f.id);
    message.success('文件已删除');
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
          <Button icon={<ReloadOutlined />} loading={loading} onClick={() => load(kw)}>
            刷新
          </Button>
          <Upload
            name="file"
            multiple
            showUploadList={false}
            beforeUpload={beforeUpload}
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
                      <div style={{ width: 160 }}>
                        {u.tip ? (
                          <Tag style={{ fontSize: 11, marginInlineEnd: 0 }}>{u.tip}</Tag>
                        ) : (
                          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                            <Progress percent={u.percent} size="small" style={{ margin: 0, flex: 1 }} />
                            <Typography.Text type="secondary" style={{ fontSize: 11, whiteSpace: 'nowrap' }}>
                              {u.speed ?? ''}
                            </Typography.Text>
                          </div>
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

      {viewing && previewKind(viewing) === 'video' && (
        <VideoPlayerModal
          key={viewing.id}
          file={viewing}
          token={token}
          progressEvent={progressEvent}
          onClose={() => setViewing(null)}
          onSubscribe={subscribeFile}
          onStartTranscode={requestTranscode}
          onStopTranscode={stopTranscode}
        />
      )}
      {viewing && previewKind(viewing) !== 'video' && (
        <DocumentPreviewModal key={viewing.id} file={viewing} token={token} onClose={() => setViewing(null)} />
      )}
    </Card>
  );
}
