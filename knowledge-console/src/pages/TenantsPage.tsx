import { useEffect, useState } from 'react';
import {
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Space,
  Table,
  Tag,
  Typography,
  App,
} from 'antd';
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { tenantApi } from '../api/tenants';
import type { ResetAdminResp, Tenant, TenantCreateReq } from '../api/types';

export default function TenantsPage() {
  const { message, modal } = App.useApp();
  const [data, setData] = useState<Tenant[]>([]);
  const [loading, setLoading] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<Tenant | null>(null);
  const [resetTarget, setResetTarget] = useState<Tenant | null>(null);
  const [createForm] = Form.useForm();
  const [editForm] = Form.useForm();
  const [resetForm] = Form.useForm();
  const [saving, setSaving] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      setData(await tenantApi.list());
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  /** 一次性初始管理员密码弹窗（创建租户 / 重置管理员共用） */
  const showInitialPassword = (resp: ResetAdminResp, title: string) => {
    modal.info({
      title,
      width: 480,
      content: (
        <div style={{ marginTop: 12 }}>
          <Typography.Paragraph>
            管理员账号：<Typography.Text strong>{resp.username}</Typography.Text>
          </Typography.Paragraph>
          <Typography.Paragraph>
            初始密码（仅展示一次，请立即复制保存）：
          </Typography.Paragraph>
          <Typography.Text
            copyable
            code
            style={{ fontSize: 16, padding: '8px 12px', background: '#f6ffed', display: 'inline-block' }}
          >
            {resp.initialPassword}
          </Typography.Text>
        </div>
      ),
      okText: '我已保存',
    });
  };

  const handleCreate = async () => {
    const values = await createForm.validateFields();
    setSaving(true);
    try {
      const req: TenantCreateReq = {
        name: values.name,
        code: values.code.trim(),
        adminUsername: values.adminUsername?.trim() || undefined,
        maxStorageMb: values.maxStorageMb,
        maxMqConcurrency: values.maxMqConcurrency,
        maxLlmTokensMonth: values.maxLlmTokensMonth,
        maxSseConnections: values.maxSseConnections,
      };
      const resp = await tenantApi.create(req);
      message.success('租户创建成功');
      setCreateOpen(false);
      createForm.resetFields();
      showInitialPassword(resp, '租户创建成功');
      load();
    } finally {
      setSaving(false);
    }
  };

  const openEdit = (t: Tenant) => {
    setEditTarget(t);
    editForm.setFieldsValue({
      name: t.name,
      status: t.status,
      maxStorageMb: t.maxStorageMb,
      maxMqConcurrency: t.maxMqConcurrency,
      maxLlmTokensMonth: t.maxLlmTokensMonth,
      maxSseConnections: t.maxSseConnections,
    });
  };

  const handleEdit = async () => {
    if (!editTarget) return;
    const values = await editForm.validateFields();
    setSaving(true);
    try {
      await tenantApi.update(editTarget.id, values);
      message.success('已保存');
      setEditTarget(null);
      load();
    } finally {
      setSaving(false);
    }
  };

  const handleToggleStatus = async (t: Tenant) => {
    const next = t.status === 1 ? 0 : 1;
    await tenantApi.update(t.id, { status: next });
    message.success(next === 1 ? '租户已启用' : '租户已停用');
    load();
  };

  const handleResetAdmin = async () => {
    if (!resetTarget) return;
    const values = await resetForm.validateFields();
    const resp = await tenantApi.resetAdmin(resetTarget.id, values.username?.trim() || undefined);
    setResetTarget(null);
    resetForm.resetFields();
    showInitialPassword(resp, '租户管理员已重置');
  };

  const columns = [
    { title: '租户编码', dataIndex: 'code', width: 160 },
    { title: '租户名称', dataIndex: 'name' },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (s: number | null) =>
        s === 1 ? <Tag color="green">启用</Tag> : <Tag color="default">停用</Tag>,
    },
    { title: '存储上限(MB)', dataIndex: 'maxStorageMb', width: 120 },
    { title: 'MQ 并发', dataIndex: 'maxMqConcurrency', width: 100 },
    { title: 'Token 月配额', dataIndex: 'maxLlmTokensMonth', width: 140 },
    { title: 'SSE 连接数', dataIndex: 'maxSseConnections', width: 110 },
    {
      title: '操作',
      width: 250,
      render: (_: unknown, t: Tenant) => (
        <Space>
          <Button size="small" onClick={() => openEdit(t)}>
            编辑
          </Button>
          <Button size="small" icon={<ReloadOutlined />} onClick={() => setResetTarget(t)}>
            重置管理员
          </Button>
          <Popconfirm
            title={t.status === 1 ? `确认停用租户「${t.name}」？` : `确认启用租户「${t.name}」？`}
            onConfirm={() => handleToggleStatus(t)}
          >
            <Button size="small" danger={t.status === 1}>
              {t.status === 1 ? '停用' : '启用'}
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const quotaFields = (
    <>
      <Form.Item name="maxStorageMb" label="存储上限（MB）" rules={[{ required: true }]}>
        <InputNumber min={1} style={{ width: '100%' }} placeholder="如 10240" />
      </Form.Item>
      <Form.Item name="maxMqConcurrency" label="MQ 任务并发额度" rules={[{ required: true }]}>
        <InputNumber min={1} style={{ width: '100%' }} placeholder="如 4" />
      </Form.Item>
      <Form.Item name="maxLlmTokensMonth" label="LLM Token 月配额" rules={[{ required: true }]}>
        <InputNumber min={1} style={{ width: '100%' }} placeholder="如 1000000" />
      </Form.Item>
      <Form.Item name="maxSseConnections" label="最大 SSE 并发连接" rules={[{ required: true }]}>
        <InputNumber min={1} style={{ width: '100%' }} placeholder="如 20" />
      </Form.Item>
    </>
  );

  return (
    <div>
      <Space style={{ marginBottom: 16, justifyContent: 'space-between', width: '100%' }}>
        <Typography.Title level={4} style={{ margin: 0 }}>
          租户管理
        </Typography.Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
          新建租户
        </Button>
      </Space>
      <Table
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={data}
        pagination={false}
        bordered
      />

      {/* 新建租户 */}
      <Modal
        title="新建租户"
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        onOk={handleCreate}
        confirmLoading={saving}
        okText="创建"
        destroyOnHidden
      >
        <Form form={createForm} layout="vertical" initialValues={{ maxMqConcurrency: 4, maxSseConnections: 20 }}>
          <Form.Item name="name" label="租户名称" rules={[{ required: true, message: '请输入租户名称' }]}>
            <Input placeholder="如：某某科技有限公司" />
          </Form.Item>
          <Form.Item
            name="code"
            label="租户编码"
            tooltip="仅允许字母、数字、下划线，长度 10 ~ 20 位，创建后不可修改"
            rules={[
              { required: true, message: '请输入租户编码' },
              {
                pattern: /^[A-Za-z0-9_]{10,20}$/,
                message: '仅允许字母、数字、下划线，长度 10 ~ 20 位',
              },
            ]}
          >
            <Input placeholder="如 ACME_TECH_01" />
          </Form.Item>
          <Form.Item name="adminUsername" label="初始管理员账号（留空则自动生成）">
            <Input placeholder="如 admin" />
          </Form.Item>
          {quotaFields}
        </Form>
      </Modal>

      {/* 编辑租户 */}
      <Modal
        title="编辑租户"
        open={!!editTarget}
        onCancel={() => setEditTarget(null)}
        onOk={handleEdit}
        confirmLoading={saving}
        okText="保存"
        destroyOnHidden
      >
        <Form form={editForm} layout="vertical">
          <Form.Item name="name" label="租户名称" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="status" label="状态" rules={[{ required: true }]}>
            <InputNumber min={0} max={1} style={{ width: '100%' }} placeholder="1=启用 0=停用" />
          </Form.Item>
          {quotaFields}
        </Form>
      </Modal>

      {/* 重置租户管理员 */}
      <Modal
        title={`重置管理员 - ${resetTarget?.name ?? ''}`}
        open={!!resetTarget}
        onCancel={() => setResetTarget(null)}
        onOk={handleResetAdmin}
        okText="确认重置"
        destroyOnHidden
      >
        <Typography.Paragraph type="secondary">
          重置后原管理员密码立即失效，新初始密码仅展示一次。
        </Typography.Paragraph>
        <Form form={resetForm} layout="vertical">
          <Form.Item name="username" label="管理员账号（留空则沿用默认 admin）">
            <Input placeholder="admin" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
