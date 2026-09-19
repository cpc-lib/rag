import { Button, Card, Form, Input, Typography } from 'antd';
import { CloudServerOutlined, LockOutlined, UserOutlined, BankOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useState } from 'react';
import { authApi } from '../api/auth';
import { useAuthStore } from '../store/auth';
import type { LoginReq } from '../api/types';

export default function LoginPage() {
  const navigate = useNavigate();
  const setAuth = useAuthStore((s) => s.setAuth);
  const [loading, setLoading] = useState(false);

  const onFinish = async (values: LoginReq) => {
    setLoading(true);
    try {
      const resp = await authApi.login({
        username: values.username,
        password: values.password,
        tenantCode: values.tenantCode?.trim() || undefined,
      });
      setAuth(resp.token, resp.user);
      navigate(resp.user.userType === 0 ? '/tenants' : '/chat', { replace: true });
    } finally {
      setLoading(false);
    }
  };

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'linear-gradient(135deg, #e8f1ff 0%, #f5f9ff 50%, #eef7ff 100%)',
      }}
    >
      <Card
        style={{ width: 400, borderRadius: 16, boxShadow: '0 8px 32px rgba(22,119,255,0.12)' }}
        styles={{ body: { padding: '36px 32px 28px' } }}
      >
        <div style={{ textAlign: 'center', marginBottom: 28 }}>
          <CloudServerOutlined style={{ fontSize: 40, color: '#1677ff' }} />
          <Typography.Title level={3} style={{ marginTop: 12, marginBottom: 4 }}>
            知识库管理控制台
          </Typography.Title>
          <Typography.Text type="secondary">企业级多租户 RAG 问答平台</Typography.Text>
        </div>
        <Form layout="vertical" onFinish={onFinish} requiredMark={false}>
          <Form.Item
            name="username"
            label="账号"
            rules={[{ required: true, message: '请输入账号' }]}
          >
            <Input size="large" prefix={<UserOutlined />} placeholder="请输入账号" autoComplete="username" />
          </Form.Item>
          <Form.Item
            name="password"
            label="密码"
            rules={[{ required: true, message: '请输入密码' }]}
          >
            <Input.Password
              size="large"
              prefix={<LockOutlined />}
              placeholder="请输入密码"
              autoComplete="current-password"
            />
          </Form.Item>
          <Form.Item name="tenantCode" label="租户编码（选填）">
            <Input
              size="large"
              prefix={<BankOutlined />}
              placeholder="多租户同名账号时填写；平台管理员为 SUPER_TENANT"
            />
          </Form.Item>
          <Button
            type="primary"
            size="large"
            htmlType="submit"
            block
            loading={loading}
            style={{ fontWeight: 500 }}
          >
            登 录
          </Button>
        </Form>
      </Card>
    </div>
  );
}
