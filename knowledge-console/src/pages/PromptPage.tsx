import { useEffect, useState } from 'react';
import {
  App,
  Button,
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

/** 提示词模板管理（租户管理员）：增删改查 + 知识库内默认模板。 */
export default function PromptPage() {
  const { message } = App.useApp();
  const [list, setList] = useState<PromptTemplate[]>([]);
  const [kbs, setKbs] = useState<KnowledgeBase[]>([]);
  const [loading, setLoading] = useState(true);
  const [modalOpen, setModalOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState<PromptTemplate | null>(null);
  const [form] = Form.useForm();

  const load = async () => {
    setLoading(true);
    try {
      setList(await promptApi.list());
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    kbApi.list().then(setKbs);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

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
      render: (kbId: number) => kbs.find((k) => k.id === kbId)?.name ?? kbId,
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
