import { useEffect, useState } from 'react';
import {
  App,
  Button,
  Card,
  Col,
  Descriptions,
  Empty,
  Form,
  Image,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Row,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
} from 'antd';
import { DeleteOutlined, DownloadOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { imageApi } from '../api/images';
import type { GeneratedImage } from '../api/types';

const SIZE_OPTIONS = [
  { value: '2K', label: '2K 高清（默认）· 约 2048*2048' },
  { value: '1K', label: '1K · 约 1280*1280' },
  { value: '4K', label: '4K 超清 · 约 4096*4096（仅 pro 文生图）' },
  { value: '1024*1536', label: '竖版 2:3 · 1024*1536' },
  { value: '1024*1024', label: '方图 1:1 · 1024*1024' },
  { value: '1536*1024', label: '横版 3:2 · 1536*1024' },
  { value: '1120*1440', label: '竖版 · 1120*1440' },
  { value: '1440*1120', label: '横版 · 1440*1120' },
  { value: '1440*1920', label: '竖版高清 · 1440*1920' },
  { value: '1920*1440', label: '横版高清 · 1920*1440' },
];

const PAGE_SIZE = 10;

const formatTime = (iso: string | null) =>
  iso
    ? new Date(iso).toLocaleString('zh-CN')
    : '';

/** 字节大小人类可读。 */
const formatSize = (bytes: number | null) => {
  if (bytes == null) {
    return '—';
  }
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
};

export default function ImageStudioPage() {
  const { message } = App.useApp();
  const [form] = Form.useForm();
  const [generating, setGenerating] = useState(false);
  const [current, setCurrent] = useState<GeneratedImage | null>(null);
  const [history, setHistory] = useState<GeneratedImage[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loadingHistory, setLoadingHistory] = useState(false);
  const [downloadingId, setDownloadingId] = useState<number | null>(null);
  const [detailImg, setDetailImg] = useState<GeneratedImage | null>(null);
  /** 画面描述模糊查询关键词 */
  const [kw, setKw] = useState('');
  const [deletingId, setDeletingId] = useState<number | null>(null);

  const loadHistory = async (p: number, keyword = kw) => {
    setLoadingHistory(true);
    try {
      const res = await imageApi.list(p, PAGE_SIZE, keyword);
      setHistory(res.records);
      setTotal(res.total);
      setPage(p);
    } finally {
      setLoadingHistory(false);
    }
  };

  useEffect(() => {
    loadHistory(1);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 关键词防抖查询
  useEffect(() => {
    const t = setTimeout(() => loadHistory(1), 300);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [kw]);

  const handleDelete = async (img: GeneratedImage) => {
    setDeletingId(img.id);
    try {
      await imageApi.remove(img.id);
      message.success('已删除');
      if (detailImg?.id === img.id) setDetailImg(null);
      loadHistory(page);
    } finally {
      setDeletingId(null);
    }
  };

  const handleGenerate = async () => {
    const v = await form.validateFields();
    setGenerating(true);
    try {
      const img = await imageApi.generate({
        prompt: v.prompt,
        negativePrompt: v.negativePrompt || undefined,
        size: v.size,
        seed: v.seed ?? undefined,
      });
      setCurrent(img);
      message.success('图片已生成');
      loadHistory(1);
    } finally {
      setGenerating(false);
    }
  };

  const handleDownload = async (img: GeneratedImage) => {
    setDownloadingId(img.id);
    try {
      const blob = await imageApi.download(img.id);
      const objectUrl = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = objectUrl;
      a.download = `ai-image-${img.id}.png`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(objectUrl);
    } finally {
      setDownloadingId(null);
    }
  };

  return (
    <div>
      <Typography.Title level={4} style={{ marginTop: 0 }}>
        AI 画图
      </Typography.Title>
      <Row gutter={20}>
        <Col xs={24} lg={9}>
          <Card title="创作" size="small">
            <Form
              form={form}
              layout="vertical"
              initialValues={{ size: '2K' }}
            >
              <Form.Item
                name="prompt"
                label="画面描述"
                rules={[{ required: true, message: '请输入画面描述' }]}
              >
                <Input.TextArea
                  rows={5}
                  showCount
                  maxLength={2000}
                  placeholder="描述主体、风格、光线、镜头、氛围……如：一只橘猫坐在日式居酒屋门口，电影感灯光，胶片质感"
                />
              </Form.Item>
              <Form.Item
                name="negativePrompt"
                label="反向提示词（可选）"
                tooltip="描述不希望出现在画面中的内容，如：低分辨率，低画质，肢体畸形，画面过饱和"
              >
                <Input.TextArea
                  rows={2}
                  showCount
                  maxLength={500}
                  placeholder="低分辨率，低画质，肢体畸形，手指畸形，画面过饱和"
                />
              </Form.Item>
              <Form.Item name="size" label="画幅尺寸">
                <Select options={SIZE_OPTIONS} />
              </Form.Item>
              <Form.Item
                name="seed"
                label="随机种子（可选）"
                tooltip="相同种子 + 相同提示词可生成接近的画面；留空每次随机"
              >
                <InputNumber min={0} max={2147483647} style={{ width: '100%' }} />
              </Form.Item>
              <Button
                type="primary"
                size="large"
                block
                loading={generating}
                onClick={handleGenerate}
              >
                {generating ? '正在生成…' : '生成图片'}
              </Button>
            </Form>
          </Card>
        </Col>
        <Col xs={24} lg={15}>
          <Card title="最新作品" size="small">
            <Spin spinning={generating} tip="正在挥毫泼墨…">
              {current?.url ? (
                <div>
                  <Image
                    src={current.url}
                    style={{ maxHeight: 480, objectFit: 'contain' }}
                  />
                  <Space
                    size={8}
                    wrap
                    style={{ marginTop: 12, justifyContent: 'space-between', width: '100%' }}
                  >
                    <Space size={4} wrap>
                      <Tag color="blue">{current.model}</Tag>
                      <Tag>{current.size}</Tag>
                      {current.seed != null && <Tag>seed={current.seed}</Tag>}
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        {formatTime(current.createdAt)}
                      </Typography.Text>
                    </Space>
                    <Button
                      icon={<DownloadOutlined />}
                      loading={downloadingId === current.id}
                      onClick={() => handleDownload(current)}
                    >
                      下载原图
                    </Button>
                  </Space>
                  <Typography.Paragraph
                    type="secondary"
                    style={{ marginTop: 12, marginBottom: 0 }}
                  >
                    {current.prompt}
                  </Typography.Paragraph>
                </div>
              ) : (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description={generating ? '' : '输入画面描述，开始创作'}
                  style={{ padding: '60px 0' }}
                />
              )}
            </Spin>
          </Card>
        </Col>
      </Row>

      <Card
        title={
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <span>我的作品</span>
            <Space size={8}>
              <Input.Search
                placeholder="画面描述关键词"
                allowClear
                value={kw}
                onChange={(e) => setKw(e.target.value)}
                style={{ width: 240 }}
                prefix={<SearchOutlined />}
              />
              <Button
                icon={<ReloadOutlined />}
                loading={loadingHistory}
                onClick={() => loadHistory(page)}
              >
                刷新
              </Button>
            </Space>
          </div>
        }
        size="small"
        style={{ marginTop: 20 }}
      >
        <Table<GeneratedImage>
          rowKey="id"
          size="middle"
          loading={loadingHistory}
          columns={[
            {
              title: '画面描述',
              dataIndex: 'prompt',
              ellipsis: true,
            },
            {
              title: '文件大小',
              dataIndex: 'fileSize',
              width: 100,
              render: (v: number | null) => formatSize(v),
            },
            {
              title: '时间',
              dataIndex: 'createdAt',
              width: 160,
              render: (v: string | null) => formatTime(v),
            },
            {
              title: '操作',
              width: 120,
              render: (_, img) => (
                <Space size={8}>
                  <a
                    onClick={(e) => {
                      e.stopPropagation();
                      setDetailImg(img);
                    }}
                  >
                    详情
                  </a>
                  <Popconfirm
                    title="确认删除该作品？"
                    description="将删除图片原文件与文件库关联记录，不可恢复"
                    okText="删除"
                    okButtonProps={{ danger: true, loading: deletingId === img.id }}
                    cancelText="取消"
                    onConfirm={() => handleDelete(img)}
                  >
                    <Button
                      type="link"
                      danger
                      size="small"
                      icon={<DeleteOutlined />}
                      loading={deletingId === img.id}
                      onClick={(e) => e.stopPropagation()}
                    >
                      删除
                    </Button>
                  </Popconfirm>
                </Space>
              ),
            },
          ]}
          dataSource={history}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有作品" /> }}
          pagination={{
            current: page,
            pageSize: PAGE_SIZE,
            total,
            showSizeChanger: false,
            onChange: (p) => loadHistory(p),
          }}
          onRow={(img) => ({
            onClick: (e) => {
              // Popconfirm 弹层经 Portal 渲染，React 事件仍会冒泡到行：点击确认/取消时不触发详情
              if ((e.target as HTMLElement).closest('.ant-popover')) return;
              setDetailImg(img);
            },
            style: { cursor: 'pointer' },
          })}
        />
      </Card>

      <Modal
        open={!!detailImg}
        width={640}
        title="作品详情"
        footer={null}
        onCancel={() => setDetailImg(null)}
      >
        {detailImg && (
          <div>
            <Image
              src={detailImg.url ?? ''}
              style={{ maxHeight: 420, objectFit: 'contain' }}
            />
            <Descriptions column={1} size="small" style={{ marginTop: 12 }}>
              <Descriptions.Item label="画面描述">{detailImg.prompt}</Descriptions.Item>
              {detailImg.negativePrompt && (
                <Descriptions.Item label="反向提示词">{detailImg.negativePrompt}</Descriptions.Item>
              )}
              <Descriptions.Item label="模型">{detailImg.model}</Descriptions.Item>
              <Descriptions.Item label="画幅尺寸">{detailImg.size}</Descriptions.Item>
              <Descriptions.Item label="随机种子">
                {detailImg.seed != null ? detailImg.seed : '—'}
              </Descriptions.Item>
              <Descriptions.Item label="文件大小">
                {formatSize(detailImg.fileSize)}
              </Descriptions.Item>
              <Descriptions.Item label="时间">
                {formatTime(detailImg.createdAt)}
              </Descriptions.Item>
            </Descriptions>
            <Button
              type="primary"
              icon={<DownloadOutlined />}
              loading={downloadingId === detailImg.id}
              onClick={() => handleDownload(detailImg)}
              style={{ marginTop: 12 }}
            >
              下载原图
            </Button>
          </div>
        )}
      </Modal>
    </div>
  );
}
