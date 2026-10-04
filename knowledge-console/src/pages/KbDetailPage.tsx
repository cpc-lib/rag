import { useCallback, useEffect, useRef, useState } from 'react';
import {
  App,
  Button,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Progress,
  Space,
  Spin,
  Steps,
  Table,
  Tag,
  Tooltip,
  Typography,
  Upload,
} from 'antd';
import { ArrowLeftOutlined, DeleteOutlined, UploadOutlined, EyeOutlined, FileTextOutlined, ReloadOutlined } from '@ant-design/icons';
import type { UploadRequestOption } from 'rc-upload/lib/interface';
import { createSHA256 } from 'hash-wasm';
import { useNavigate, useParams } from 'react-router-dom';
import { documentApi } from '../api/documents';
import { uploadApi } from '../api/uploads';
import { chunkApi } from '../api/chunks';
import { kbApi } from '../api/knowledgeBases';
import { useAuthStore } from '../store/auth';
import type { Chunk, DocumentItem, KnowledgeBase } from '../api/types';

const ALLOWED_EXT = ['.txt', '.md', '.pdf', '.docx', '.xlsx', '.png', '.jpg', '.jpeg'];

const PIPELINE_STEPS = ['解析', '切片', '向量化', '索引'];

/** 按状态/进度推断当前步骤（0~4）。 */
function stepOf(status: string, progress: number): number {
  switch (status) {
    case 'PARSING':
      return 0;
    case 'CHUNKING':
      return 1;
    case 'EMBEDDING':
      return 2;
    case 'INDEXING':
      return 3;
    case 'READY':
      return 4;
    default:
      if (progress < 20) return 0;
      if (progress < 40) return 1;
      if (progress < 80) return 2;
      return 3;
  }
}

/** 文档处理进度：阶段步骤条 + 百分比进度条。 */
function DocProgress({ doc }: { doc: DocumentItem }) {
  const stopped = doc.status === 'STOPPED';
  const failed = doc.status === 'FAILED';
  const done = doc.status === 'READY';
  return (
    <div style={{ width: '100%' }}>
      <Steps
        size="small"
        current={stepOf(doc.status, doc.progress ?? 0)}
        status={stopped || failed ? 'error' : done ? 'finish' : 'process'}
        items={PIPELINE_STEPS.map((t) => ({ title: t }))}
        style={{ marginBottom: 6 }}
      />
      <Progress
        percent={doc.progress ?? 0}
        size="small"
        status={stopped || failed ? 'exception' : done ? 'success' : 'active'}
      />
      {(stopped || failed) && doc.errorMsg && (
        <Typography.Text type="danger" style={{ fontSize: 12, display: 'block', marginTop: 4 }}>
          {doc.errorMsg}
        </Typography.Text>
      )}
    </div>
  );
}

const TERMINAL_STATUS = new Set(['READY', 'FAILED']);
/** 处理中状态：仅这些状态需要轮询进度，UPLOADED/STOPPED 为静态状态 */
const PROCESSING_STATUS = new Set(['PARSING', 'CHUNKING', 'EMBEDDING', 'INDEXING']);

const formatSize = (bytes: number | null) => {
  if (bytes == null) return '-';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
};

export default function KbDetailPage() {
  const { id } = useParams();
  const kbId = Number(id);
  const navigate = useNavigate();
  const { message } = App.useApp();

  const [kb, setKb] = useState<KnowledgeBase | null>(null);
  const [docs, setDocs] = useState<DocumentItem[]>([]);
  const [loading, setLoading] = useState(false);
  /** 当前查看进度的文档；弹窗打开期间轮询 */
  const [progressDoc, setProgressDoc] = useState<DocumentItem | null>(null);

  // 切片抽屉状态
  const [chunkDoc, setChunkDoc] = useState<DocumentItem | null>(null);
  const [chunks, setChunks] = useState<Chunk[]>([]);
  const [chunkTotal, setChunkTotal] = useState(0);
  const [chunkPage, setChunkPage] = useState(1);
  const [chunksLoading, setChunksLoading] = useState(false);
  const [chunkModal, setChunkModal] = useState<{ mode: 'create' | 'edit'; chunk?: Chunk } | null>(null);
  const [chunkDetailLoading, setChunkDetailLoading] = useState(false);
  const [chunkForm] = Form.useForm();

  /** 本次上传会话内已处理的 SHA-256：相同内容文件只保留一个任务 */
  const seenSha256Ref = useRef<Set<string>>(new Set());
  /** 文档进度 WS 连接（弹窗打开期间持有） */
  const wsRef = useRef<WebSocket | null>(null);
  const token = useAuthStore((s) => s.token);

  const loadDocs = useCallback(
    async (showLoading = false) => {
      if (showLoading) setLoading(true);
      try {
        const page = await documentApi.list(kbId, 1, 50);
        setDocs(page.records);
        return page.records;
      } finally {
        if (showLoading) setLoading(false);
      }
    },
    [kbId],
  );

  useEffect(() => {
    kbApi.get(kbId).then(setKb);
    loadDocs(true);
  }, [kbId, loadDocs]);

  // 弹窗打开期间：WebSocket 订阅该文档进度（Worker 经 Redis 推送），关闭即断开
  useEffect(() => {
    if (!progressDoc) return;
    if (!PROCESSING_STATUS.has(progressDoc.status)) return;
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    const ws = new WebSocket(
      `${proto}://${location.host}/ws/doc-progress?token=${encodeURIComponent(token ?? '')}`,
    );
    wsRef.current = ws;
    ws.onopen = () => ws.send(JSON.stringify({ docId: progressDoc.id }));
    ws.onmessage = (ev) => {
      try {
        const m = JSON.parse(ev.data) as { docId: number; status: string; progress: number };
        if (m.docId !== progressDoc.id) return;
        setProgressDoc((cur) =>
          cur && cur.id === m.docId
            ? { ...cur, status: m.status, progress: m.progress >= 0 ? m.progress : cur.progress }
            : cur,
        );
        // 状态推进到终态后同步一次表格
        if (!PROCESSING_STATUS.has(m.status)) loadDocs();
      } catch {
        // 忽略
      }
    };
    ws.onerror = () => ws.close();
    return () => {
      wsRef.current = null;
      ws.close();
    };
  }, [progressDoc?.id, progressDoc?.status, token, loadDocs]);

  const customUpload = async (option: UploadRequestOption) => {
    const file = option.file as File;
    const ext = file.name.slice(file.name.lastIndexOf('.')).toLowerCase();
    if (!ALLOWED_EXT.includes(ext)) {
      message.error(`不支持的文件类型：${ext}`);
      option.onError?.(new Error('不支持的文件类型'));
      return;
    }
    if (file.size > 100 * 1024 * 1024) {
      message.error('文件不能超过 100MB');
      option.onError?.(new Error('文件过大'));
      return;
    }
    const sessKey = `upload-session:kb:${kbId}:${file.name}:${file.size}`;
    let sha256Hex = '';
    try {
      // 秒传：流式分块计算 SHA-256（hash-wasm 增量模式，内存占用恒定）
      const hasher = await createSHA256();
      const CHUNK = 8 * 1024 * 1024;
      for (let off = 0; off < file.size; off += CHUNK) {
        const buf = await file.slice(off, Math.min(off + CHUNK, file.size)).arrayBuffer();
        hasher.update(new Uint8Array(buf));
      }
      sha256Hex = hasher.digest('hex');

      // 批内去重：同一 sha256 只保留第一个任务，其余直接跳过（进行中去重，完成后由后端秒传兜底）
      if (seenSha256Ref.current.has(sha256Hex)) {
        message.info(`文件「${file.name}」与本次上传中的另一文件内容相同，已跳过`);
        option.onSuccess?.({}, new XMLHttpRequest());
        return;
      }
      seenSha256Ref.current.add(sha256Hex);

      // 断点续传：恢复本地会话
      let sessionId = Number(localStorage.getItem(sessKey)) || 0;
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
        const r = await uploadApi.init(file.name, file.size, file.type || 'application/octet-stream',
          sha256Hex, 'KB_DOCUMENT', kbId);
        if (r.instant) {
          // 秒传命中：同知识库已有同内容文档，直接完成
          localStorage.removeItem(sessKey);
          message.success(`文件「${file.name}」秒传成功（文档已存在）`);
          option.onSuccess?.({}, new XMLHttpRequest());
          loadDocs(true);
          return;
        }
        sessionId = r.sessionId;
        chunkSize = r.chunkSize;
        localStorage.setItem(sessKey, String(sessionId));
      }
      const total = Math.ceil(file.size / chunkSize);
      for (let i = 0; i < total; i++) {
        const part = i + 1;
        if (done.has(part)) continue;
        await uploadApi.uploadPart(sessionId, part, file.slice(i * chunkSize, (i + 1) * chunkSize));
        done.add(part);
      }
      await uploadApi.complete(sessionId);
      localStorage.removeItem(sessKey);
      message.success(`文件「${file.name}」已上传，点击「开始处理」启动解析`);
      option.onSuccess?.({}, new XMLHttpRequest());
      loadDocs(true);
    } catch (e) {
      message.error(`文件「${file.name}」上传失败，重新上传将自动从断点续传`);
      option.onError?.(e as Error);
    } finally {
      // 任务结束后从批内去重集合移除，允许用户删除后再次上传同一文件
      if (sha256Hex) seenSha256Ref.current.delete(sha256Hex);
    }
  };

  const openPreview = async (doc: DocumentItem) => {
    try {
      const url = await documentApi.downloadUrl(doc.id);
      window.open(url, '_blank');
    } catch {
      /* 错误提示已由拦截器统一处理 */
    }
  };

  // ---- 切片抽屉 ----
  const loadChunks = async (doc: DocumentItem, page = 1) => {
    setChunksLoading(true);
    try {
      const res = await chunkApi.page(doc.id, page, 20);
      setChunks(res.records);
      setChunkTotal(res.total);
      setChunkPage(page);
    } finally {
      setChunksLoading(false);
    }
  };

  const openChunks = (doc: DocumentItem) => {
    setChunkDoc(doc);
    loadChunks(doc, 1);
  };

  const openChunkCreate = () => {
    chunkForm.resetFields();
    setChunkDetailLoading(false);
    setChunkModal({ mode: 'create' });
  };

  const openChunkEdit = async (chunk: Chunk) => {
    chunkForm.resetFields();
    setChunkModal({ mode: 'edit', chunk });
    setChunkDetailLoading(true);
    try {
      // 查询详情后再回显，拿到服务端最新数据
      const detail = await chunkApi.get(chunk.id);
      chunkForm.setFieldsValue({
        content: detail.content,
        page: detail.page ?? 0,
        sectionTitle: detail.sectionTitle ?? '',
        sectionPath: detail.sectionPath ?? '',
      });
    } finally {
      setChunkDetailLoading(false);
    }
  };

  const submitChunk = async () => {
    const target = chunkModal;
    if (!chunkDoc || !target) return;
    const v = await chunkForm.validateFields();
    if (target.mode === 'create') {
      await chunkApi.create({
        documentId: chunkDoc.id,
        content: v.content,
        page: v.page ?? 0,
        sectionTitle: v.sectionTitle || undefined,
      });
      message.success('人工切片已添加，已触发索引重建');
    } else if (target.chunk) {
      await chunkApi.update(target.chunk.id, {
        content: v.content,
        page: v.page ?? 0,
        sectionTitle: v.sectionTitle || undefined,
      });
      message.success('切片已更新，已触发索引重建');
    }
    setChunkModal(null);
    loadChunks(chunkDoc, chunkPage);
    loadDocs();
  };

  const deleteChunk = async (chunk: Chunk) => {
    if (!chunkDoc) return;
    await chunkApi.remove(chunk.id);
    message.success('切片已删除，已触发索引重建');
    loadChunks(chunkDoc, chunkPage);
    loadDocs();
  };

  const deleteDocument = async (doc: DocumentItem) => {
    await documentApi.remove(doc.id);
    message.success('文档已删除');
    loadDocs();
  };

  const reparseDocument = async (doc: DocumentItem) => {
    await documentApi.reparse(doc.id);
    message.success('已按当前策略重新解析并切片');
    loadDocs();
  };

  const startProcessing = async (doc: DocumentItem) => {
    await documentApi.start(doc.id);
    message.success(doc.status === 'UPLOADED' ? '已开始处理' : '已从断点继续处理');
    loadDocs();
  };

  const stopProcessing = async (doc: DocumentItem) => {
    await documentApi.stop(doc.id);
    message.success('停止指令已发送，将在当前阶段完成后停止');
    loadDocs();
  };

  const docColumns = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    {
      title: '文件名',
      dataIndex: 'fileName',
      render: (name: string) => (
        <Space>
          <FileTextOutlined style={{ color: '#1677ff' }} />
          <Typography.Text>{name}</Typography.Text>
        </Space>
      ),
    },
    { title: '大小', dataIndex: 'fileSize', width: 100, render: formatSize },
    { title: '页数', dataIndex: 'pageCount', width: 80, render: (v: number | null) => v ?? 0 },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (s: string, r: DocumentItem) => {
        const label: Record<string, string> = {
          UPLOADED: '待处理',
          PARSING: '解析中',
          CHUNKING: '切片中',
          EMBEDDING: '向量化中',
          INDEXING: '索引中',
          READY: '就绪',
          STOPPED: '已停止',
          FAILED: '失败',
        };
        const color =
          s === 'READY'
            ? 'green'
            : s === 'FAILED'
              ? 'red'
              : s === 'STOPPED'
                ? 'orange'
                : s === 'UPLOADED'
                  ? 'default'
                  : 'blue';
        const tag = (
          <Tag
            color={color}
            style={{ cursor: 'pointer' }}
            onClick={() => setProgressDoc(r)}
          >
            {label[s] ?? s}
          </Tag>
        );
        // 失败时悬浮展示完整错误原因
        return s === 'FAILED' && r.errorMsg ? (
          <Tooltip title={r.errorMsg} color="#ff4d4f">
            {tag}
          </Tooltip>
        ) : (
          tag
        );
      },
    },
    {
      title: '告警',
      dataIndex: 'warning',
      width: 140,
      render: (w: string | null) =>
        w ? (
          <Typography.Text type="warning" style={{ fontSize: 12 }}>
            {w.length > 30 ? `${w.slice(0, 30)}…` : w}
          </Typography.Text>
        ) : (
          '-'
        ),
    },
    {
      title: '操作',
      width: 460,
      render: (_: unknown, r: DocumentItem) => (
        <Space>
          {(r.status === 'UPLOADED' || r.status === 'STOPPED' || r.status === 'FAILED') && (
            <Button size="small" type="primary" onClick={() => startProcessing(r)}>
              {r.status === 'UPLOADED' ? '开始处理' : '继续处理'}
            </Button>
          )}
          {PROCESSING_STATUS.has(r.status) && (
            <Popconfirm
              title="停止处理该文档？"
              description="将在当前阶段完成后停止，已完成的解析/切片进度保留"
              okText="停止"
              cancelText="取消"
              onConfirm={() => stopProcessing(r)}
            >
              <Button size="small" danger>
                停止
              </Button>
            </Popconfirm>
          )}
          <Button size="small" onClick={() => openChunks(r)}>
            切片
          </Button>
          <Button size="small" icon={<EyeOutlined />} onClick={() => openPreview(r)}>
            预览
          </Button>
          <Popconfirm
            title="重新解析该文档？"
            description="将按当前策略重建自动切片并重新索引，人工切片保留"
            okText="重新解析"
            cancelText="取消"
            onConfirm={() => reparseDocument(r)}
          >
            <Button size="small" icon={<ReloadOutlined />} disabled={!TERMINAL_STATUS.has(r.status)}>
              重新解析
            </Button>
          </Popconfirm>
          <Popconfirm
            title="确认删除该文档？"
            description="将删除文档、全部切片、索引及原始文件，不可恢复"
            okText="确认删除"
            okButtonProps={{ danger: true }}
            cancelText="取消"
            onConfirm={() => deleteDocument(r)}
          >
            <Button type="link" danger size="small" icon={<DeleteOutlined />}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const chunkColumns = [
    { title: '#', dataIndex: 'seq', width: 60 },
    { title: '页', dataIndex: 'page', width: 60, render: (v: number | null) => (v ?? 0) + 1 },
    {
      title: '来源',
      dataIndex: 'status',
      width: 90,
      render: (s: string) =>
        s === 'MANUAL' ? <Tag color="purple">人工</Tag> : <Tag color="blue">自动</Tag>,
    },
    {
      title: '类型',
      dataIndex: 'chunkType',
      width: 90,
      render: (t: string) =>
        t === 'PARENT' ? <Tag color="gold">Parent</Tag> : <Tag>Child</Tag>,
    },
    {
      title: '章节',
      dataIndex: 'sectionTitle',
      width: 140,
      render: (t: string | null, r: Chunk) =>
        t ? (
          <Typography.Text ellipsis={{ tooltip: r.sectionPath }} style={{ maxWidth: 130 }}>
            {t}
          </Typography.Text>
        ) : (
          '-'
        ),
    },
    {
      title: '内容',
      dataIndex: 'content',
      render: (v: string) => (
        <Typography.Paragraph
          style={{ marginBottom: 0, maxWidth: 300 }}
          ellipsis={{ rows: 2, tooltip: v }}
        >
          {v}
        </Typography.Paragraph>
      ),
    },
    {
      title: '操作',
      width: 120,
      render: (_: unknown, r: Chunk) => (
        r.chunkType === 'PARENT' ? <Typography.Text type="secondary">上下文切片</Typography.Text> : (
          <Space>
            <Button size="small" onClick={() => openChunkEdit(r)}>
              编辑
            </Button>
            <Popconfirm title="确认删除该切片？" onConfirm={() => deleteChunk(r)}>
              <Button size="small" danger>
                删除
              </Button>
            </Popconfirm>
          </Space>
        )
      ),
    },
  ];

  return (
    <div>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          gap: 12,
          marginBottom: 16,
          flexWrap: 'wrap',
        }}
      >
        <Space>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/knowledge-bases')}>
            返回
          </Button>
          <Typography.Title level={4} style={{ margin: 0 }}>
            {kb?.name ?? '知识库'}
          </Typography.Title>
          {kb?.description && <Typography.Text type="secondary">{kb.description}</Typography.Text>}
        </Space>
        <Space>
          <Tooltip title="支持 txt / md / pdf / docx / xlsx / png / jpg / jpeg，单个文件 ≤ 100MB">
            <Upload
              accept={ALLOWED_EXT.join(',')}
              showUploadList={false}
              customRequest={customUpload}
              multiple
            >
              <Button type="primary" icon={<UploadOutlined />}>
                上传文档
              </Button>
            </Upload>
          </Tooltip>
        </Space>
      </div>

      <Table
        rowKey="id"
        loading={loading}
        columns={docColumns}
        dataSource={docs}
        pagination={false}
        bordered
      />

      {/* 单文档处理进度弹窗：点击状态列触发，打开期间轮询该文档 */}
      <Modal
        title={`处理进度 - ${progressDoc?.fileName ?? ''}`}
        open={!!progressDoc}
        width={520}
        okText="关闭"
        cancelButtonProps={{ style: { display: 'none' } }}
        onOk={() => setProgressDoc(null)}
        onCancel={() => setProgressDoc(null)}
      >
        {progressDoc && <DocProgress doc={progressDoc} />}
      </Modal>

      {/* 切片管理抽屉 */}
      <Drawer
        title={`切片管理 - ${chunkDoc?.fileName ?? ''}`}
        width={820}
        open={!!chunkDoc}
        onClose={() => setChunkDoc(null)}
        extra={
          <Button type="primary" onClick={openChunkCreate}>
            新增人工切片
          </Button>
        }
      >
        <Table
          rowKey="id"
          size="small"
          loading={chunksLoading}
          columns={chunkColumns}
          dataSource={chunks}
          pagination={{
            current: chunkPage,
            pageSize: 20,
            total: chunkTotal,
            showSizeChanger: false,
            onChange: (p) => chunkDoc && loadChunks(chunkDoc, p),
          }}
        />
      </Drawer>

      {/* 切片新增/编辑 */}
      <Modal
        title={chunkModal?.mode === 'create' ? '新增人工切片' : '编辑切片'}
        open={!!chunkModal}
        onCancel={() => setChunkModal(null)}
        onOk={submitChunk}
        okText="保存"
        confirmLoading={chunkDetailLoading}
        destroyOnHidden
      >
        <Spin spinning={chunkDetailLoading}>
          <Form form={chunkForm} layout="vertical">
            <Form.Item name="sectionTitle" label="章节标题">
              <Input placeholder="可选，如：第3章 / JVM 内存结构" />
            </Form.Item>
            <Form.Item name="sectionPath" label="章节路径">
              <Input disabled placeholder="无（自动切片显示完整章节路径）" />
            </Form.Item>
            <Form.Item
              name="content"
              label="切片内容"
              rules={[{ required: true, message: '请输入切片内容' }]}
            >
              <Input.TextArea rows={8} placeholder="请输入切片文本" />
            </Form.Item>
            <Form.Item name="page" label="所在页码（从 0 开始）" initialValue={0}>
              <InputNumber min={0} style={{ width: 200 }} />
            </Form.Item>
          </Form>
        </Spin>
      </Modal>
    </div>
  );
}
