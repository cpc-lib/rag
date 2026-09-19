import { useEffect, useState } from 'react';
import {
  App,
  Button,
  Checkbox,
  Empty,
  Input,
  List,
  Modal,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
} from 'antd';
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { userApi } from '../api/users';
import { kbApi } from '../api/knowledgeBases';
import { promptApi } from '../api/prompts';
import { menuApi } from '../api/menus';
import { toolCatalogApi } from '../api/toolConfig';
import type {
  GrantableMenu,
  KnowledgeBase,
  PromptTemplate,
  TenantUser,
  ToolCatalogItem,
} from '../api/types';

export default function UsersPage() {
  const { message, modal } = App.useApp();
  const [data, setData] = useState<TenantUser[]>([]);
  const [kbs, setKbs] = useState<KnowledgeBase[]>([]);
  const [prompts, setPrompts] = useState<PromptTemplate[]>([]);
  const [grantableMenus, setGrantableMenus] = useState<GrantableMenu[]>([]);
  const [toolCatalog, setToolCatalog] = useState<ToolCatalogItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [statusLoadingId, setStatusLoadingId] = useState<number | null>(null);

  // 新建用户
  const [createOpen, setCreateOpen] = useState(false);
  const [createUsername, setCreateUsername] = useState('');

  // 针对用户的菜单 / 工具列表勾选（两个独立弹窗）
  const [menuTarget, setMenuTarget] = useState<TenantUser | null>(null);
  const [menuChecked, setMenuChecked] = useState<string[]>([]);
  const [toolTarget, setToolTarget] = useState<TenantUser | null>(null);
  const [toolChecked, setToolChecked] = useState<string[]>([]);

  // 知识库 + 提示词数据授权
  const [grantTarget, setGrantTarget] = useState<TenantUser | null>(null);
  const [grantIds, setGrantIds] = useState<number[]>([]);
  const [grantPromptIds, setGrantPromptIds] = useState<number[]>([]);

  const load = async () => {
    setLoading(true);
    try {
      setData(await userApi.list());
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    kbApi.list().then(setKbs);
    promptApi.list().then(setPrompts);
    menuApi.grantable().then(setGrantableMenus);
    toolCatalogApi.list().then(setToolCatalog);
  }, []);

  const menuLabel: Record<string, string> = Object.fromEntries(
    grantableMenus.map((m) => [m.code, m.name]),
  );
  const toolLabel: Record<string, string> = Object.fromEntries(
    toolCatalog.map((t) => [t.code, t.name]),
  );

  /** 一次性初始密码弹窗（创建 / 重置共用）。 */
  const showPassword = (title: string, username: string, pwd: string) => {
    modal.info({
      title,
      width: 480,
      content: (
        <div style={{ marginTop: 12 }}>
          <Typography.Paragraph>
            账号：<Typography.Text strong>{username}</Typography.Text>
          </Typography.Paragraph>
          <Typography.Paragraph>初始密码（仅展示一次，请立即复制保存）：</Typography.Paragraph>
          <Typography.Text
            copyable
            code
            style={{ fontSize: 16, padding: '8px 12px', background: '#f6ffed', display: 'inline-block' }}
          >
            {pwd}
          </Typography.Text>
        </div>
      ),
      okText: '我已保存',
    });
  };

  const handleCreate = async () => {
    const username = createUsername.trim();
    if (!username) {
      message.warning('请输入账号');
      return;
    }
    setSaving(true);
    try {
      const resp = await userApi.create(username);
      message.success('用户创建成功');
      setCreateOpen(false);
      setCreateUsername('');
      showPassword('用户创建成功', resp.user.username, resp.initialPassword);
      load();
    } finally {
      setSaving(false);
    }
  };

  /** 账号启停：表格内开关直接保存。 */
  const toggleStatus = async (u: TenantUser, checked: boolean) => {
    setStatusLoadingId(u.id);
    try {
      await userApi.update(u.id, { status: checked ? 1 : 0 });
      message.success(`账号已${checked ? '启用' : '停用'}`);
      load();
    } finally {
      setStatusLoadingId(null);
    }
  };

  // ---------- 菜单 / 工具列表勾选 ----------
  const openMenu = (u: TenantUser) => {
    setMenuTarget(u);
    setMenuChecked(u.menuCodes);
  };

  const openTool = (u: TenantUser) => {
    setToolTarget(u);
    setToolChecked(u.toolCodes);
  };

  const handleMenuSave = async () => {
    if (!menuTarget) return;
    setSaving(true);
    try {
      await userApi.update(menuTarget.id, { menuCodes: menuChecked });
      message.success('菜单授权已保存');
      setMenuTarget(null);
      load();
    } finally {
      setSaving(false);
    }
  };

  const handleToolSave = async () => {
    if (!toolTarget) return;
    setSaving(true);
    try {
      await userApi.update(toolTarget.id, { toolCodes: toolChecked });
      message.success('工具授权已保存');
      setToolTarget(null);
      load();
    } finally {
      setSaving(false);
    }
  };

  const handleResetPassword = async (u: TenantUser) => {
    const resp = await userApi.resetPassword(u.id);
    showPassword('密码已重置', u.username, resp.initialPassword);
  };

  const openGrant = (u: TenantUser) => {
    setGrantTarget(u);
    setGrantIds(u.kbIds);
    setGrantPromptIds(u.promptIds);
  };

  const toggleKb = (kbId: number, checked: boolean) => {
    setGrantIds((prev) =>
      checked ? [...prev, kbId] : prev.filter((i) => i !== kbId),
    );
    // 收回知识库时，一并收回其下全部提示词授权。
    if (!checked) {
      const kbPromptIds = prompts.filter((p) => p.kbId === kbId).map((p) => p.id);
      setGrantPromptIds((prev) => prev.filter((i) => !kbPromptIds.includes(i)));
    }
  };

  const togglePrompt = (pid: number, checked: boolean) => {
    setGrantPromptIds((prev) =>
      checked ? [...prev, pid] : prev.filter((i) => i !== pid),
    );
  };

  const handleGrant = async () => {
    if (!grantTarget) return;
    setSaving(true);
    try {
      await userApi.grantKbs(grantTarget.id, grantIds, grantPromptIds);
      message.success('知识库与提示词授权已更新');
      setGrantTarget(null);
      load();
    } finally {
      setSaving(false);
    }
  };

  const columns = [
    { title: '账号', dataIndex: 'username' },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (s: number, u: TenantUser) => (
        <Switch
          checked={s === 1}
          size="small"
          loading={statusLoadingId === u.id}
          onChange={(checked) => toggleStatus(u, checked)}
        />
      ),
    },
    {
      title: '功能菜单 / 工具',
      dataIndex: 'menuCodes',
      width: 220,
      render: (_: unknown, u: TenantUser) => (
        <Space size={4} wrap>
          {u.menuCodes.map((c) => (
            <Tag key={c}>{menuLabel[c] ?? c}</Tag>
          ))}
          {u.toolCodes.map((c) => (
            <Tag key={c} color="orange">
              {toolLabel[c] ?? c}
            </Tag>
          ))}
          {u.menuCodes.length === 0 && u.toolCodes.length === 0 && (
            <Typography.Text type="warning">无</Typography.Text>
          )}
        </Space>
      ),
    },
    {
      title: '知识库 / 提示词',
      dataIndex: 'kbIds',
      width: 120,
      render: (_: unknown, u: TenantUser) => (
        <span>
          {u.kbIds.length} / {u.promptIds.length}
        </span>
      ),
    },
    {
      title: '操作',
      width: 300,
      render: (_: unknown, u: TenantUser) => (
        <Space size={4} wrap>
          <Button size="small" onClick={() => openMenu(u)}>
            菜单授权
          </Button>
          <Button size="small" onClick={() => openTool(u)}>
            工具授权
          </Button>
          <Button size="small" onClick={() => openGrant(u)}>
            数据授权
          </Button>
          <Button size="small" icon={<ReloadOutlined />} onClick={() => handleResetPassword(u)}>
            重置密码
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Space style={{ marginBottom: 16, justifyContent: 'space-between', width: '100%' }}>
        <Typography.Title level={4} style={{ margin: 0 }}>
          用户管理
        </Typography.Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
          新建用户
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

      {/* 新建用户 */}
      <Modal
        title="新建用户"
        open={createOpen}
        okText="创建"
        confirmLoading={saving}
        onOk={handleCreate}
        onCancel={() => setCreateOpen(false)}
        destroyOnClose
      >
        <Input
          placeholder="登录账号，2 ~ 50 位"
          value={createUsername}
          onChange={(e) => setCreateUsername(e.target.value)}
        />
        <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>
          新用户默认拥有智能问答、AI 画图菜单，暂无知识库授权。
        </Typography.Paragraph>
      </Modal>

      {/* 菜单授权：菜单列表逐行勾选 */}
      <Modal
        title={`菜单授权 - ${menuTarget?.username ?? ''}`}
        open={!!menuTarget}
        width={520}
        okText="保存"
        confirmLoading={saving}
        onOk={handleMenuSave}
        onCancel={() => setMenuTarget(null)}
        destroyOnClose
      >
        <Checkbox.Group
          value={menuChecked}
          onChange={(vals) => setMenuChecked(vals as string[])}
          style={{ width: '100%' }}
        >
          <List
            size="small"
            bordered
            dataSource={grantableMenus}
            renderItem={(m) => (
              <List.Item>
                <Checkbox value={m.code} style={{ width: '100%' }}>
                  <Space>
                    {m.name}
                    {!m.enabled && <Tag color="warning">租户未启用</Tag>}
                  </Space>
                </Checkbox>
              </List.Item>
            )}
          />
        </Checkbox.Group>
        <Typography.Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>
          在菜单列表上勾选后保存，即配置给该用户；租户未启用的菜单可预先授权，开关打开后生效。
        </Typography.Paragraph>
      </Modal>

      {/* 工具授权：工具列表逐行勾选 */}
      <Modal
        title={`工具授权 - ${toolTarget?.username ?? ''}`}
        open={!!toolTarget}
        width={520}
        okText="保存"
        confirmLoading={saving}
        onOk={handleToolSave}
        onCancel={() => setToolTarget(null)}
        destroyOnClose
      >
        <Checkbox.Group
          value={toolChecked}
          onChange={(vals) => setToolChecked(vals as string[])}
          style={{ width: '100%' }}
        >
          <List
            size="small"
            bordered
            dataSource={toolCatalog}
            renderItem={(t) => (
              <List.Item>
                <Checkbox value={t.code} style={{ width: '100%' }}>
                  <Space>
                    {t.name}
                    {!t.enabled && <Tag color="warning">租户未启用</Tag>}
                    {t.requiresKey && !t.apiKeyConfigured && (
                      <Tag color="error">Key 未配置</Tag>
                    )}
                  </Space>
                </Checkbox>
              </List.Item>
            )}
          />
        </Checkbox.Group>
        <Typography.Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>
          在工具列表上勾选后保存，即配置给该用户；工具实际可用还需租户开关开启、Key 已配置。
        </Typography.Paragraph>
      </Modal>

      {/* 知识库 + 提示词数据授权 */}
      <Modal
        title={`数据授权 - ${grantTarget?.username ?? ''}`}
        open={!!grantTarget}
        width={600}
        okText="保存"
        confirmLoading={saving}
        onOk={handleGrant}
        onCancel={() => setGrantTarget(null)}
        destroyOnClose
      >
        {kbs.length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="本租户暂无知识库" />
        ) : (
          <div style={{ maxHeight: 460, overflow: 'auto' }}>
            {kbs.map((k) => {
              const kbChecked = grantIds.includes(k.id);
              const kbPrompts = prompts.filter((p) => p.kbId === k.id);
              return (
                <div key={k.id} style={{ marginBottom: 12 }}>
                  <Checkbox
                    checked={kbChecked}
                    onChange={(e) => toggleKb(k.id, e.target.checked)}
                  >
                    <Typography.Text strong>{k.name}</Typography.Text>
                  </Checkbox>
                  <div style={{ paddingLeft: 24 }}>
                    {kbPrompts.length === 0 ? (
                      <Typography.Text type="warning" style={{ fontSize: 12 }}>
                        该知识库暂无提示词模板
                      </Typography.Text>
                    ) : (
                      kbPrompts.map((p) => (
                        <div key={p.id}>
                          <Checkbox
                            disabled={!kbChecked}
                            checked={grantPromptIds.includes(p.id)}
                            onChange={(e) => togglePrompt(p.id, e.target.checked)}
                          >
                            {p.name}
                            {p.isDefault && (
                              <Tag color="blue" style={{ marginLeft: 6 }}>
                                默认
                              </Tag>
                            )}
                          </Checkbox>
                        </div>
                      ))
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        )}
        <Typography.Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>
          提示词需逐个授权；保存后将完全替换该用户现有的知识库与提示词授权。
        </Typography.Paragraph>
      </Modal>
    </div>
  );
}
