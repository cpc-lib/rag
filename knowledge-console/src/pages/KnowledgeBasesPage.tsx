import { useEffect, useState } from 'react';
import {
  App,
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import { PlusOutlined, ArrowRightOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { kbApi } from '../api/knowledgeBases';
import type { KbCreateReq, KnowledgeBase } from '../api/types';

/** 分隔符 JSON 字符串 ⇄ textarea（每行一个） */
const separatorsToText = (json: string | null): string => {
  if (!json) return '';
  try {
    const arr = JSON.parse(json) as string[];
    return Array.isArray(arr) ? arr.join('\n') : '';
  } catch {
    return '';
  }
};

const textToSeparators = (text: string): string[] =>
  text
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean);

/** 切片策略选项（指南 §3，顺序即推荐展示顺序） */
const STRATEGY_OPTIONS: { value: string; label: string }[] = [
  { value: 'AUTO', label: 'AUTO（按文件类型自动选择）' },
  { value: 'FIXED_SIZE', label: 'FIXED_SIZE（固定长度）' },
  { value: 'SLIDING_WINDOW', label: 'SLIDING_WINDOW（滑动窗口）' },
  { value: 'RECURSIVE', label: 'RECURSIVE（递归分隔符）' },
  { value: 'PARAGRAPH', label: 'PARAGRAPH（段落感知）' },
  { value: 'SENTENCE', label: 'SENTENCE（句子感知）' },
  { value: 'SEMANTIC', label: 'SEMANTIC（语义主题，额外 embedding）' },
  { value: 'STRUCTURE', label: 'STRUCTURE（结构/编号标题）' },
  { value: 'MARKDOWN', label: 'MARKDOWN（Markdown 标题树）' },
  { value: 'HTML', label: 'HTML（DOM 结构）' },
  { value: 'PDF_LAYOUT', label: 'PDF_LAYOUT（PDF 版式）' },
  { value: 'TABLE', label: 'TABLE（表格优先）' },
  { value: 'QA', label: 'QA（问答对）' },
  { value: 'PARENT_CHILD', label: 'PARENT_CHILD（父子切片）' },
  { value: 'CODE', label: 'CODE（代码结构）' },
];

export default function KnowledgeBasesPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [data, setData] = useState<KnowledgeBase[]>([]);
  const [loading, setLoading] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<KnowledgeBase | null>(null);
  const [createForm] = Form.useForm();
  const [editForm] = Form.useForm();
  const [saving, setSaving] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      setData(await kbApi.list());
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const handleCreate = async () => {
    const v = await createForm.validateFields();
    const req: KbCreateReq = {
      name: v.name,
      description: v.description?.trim() || undefined,
      parentChunkSize: v.parentChunkSize,
      childChunkSize: v.childChunkSize,
      childOverlap: v.childOverlap,
      chunkStrategy: v.chunkStrategy,
      separators: v.separatorsText ? textToSeparators(v.separatorsText) : undefined,
    };
    setSaving(true);
    try {
      await kbApi.create(req);
      message.success('知识库已创建');
      setCreateOpen(false);
      createForm.resetFields();
      load();
    } finally {
      setSaving(false);
    }
  };

  const openEdit = (kb: KnowledgeBase) => {
    setEditTarget(kb);
    editForm.setFieldsValue({
      name: kb.name,
      description: kb.description ?? '',
      parentChunkSize: kb.parentChunkSize ?? 1200,
      childChunkSize: kb.childChunkSize ?? 400,
      childOverlap: kb.childOverlap ?? 60,
      chunkStrategy: kb.chunkStrategy ?? 'AUTO',
      separatorsText: separatorsToText(kb.separators),
    });
  };

  const handleEdit = async () => {
    if (!editTarget) return;
    const v = await editForm.validateFields();
    setSaving(true);
    try {
      await kbApi.update(editTarget.id, {
        name: v.name,
        description: v.description?.trim() || '',
        parentChunkSize: v.parentChunkSize,
        childChunkSize: v.childChunkSize,
        childOverlap: v.childOverlap,
        chunkStrategy: v.chunkStrategy,
        separators: textToSeparators(v.separatorsText ?? ''),
      });
      message.success('已保存（切片策略将在下次解析/重建时生效）');
      setEditTarget(null);
      load();
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (kb: KnowledgeBase) => {
    await kbApi.remove(kb.id);
    message.success('知识库已删除，索引与对象已级联清理');
    load();
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    { title: '名称', dataIndex: 'name', width: 200 },
    { title: '描述', dataIndex: 'description', render: (v: string | null) => v || '-' },
    {
      title: '切片策略',
      width: 340,
      render: (_: unknown, r: KnowledgeBase) => (
        <Space size={4} wrap>
          <Tag color="geekblue">{r.chunkStrategy ?? 'AUTO'}</Tag>
          <Tag color="blue">parent={r.parentChunkSize ?? '-'}</Tag>
          <Tag>child={r.childChunkSize ?? '-'}</Tag>
          <Tag>overlap={r.childOverlap ?? '-'}</Tag>
        </Space>
      ),
    },
    {
      title: 'Milvus 集合 / ES 索引',
      width: 200,
      render: (_: unknown, r: KnowledgeBase) => (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {r.milvusCollection} / {r.esIndex}
        </Typography.Text>
      ),
    },
    {
      title: '操作',
      width: 220,
      render: (_: unknown, r: KnowledgeBase) => (
        <Space>
          <Button type="link" size="small" onClick={() => navigate(`/knowledge-bases/${r.id}`)}>
            进入 <ArrowRightOutlined />
          </Button>
          <Button size="small" onClick={() => openEdit(r)}>
            编辑
          </Button>
          <Popconfirm
            title={`确认删除知识库「${r.name}」？`}
            description="将级联删除全部文档、切片、向量/全文索引及对象存储文件"
            onConfirm={() => handleDelete(r)}
          >
            <Button size="small" danger>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const strategyFields = (
    <>
      <Form.Item
        name="chunkStrategy"
        label="切片策略"
        tooltip="AUTO：按每个文件的类型自动选择最合适的策略；其余为强制指定"
        rules={[{ required: true, message: '请选择切片策略' }]}
      >
        <Select options={STRATEGY_OPTIONS} />
      </Form.Item>
      <Space size={16} wrap>
        <Form.Item
          name="parentChunkSize"
          label="Parent 大小（tokens）"
          tooltip="检索命中 child 后，用 parent 的完整上下文送给大模型"
          rules={[{ required: true, message: '请输入 Parent 大小' }]}
        >
          <InputNumber min={200} max={8000} style={{ width: 170 }} />
        </Form.Item>
        <Form.Item
          name="childChunkSize"
          label="Child 大小（tokens）"
          tooltip="小切片用于精准向量/关键词检索"
          rules={[{ required: true, message: '请输入 Child 大小' }]}
        >
          <InputNumber min={100} max={2000} style={{ width: 170 }} />
        </Form.Item>
        <Form.Item
          name="childOverlap"
          label="Child 重叠（tokens）"
          rules={[{ required: true, message: '请输入重叠大小' }]}
        >
          <InputNumber min={0} max={500} style={{ width: 170 }} />
        </Form.Item>
      </Space>
      <Form.Item
        name="separatorsText"
        label="分隔符（每行一个，按优先级排序）"
        tooltip="递归切片：依次尝试分隔符，最后按 token 硬切"
      >
        <Input.TextArea rows={4} placeholder={'\n\n\n\n。\n？\n！'} />
      </Form.Item>
    </>
  );

  return (
    <div>
      <Space style={{ marginBottom: 16, justifyContent: 'space-between', width: '100%' }}>
        <Typography.Title level={4} style={{ margin: 0 }}>
          知识库管理
        </Typography.Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
          新建知识库
        </Button>
      </Space>
      <Table rowKey="id" loading={loading} columns={columns} dataSource={data} pagination={false} bordered />

      <Modal
        title="新建知识库"
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        onOk={handleCreate}
        confirmLoading={saving}
        okText="创建"
        destroyOnClose
      >
        <Form
          form={createForm}
          layout="vertical"
          initialValues={{
            chunkStrategy: 'AUTO',
            parentChunkSize: 1200,
            childChunkSize: 400,
            childOverlap: 60,
            separatorsText: '\n\n\n\n。\n？\n！',
          }}
        >
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="如：产品手册库" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} />
          </Form.Item>
          {strategyFields}
        </Form>
      </Modal>

      <Modal
        title="编辑知识库"
        open={!!editTarget}
        onCancel={() => setEditTarget(null)}
        onOk={handleEdit}
        confirmLoading={saving}
        okText="保存"
        destroyOnClose
      >
        <Form form={editForm} layout="vertical">
          <Form.Item name="name" label="名称" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} />
          </Form.Item>
          {strategyFields}
        </Form>
      </Modal>
    </div>
  );
}
