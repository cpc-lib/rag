import { useEffect, useState } from 'react';
import {
  App,
  Button,
  Descriptions,
  Drawer,
  Form,
  Input,
  InputNumber,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import type { TableProps } from 'antd';
import { modelApi } from '../api/modelConfig';
import type { ModelItem, ModelReq, ModelType } from '../api/types';

const TYPE_LABEL: Record<ModelType, string> = {
  CHAT: '对话',
  VISION: '视觉',
  EMBEDDING: '向量',
  IMAGE: '文生图',
};

const TYPE_COLOR: Record<ModelType, string> = {
  CHAT: 'blue',
  VISION: 'purple',
  EMBEDDING: 'cyan',
  IMAGE: 'geekblue',
};

const formatTime = (raw: string | null) => {
  if (!raw) {
    return '—';
  }
  const d = new Date(raw);
  return Number.isNaN(d.getTime()) ? raw : d.toLocaleString('zh-CN');
};

/**
 * 模型参数：模型池列表，点击行看详情，支持新增、启用/禁用、编辑。
 * 密钥脱敏：详情仅展示是否已配置，表单留空表示不修改。
 */
export default function ModelConfigPage() {
  const { message } = App.useApp();
  const [form] = Form.useForm();
  const [models, setModels] = useState<ModelItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [detail, setDetail] = useState<ModelItem | null>(null);
  const [editing, setEditing] = useState<ModelItem | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [toggling, setToggling] = useState<number | null>(null);

  const type = Form.useWatch('type', form) as ModelType | undefined;

  const refresh = async () => {
    setLoading(true);
    try {
      const page = await modelApi.list({ size: 100 });
      setModels(page.records);
      return page.records;
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ type: 'CHAT' });
    setModalOpen(true);
  };

  const openEdit = async (m: ModelItem) => {
    setEditing(m);
    const detail = await modelApi.get(m.id);
    form.setFieldsValue({
      name: detail.name,
      type: detail.type,
      baseUrl: detail.baseUrl ?? '',
      model: detail.model ?? '',
      apiKey: detail.apiKey ?? '',
      temperature: detail.temperature,
      topP: detail.topP,
      maxTokens: detail.maxTokens,
      embeddingDim: detail.embeddingDim,
    });
    setModalOpen(true);
  };

  const onFinish = async (v: Record<string, unknown>) => {
    const req: ModelReq = {
      name: (v.name as string).trim(),
      type: v.type as ModelType,
    };
    const baseUrl = ((v.baseUrl as string) ?? '').trim();
    const modelName = ((v.model as string) ?? '').trim();
    const apiKey = ((v.apiKey as string) ?? '').trim();
    if (baseUrl) {
      req.baseUrl = baseUrl;
    }
    if (modelName) {
      req.model = modelName;
    }
    if (apiKey) {
      req.apiKey = apiKey;
    }
    if (v.type === 'CHAT') {
      if (v.temperature != null) {
        req.temperature = Number(v.temperature);
      }
      if (v.topP != null) {
        req.topP = Number(v.topP);
      }
      if (v.maxTokens != null) {
        req.maxTokens = Number(v.maxTokens);
      }
    }
    if (v.type === 'EMBEDDING' && v.embeddingDim != null) {
      req.embeddingDim = Number(v.embeddingDim);
    }

    setSaving(true);
    try {
      if (editing) {
        await modelApi.update(editing.id, req);
        message.success('已保存');
      } else {
        await modelApi.create(req);
        message.success('已新增，请点击启用');
      }
      setModalOpen(false);
      const recs = await refresh();
      if (detail) {
        setDetail(recs.find((r) => r.id === detail.id) ?? null);
      }
    } finally {
      setSaving(false);
    }
  };

  const toggle = async (m: ModelItem) => {
    setToggling(m.id);
    try {
      if (m.enabled) {
        await modelApi.disable(m.id);
        message.success('已禁用');
      } else {
        await modelApi.enable(m.id);
        message.success('已启用（同类原启用模型已自动停用）');
      }
      const recs = await refresh();
      if (detail) {
        setDetail(recs.find((r) => r.id === detail.id) ?? null);
      }
    } finally {
      setToggling(null);
    }
  };

  const columns: TableProps<ModelItem>['columns'] = [
    { title: '名称', dataIndex: 'name' },
    {
      title: '类型',
      dataIndex: 'type',
      width: 100,
      render: (t: ModelType) => <Tag color={TYPE_COLOR[t]}>{TYPE_LABEL[t]}</Tag>,
    },
    {
      title: '模型名称',
      dataIndex: 'model',
      render: (m: string | null) => m ?? '—',
    },
    {
      title: '状态',
      dataIndex: 'enabled',
      width: 100,
      render: (enabled: boolean) =>
        enabled ? <Tag color="green">启用中</Tag> : <Tag>已禁用</Tag>,
    },
    {
      title: '操作',
      width: 150,
      render: (_, m) => (
        <Space size={12}>
          <a
            onClick={(e) => {
              e.stopPropagation();
              setDetail(m);
            }}
          >
            详情
          </a>
          <Popconfirm
            title={m.enabled ? '确认禁用该模型？' : '确认启用该模型？'}
            okText="确定"
            cancelText="取消"
            onConfirm={() => toggle(m)}
          >
            <a
              onClick={(e) => e.stopPropagation()}
              style={{ color: m.enabled ? undefined : '#389e0d' }}
            >
              {m.enabled ? '禁用' : '启用'}
            </a>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Space style={{ width: '100%', justifyContent: 'space-between' }}>
        <Typography.Title level={4} style={{ margin: 0 }}>
          模型参数
        </Typography.Title>
        <Space size={8}>
          <Button icon={<ReloadOutlined />} loading={loading} onClick={() => refresh()}>
            刷新
          </Button>
          <Button type="primary" onClick={openCreate}>
            新增模型
          </Button>
        </Space>
      </Space>

      <Table<ModelItem>
        style={{ marginTop: 16 }}
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={models}
        pagination={{ pageSize: 10, showSizeChanger: false }}
        onRow={(m) => ({
          onClick: (e) => {
            // Popconfirm 弹层经 Portal 渲染，React 事件仍会冒泡到行：点击确认/取消时不触发详情
            if ((e.target as HTMLElement).closest('.ant-popover')) return;
            setDetail(m);
          },
          style: { cursor: 'pointer' },
        })}
      />

      <Drawer
        open={!!detail}
        width={440}
        title="模型详情"
        onClose={() => setDetail(null)}
        extra={
          detail && (
            <Space>
              <Button onClick={() => openEdit(detail)}>编辑</Button>
              <Button
                loading={toggling === detail.id}
                onClick={() => toggle(detail)}
                danger={detail.enabled}
                type={detail.enabled ? 'default' : 'primary'}
              >
                {detail.enabled ? '禁用' : '启用'}
              </Button>
            </Space>
          )
        }
      >
        {detail && (
          <Descriptions column={1} bordered size="small">
            <Descriptions.Item label="名称">{detail.name}</Descriptions.Item>
            <Descriptions.Item label="类型">
              <Tag color={TYPE_COLOR[detail.type]}>
                {TYPE_LABEL[detail.type]}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label="Base URL">
              {detail.baseUrl ?? '—'}
            </Descriptions.Item>
            <Descriptions.Item label="模型名称">
              {detail.model ?? '—'}
            </Descriptions.Item>
            <Descriptions.Item label="API Key">
              {detail.apiKeyConfigured ? (
                <Tag color="green">已配置</Tag>
              ) : (
                <Tag>未配置</Tag>
              )}
            </Descriptions.Item>
            {detail.type === 'CHAT' && (
              <>
                <Descriptions.Item label="Temperature">
                  {detail.temperature ?? '—'}
                </Descriptions.Item>
                <Descriptions.Item label="Top-P">
                  {detail.topP ?? '—'}
                </Descriptions.Item>
                <Descriptions.Item label="Max Tokens">
                  {detail.maxTokens ?? '—'}
                </Descriptions.Item>
              </>
            )}
            {detail.type === 'EMBEDDING' && (
              <Descriptions.Item label="向量维度">
                {detail.embeddingDim ?? '—'}
              </Descriptions.Item>
            )}
            <Descriptions.Item label="状态">
              {detail.enabled ? (
                <Tag color="green">启用中</Tag>
              ) : (
                <Tag>已禁用</Tag>
              )}
            </Descriptions.Item>
            <Descriptions.Item label="创建时间">
              {formatTime(detail.createdAt)}
            </Descriptions.Item>
          </Descriptions>
        )}
      </Drawer>

      <Form
        form={form}
        layout="vertical"
        onFinish={onFinish}
      >
        <Drawer
          open={modalOpen}
          width={460}
          title={editing ? '编辑模型' : '新增模型'}
          onClose={() => setModalOpen(false)}
          extra={
            <Space>
              <Button onClick={() => setModalOpen(false)}>取消</Button>
              <Button
                type="primary"
                loading={saving}
                onClick={() => form.submit()}
              >
                保存
              </Button>
            </Space>
          }
        >
          <Form.Item
            name="name"
            label="名称"
            rules={[{ required: true, message: '请输入名称' }]}
          >
            <Input placeholder="便于识别的名称，如：线上对话模型" />
          </Form.Item>
          <Form.Item
            name="type"
            label="类型"
            rules={[{ required: true }]}
          >
            <Select
              disabled={!!editing}
              options={(Object.keys(TYPE_LABEL) as ModelType[]).map((t) => ({
                value: t,
                label: `${TYPE_LABEL[t]}（${t}）`,
              }))}
            />
          </Form.Item>
          <Form.Item name="baseUrl" label="接口 Base URL（OpenAI 兼容）">
            <Input placeholder="如 https://api.example.com/v1" />
          </Form.Item>
          <Form.Item name="model" label="模型名称">
            <Input placeholder="如 qwen-plus" />
          </Form.Item>
          <Form.Item name="apiKey" label="API Key">
            <Input.Password placeholder="请输入 API Key" />
          </Form.Item>

          {type === 'CHAT' && (
            <Space size={16}>
              <Form.Item name="temperature" label="Temperature（0~2）">
                <InputNumber min={0} max={2} step={0.1} />
              </Form.Item>
              <Form.Item name="topP" label="Top-P（0~1）">
                <InputNumber min={0} max={1} step={0.05} />
              </Form.Item>
              <Form.Item name="maxTokens" label="Max Tokens">
                <InputNumber min={1} max={128000} />
              </Form.Item>
            </Space>
          )}
          {type === 'EMBEDDING' && (
            <Form.Item
              name="embeddingDim"
              label="向量维度（64~65536，须与模型实际输出一致）"
            >
              <InputNumber min={64} max={65536} style={{ width: 240 }} />
            </Form.Item>
          )}
        </Drawer>
      </Form>
    </div>
  );
}
