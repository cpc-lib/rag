import { useEffect, useState, type ReactNode } from 'react';
import { Layout, Menu, Dropdown, Typography, Space, App, Form, Input, Modal } from 'antd';
import {
  TeamOutlined,
  MessageOutlined,
  BookOutlined,
  SettingOutlined,
  ToolOutlined,
  DashboardOutlined,
  BulbOutlined,
  LogoutOutlined,
  KeyOutlined,
  CloudServerOutlined,
  PictureOutlined,
  EditOutlined,
  FolderOutlined,
  GlobalOutlined,
} from '@ant-design/icons';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../store/auth';
import { authApi } from '../api/auth';
import { menuApi } from '../api/menus';
import type { SidebarMenu } from '../api/types';

const { Sider, Header, Content } = Layout;

/** 图标标识 → 图标组件（目录在后端，图标的具体渲染仍属前端表现层）。 */
const ICONS: Record<string, ReactNode> = {
  team: <TeamOutlined />,
  message: <MessageOutlined />,
  picture: <PictureOutlined />,
  edit: <EditOutlined />,
  book: <BookOutlined />,
  setting: <SettingOutlined />,
  bulb: <BulbOutlined />,
  tool: <ToolOutlined />,
  dashboard: <DashboardOutlined />,
  folder: <FolderOutlined />,
  language: <GlobalOutlined />,
};

const ROLE_TEXT: Record<number, string> = {
  0: '平台管理员',
  1: '租户管理员',
  2: '普通用户',
};

export default function MainLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const { user, clearAuth } = useAuthStore();
  const { message } = App.useApp();
  const [pwdForm] = Form.useForm();
  const [pwdOpen, setPwdOpen] = useState(false);
  const [changing, setChanging] = useState(false);
  const [menus, setMenus] = useState<SidebarMenu[]>([]);

  // 侧边栏目录由后端下发：角色可见范围、租户总开关、个人授权均已在后端过滤
  useEffect(() => {
    menuApi.sidebar().then(setMenus).catch(() => setMenus([]));
  }, [user?.id, user?.userType]);

  // 文档/切片详情页时，侧边栏高亮知识库管理
  const selectedKey = location.pathname.startsWith('/knowledge-bases/')
    ? '/knowledge-bases'
    : location.pathname;

  const handleLogout = async () => {
    try {
      await authApi.logout();
    } catch {
      // 即使后端登出失败也清理本地凭证
    }
    clearAuth();
    navigate('/login', { replace: true });
  };

  const handleChangePassword = async () => {
    const v = await pwdForm.validateFields();
    setChanging(true);
    try {
      await authApi.changePassword({ oldPassword: v.oldPassword, newPassword: v.newPassword });
      message.success('密码修改成功');
      setPwdOpen(false);
      pwdForm.resetFields();
    } finally {
      setChanging(false);
    }
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider
        width={220}
        theme="light"
        style={{
          borderRight: '1px solid #f0f0f0',
          boxShadow: '2px 0 8px rgba(0,0,0,0.03)',
        }}
      >
        <div
          style={{
            height: 64,
            display: 'flex',
            alignItems: 'center',
            gap: 10,
            padding: '0 20px',
            fontWeight: 700,
            fontSize: 16,
            color: '#1677ff',
          }}
        >
          <CloudServerOutlined style={{ fontSize: 22 }} />
          <span>知识库控制台</span>
        </div>
        <Menu
          mode="inline"
          selectedKeys={[selectedKey]}
          items={menus.map((m) => ({
            key: m.path,
            icon: ICONS[m.icon ?? ''] ?? <ToolOutlined />,
            label: m.name,
          }))}
          onClick={({ key }) => navigate(key)}
          style={{ borderInlineEnd: 'none', marginTop: 8 }}
        />
      </Sider>
      <Layout>
        <Header
          style={{
            background: '#fff',
            padding: '0 24px',
            display: 'flex',
            justifyContent: 'flex-end',
            alignItems: 'center',
            borderBottom: '1px solid #f0f0f0',
          }}
        >
          <Dropdown
            menu={{
              items: [
                {
                  key: 'change-password',
                  icon: <KeyOutlined />,
                  label: '修改密码',
                  onClick: () => setPwdOpen(true),
                },
                {
                  key: 'logout',
                  icon: <LogoutOutlined />,
                  label: '退出登录',
                  onClick: handleLogout,
                },
              ],
            }}
          >
            <Space style={{ cursor: 'pointer' }}>
              <Typography.Text strong>{user?.username}</Typography.Text>
              <Typography.Text type="secondary">
                {user ? ROLE_TEXT[user.userType] : ''}
                {user?.tenantName ? ` · ${user.tenantName}` : ''}
              </Typography.Text>
            </Space>
          </Dropdown>
        </Header>
        <Content style={{ margin: 20 }}>
          <Outlet />
        </Content>
      </Layout>

      <Modal
        title="修改密码"
        open={pwdOpen}
        okText="确认修改"
        cancelText="取消"
        confirmLoading={changing}
        onOk={handleChangePassword}
        onCancel={() => {
          setPwdOpen(false);
          pwdForm.resetFields();
        }}
        destroyOnHidden
      >
        <Form form={pwdForm} layout="vertical" style={{ marginTop: 8 }}>
          <Form.Item
            name="oldPassword"
            label="原密码"
            rules={[{ required: true, message: '请输入原密码' }]}
          >
            <Input.Password placeholder="请输入原密码" autoComplete="current-password" />
          </Form.Item>
          <Form.Item
            name="newPassword"
            label="新密码"
            rules={[
              { required: true, message: '请输入新密码' },
              { min: 8, max: 64, message: '密码长度需在 8 ~ 64 位之间' },
            ]}
            extra="长度 8 ~ 64 位"
          >
            <Input.Password placeholder="请输入新密码" autoComplete="new-password" />
          </Form.Item>
          <Form.Item
            name="confirmPassword"
            label="确认新密码"
            dependencies={['newPassword']}
            rules={[
              { required: true, message: '请再次输入新密码' },
              ({ getFieldValue }) => ({
                validator(_, value) {
                  if (!value || getFieldValue('newPassword') === value) {
                    return Promise.resolve();
                  }
                  return Promise.reject(new Error('两次输入的密码不一致'));
                },
              }),
            ]}
          >
            <Input.Password placeholder="请再次输入新密码" autoComplete="new-password" />
          </Form.Item>
        </Form>
      </Modal>
    </Layout>
  );
}
