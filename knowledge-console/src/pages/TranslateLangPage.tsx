import { useEffect, useState } from 'react';
import { App, AutoComplete, Button, Card, Popconfirm, Space, Table, Typography } from 'antd';
import { DeleteOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { subtitleApi } from '../api/subtitles';
import type { TranslateLang } from '../api/types';

const { Text } = Typography;

/** 常用语言快捷建议（可自由输入任意语言名） */
const LANG_PRESETS = [
  '英语', '日语', '韩语', '法语', '德语', '西班牙语',
  '俄语', '葡萄牙语', '意大利语', '阿拉伯语', '泰语', '越南语',
];

/** 目标语言管理：维护字幕转换可用的翻译目标语言（租户级）。 */
export default function TranslateLangPage() {
  const { message } = App.useApp();
  const [langs, setLangs] = useState<TranslateLang[]>([]);
  const [loading, setLoading] = useState(false);
  const [name, setName] = useState('');
  const [adding, setAdding] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      setLangs(await subtitleApi.listLangs());
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const handleAdd = async () => {
    const n = name.trim();
    if (!n) {
      message.warning('请输入语言名称');
      return;
    }
    setAdding(true);
    try {
      // 后端返回更新后的完整列表
      const data = await subtitleApi.addLang(n);
      setLangs(data);
      setName('');
      message.success('已添加');
    } finally {
      setAdding(false);
    }
  };

  const handleDelete = async (lang: TranslateLang) => {
    await subtitleApi.deleteLang(lang.id);
    await load();
    message.success('已删除');
  };

  return (
    <Card
      title="目标语言管理"
      style={{ minHeight: '100%' }}
      extra={
        <Button icon={<ReloadOutlined />} loading={loading} onClick={load}>
          刷新
        </Button>
      }
    >
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Text type="secondary">
          维护字幕转换可用的翻译目标语言（租户级）。语言名将直接作为翻译提示词的目标语言传参。
        </Text>
        <Space.Compact style={{ width: 360, display: 'flex' }}>
          <AutoComplete
            value={name}
            onChange={setName}
            options={LANG_PRESETS.filter((p) => !langs.some((l) => l.name === p))
              .map((p) => ({ value: p }))}
            placeholder="输入语言名称，如 English / 日本語"
            style={{ flex: 1 }}
          />
          <Button type="primary" icon={<PlusOutlined />} loading={adding} onClick={handleAdd}>
            添加
          </Button>
        </Space.Compact>
        <Table
          rowKey="id"
          loading={loading}
          dataSource={langs}
          pagination={false}
          columns={[
            { title: '语言名称', dataIndex: 'name' },
            {
              title: '操作',
              width: 110,
              render: (_, record) => (
                <Popconfirm title="确认删除该语言？" onConfirm={() => handleDelete(record)}>
                  <Button type="link" danger size="small" icon={<DeleteOutlined />}>
                    删除
                  </Button>
                </Popconfirm>
              ),
            },
          ]}
        />
      </Space>
    </Card>
  );
}
