import { useEffect, useState } from 'react';
import { Button, Card, Divider, Form, Input, Spin, Switch, Typography, App, Tag } from 'antd';
import { toolCatalogApi, toolConfigApi } from '../api/toolConfig';
import { menuApi } from '../api/menus';
import type {
  GrantableMenu,
  ToolCatalogItem,
  ToolConfigMasked,
  ToolConfigReq,
} from '../api/types';

/** 工具码 → tool_config 开关字段（新增工具时在此映射）。 */
const TOOL_SWITCH_FIELD: Record<string, string> = {
  weather: 'weatherEnabled',
  tavily: 'tavilyEnabled',
};

/** 功能配置：① 功能菜单租户总开关 ② Agent 工具启停（目录均由后端下发）。 */
export default function ToolConfigPage() {
  const { message } = App.useApp();
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [savingMenus, setSavingMenus] = useState(false);
  const [masked, setMasked] = useState<ToolConfigMasked | null>(null);
  const [menus, setMenus] = useState<GrantableMenu[]>([]);
  const [catalog, setCatalog] = useState<ToolCatalogItem[]>([]);

  const load = async () => {
    setLoading(true);
    try {
      const [cfg, grantable, tools] = await Promise.all([
        toolConfigApi.get(),
        menuApi.grantable(),
        toolCatalogApi.list(),
      ]);
      setMasked(cfg);
      setMenus(grantable);
      setCatalog(tools);
      form.setFieldsValue({
        weatherEnabled: !!cfg.weatherEnabled,
        tavilyEnabled: !!cfg.tavilyEnabled,
        tavilyApiKey: '',
      });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const tavilyEnabled = Form.useWatch('tavilyEnabled', form);

  // ---- 功能菜单租户总开关 ----
  const toggleMenu = (code: string, checked: boolean) => {
    setMenus((prev) => prev.map((m) => (m.code === code ? { ...m, enabled: checked } : m)));
  };

  const saveMenus = async () => {
    setSavingMenus(true);
    try {
      const updated = await menuApi.updateTenant(menus.filter((m) => m.enabled).map((m) => m.code));
      setMenus(updated);
      message.success('功能菜单总开关已保存');
    } finally {
      setSavingMenus(false);
    }
  };

  // ---- Agent 工具 ----
  const onFinish = async (v: {
    weatherEnabled: boolean;
    tavilyEnabled: boolean;
    tavilyApiKey?: string;
  }) => {
    // 前端提前校验（后端同样强校验）
    if (v.tavilyEnabled && !v.tavilyApiKey?.trim() && !masked?.tavilyApiKeyConfigured) {
      message.warning('启用 Tavily 前请先配置 API Key');
      return;
    }
    const req: ToolConfigReq = {
      weatherEnabled: v.weatherEnabled,
      tavilyEnabled: v.tavilyEnabled,
    };
    const key = v.tavilyApiKey?.trim();
    if (key) req.tavilyApiKey = key;

    setSaving(true);
    try {
      const updated = await toolConfigApi.update(req);
      setMasked(updated);
      form.setFieldValue('tavilyApiKey', '');
      message.success('工具配置已保存');
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return <Spin style={{ width: '100%', marginTop: 120 }} size="large" />;
  }

  return (
    <div style={{ maxWidth: 720 }}>
      <Typography.Title level={4}>功能配置</Typography.Title>

      {/* ① 功能菜单租户总开关 */}
      <Card size="small" style={{ marginBottom: 16 }} title="功能菜单（租户总开关）">
        {menus.map((m) => (
          <div
            key={m.code}
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              padding: '6px 0',
            }}
          >
            <Typography.Text>{m.name}</Typography.Text>
            <Switch
              checked={m.enabled}
              checkedChildren="开"
              unCheckedChildren="关"
              onChange={(checked) => toggleMenu(m.code, checked)}
            />
          </div>
        ))}
        <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 12 }}>
          关闭后，租户内所有普通用户均不可使用该功能（后端强制拦截），可在用户管理中逐个下放。
        </Typography.Paragraph>
        <Button type="primary" loading={savingMenus} onClick={saveMenus}>
          保存菜单开关
        </Button>
      </Card>

      <Divider />

      {/* ② Agent 工具（卡片来自后端工具目录） */}
      <Typography.Title level={5}>Agent 工具</Typography.Title>
      <Form form={form} layout="vertical" onFinish={onFinish}>
        {catalog.map((t) => {
          const field = TOOL_SWITCH_FIELD[t.code];
          const keyConfigured = t.code === 'tavily' && masked?.tavilyApiKeyConfigured;
          return (
            <Card key={t.code} size="small" style={{ marginBottom: 16 }}>
              <div
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'flex-start',
                  marginBottom: field === 'tavilyEnabled' && tavilyEnabled ? 16 : 0,
                }}
              >
                <div>
                  <Typography.Text strong>
                    {t.name}（{t.fnName}）
                  </Typography.Text>
                  <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
                    {t.description}
                    {keyConfigured && (
                      <Tag color="green" style={{ marginLeft: 8 }}>
                        Key 已配置
                      </Tag>
                    )}
                  </Typography.Paragraph>
                </div>
                {field ? (
                  <Form.Item name={field} valuePropName="checked" noStyle>
                    <Switch checkedChildren="开" unCheckedChildren="关" />
                  </Form.Item>
                ) : (
                  <Switch disabled />
                )}
              </div>
              {t.requiresKey && t.code === 'tavily' && tavilyEnabled && (
                <Form.Item
                  name="tavilyApiKey"
                  label="Tavily API Key"
                  style={{ marginTop: 16, marginBottom: 0 }}
                >
                  <Input.Password
                    placeholder={masked?.tavilyApiKeyConfigured ? '已配置，留空不修改' : '请输入 Tavily API Key'}
                  />
                </Form.Item>
              )}
            </Card>
          );
        })}

        <Button type="primary" htmlType="submit" loading={saving} size="large">
          保存工具配置
        </Button>
      </Form>
    </div>
  );
}
