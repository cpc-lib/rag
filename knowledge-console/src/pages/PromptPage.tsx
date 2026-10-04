import { useEffect, useState } from 'react';
import {
  App,
  Button,
  Card,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
} from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { promptApi } from '../api/prompts';
import { kbApi } from '../api/knowledgeBases';
import type { KnowledgeBase, PromptReq, PromptTemplate } from '../api/types';

/** 提示词模板管理（租户管理员）：知识库问答模板 + 字幕翻译提示词。 */
export default function PromptPage() {
  const { message } = App.useApp();
  const [list, setList] = useState<PromptTemplate[]>([]);
  const [kbs, setKbs] = useState<KnowledgeBase[]>([]);
  const [loading, setLoading] = useState(true);
  const [modalOpen, setModalOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState<PromptTemplate | null>(null);
  const [form] = Form.useForm();

  // 字幕翻译提示词
  const [subtitleContent, setSubtitleContent] = useState('');
  const [subtitleSaving, setSubtitleSaving] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      setList(await promptApi.list());
    } finally {
      setLoading(false);
    }
  };

  const loadSubtitle = async () => {
    try {
      const t = await promptApi.getSubtitle();
      setSubtitleContent(t.content);
    } catch {
      // 忽略，首次可能不存在
    }
  };

  useEffect(() => {
    load();
    loadSubtitle();
    kbApi.list().then(setKbs);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const saveSubtitle = async () => {
    if (!subtitleContent.trim()) {
      message.warning('提示词内容不能为空');
      return;
    }
    setSubtitleSaving(true);
    try {
      await promptApi.updateSubtitle(subtitleContent);
      message.success('字幕翻译提示词已保存');
    } finally {
      setSubtitleSaving(false);
    }
  };

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ isDefault: false });
    setModalOpen(true);
  };

  const openEdit = (t: PromptTemplate) => {
    setEditing(t);
    form.setFieldsValue({
      kbId: t.kbId,
      name: t.name,
      content: t.content,
      isDefault: t.isDefault,
    });
    setModalOpen(true);
  };

  const onOk = async () => {
    const v = await form.validateFields();
    const req: PromptReq = {
      kbId: v.kbId,
      name: v.name.trim(),
      content: v.content,
      isDefault: v.isDefault,
    };
    setSaving(true);
    try {
      if (editing) {
        await promptApi.update(editing.id, req);
      } else {
        await promptApi.create(req);
      }
      message.success('模板已保存');
      setModalOpen(false);
      await load();
    } finally {
      setSaving(false);
    }
  };

  const onDelete = async (t: PromptTemplate) => {
    await promptApi.remove(t.id);
    message.success('模板已删除');
    await load();
  };

  const columns: ColumnsType<PromptTemplate> = [
    {
      title: '所属知识库',
      dataIndex: 'kbId',
      width: 160,
      render: (kbId: number | null) =>
        kbId == null ? '-' : kbs.find((k) => k.id === kbId)?.name ?? kbId,
    },
    {
      title: '模板名称',
      dataIndex: 'name',
      width: 240,
      render: (name: string, t) => (
        <Space size={8}>
          <Typography.Text strong>{name}</Typography.Text>
          {t.isDefault && <Tag color="blue">默认</Tag>}
        </Space>
      ),
    },
    {
      title: '内容（支持 {{资料}} {{外部信息}} {{问题}} 占位符）',
      dataIndex: 'content',
      ellipsis: true,
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      width: 180,
    },
    {
      title: '操作',
      width: 140,
      render: (_, t) => (
        <Space size={4}>
          <Button type="link" size="small" onClick={() => openEdit(t)}>
            编辑
          </Button>
          <Popconfirm
            title="确认删除该模板？"
            okText="删除"
            cancelText="取消"
            okButtonProps={{ danger: true }}
            onConfirm={() => onDelete(t)}
            disabled={t.isDefault}
          >
            <Button type="link" size="small" danger disabled={t.isDefault}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Space style={{ marginBottom: 16, justifyContent: 'space-between', width: '100%' }}>
        <Typography.Title level={4} style={{ margin: 0 }}>
          提示词管理
        </Typography.Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
          新建模板
        </Button>
      </Space>

      <Card
        title="字幕翻译提示词"
        style={{ marginBottom: 24 }}
        extra={
          <Button type="primary" onClick={saveSubtitle} loading={subtitleSaving}>
            保存
          </Button>
        }
      >
        <Typography.Paragraph type="secondary" style={{ marginTop: 0 }}>
          用于字幕转换应用的翻译系统提示词，支持占位符 <Tag color="blue">{`{{目标语言}}`}</Tag>
        </Typography.Paragraph>
        <Input.TextArea
          value={subtitleContent}
          onChange={(e) => setSubtitleContent(e.target.value)}
          autoSize={{ minRows: 8, maxRows: 20 }}
          placeholder="字幕翻译师的系统提示词，可使用 {{目标语言}} 占位符"
        />
      </Card>

      <Table
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={list}
        pagination={false}
      />
      <Modal
        title={editing ? '编辑提示词模板' : '新建提示词模板'}
        open={modalOpen}
        onOk={onOk}
        confirmLoading={saving}
        onCancel={() => setModalOpen(false)}
        okText="保存"
        cancelText="取消"
        width={720}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="kbId"
            label="所属知识库"
            rules={[{ required: true, message: '请选择所属知识库' }]}
          >
            <Select
              placeholder="选择知识库"
              disabled={!!editing}
              options={kbs.map((k) => ({ value: k.id, label: k.name }))}
            />
          </Form.Item>
          <Form.Item
            name="name"
            label="模板名称"
            rules={[{ required: true, whitespace: true, message: '请输入模板名称' }]}
          >
            <Input maxLength={100} placeholder="如：默认知识库问答模板" />
          </Form.Item>
          <Form.Item
            name="content"
            label="模板内容"
            rules={[{ required: true, whitespace: true, message: '请输入模板内容' }]}
          >
            <Input.TextArea
              autoSize={{ minRows: 8, maxRows: 20 }}
              placeholder={'支持占位符：{{资料}}、{{外部信息}}、{{问题}}'}
            />
          </Form.Item>
          <Form.Item name="isDefault" valuePropName="checked" label="设为默认模板">
            <Switch checkedChildren="默认" unCheckedChildren="普通" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
