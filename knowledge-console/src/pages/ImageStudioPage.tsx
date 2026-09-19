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
  Row,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
} from 'antd';
import { DownloadOutlined } from '@ant-design/icons';
import { imageApi } from '../api/images';
import type { GeneratedImage } from '../api/types';

const SIZE_OPTIONS = [
  { value: '1024*1536', label: '竖版 2:3 · 1024*1536（默认）' },
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

  const loadHistory = async (p: number) => {
    setLoadingHistory(true);
    try {
      const res = await imageApi.list(p, PAGE_SIZE);
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

  const handleGenerate = async () => {
    const v = await form.validateFields();
    setGenerating(true);
    try {
      const img = await imageApi.generate({
        prompt: v.prompt,
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
              initialValues={{ size: '1024*1536' }}
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

      <Card title="我的作品" size="small" style={{ marginTop: 20 }}>
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
              width: 120,
              render: (v: number | null) => formatSize(v),
            },
            {
              title: '时间',
              dataIndex: 'createdAt',
              width: 180,
              render: (v: string | null) => formatTime(v),
            },
            {
              title: '操作',
              width: 90,
              render: (_, img) => (
                <a
                  onClick={(e) => {
                    e.stopPropagation();
                    setDetailImg(img);
                  }}
                >
                  详情
                </a>
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
            onChange: loadHistory,
          }}
          onRow={(img) => ({
            onClick: () => setDetailImg(img),
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
