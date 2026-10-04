import { useEffect, useMemo, useState } from 'react';
import {
  App,
  Button,
  Card,
  Checkbox,
  Col,
  Empty,
  Input,
  List,
  Pagination,
  Popconfirm,
  Row,
  Select,
  Space,
  Spin,
  Tag,
  Typography,
  Upload,
} from 'antd';
import {
  DeleteOutlined,
  DownloadOutlined,
  FileTextOutlined,
  ReloadOutlined,
  SaveOutlined,
  SearchOutlined,
  TranslationOutlined,
  UploadOutlined,
} from '@ant-design/icons';
import { subtitleApi } from '../api/subtitles';
import type { Subtitle, SubtitleCue, SubtitleListItem, TranslateLang } from '../api/types';
import { createSHA256 } from 'hash-wasm';

const { Dragger } = Upload;
const { Text } = Typography;

const formatTime = (iso: string | null) =>
  iso ? new Date(iso).toLocaleString('zh-CN') : '';

export default function SubtitlePage() {
  const { message } = App.useApp();
  const [list, setList] = useState<SubtitleListItem[]>([]);
  const [current, setCurrent] = useState<Subtitle | null>(null);
  /** 目标语言取自租户维护列表（在“目标语言管理”页维护） */
  const [targetLang, setTargetLang] = useState('');
  const [langs, setLangs] = useState<TranslateLang[]>([]);
  const [uploading, setUploading] = useState(false);
  const [loadingList, setLoadingList] = useState(false);
  const [translating, setTranslating] = useState(false);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);
  /** 勾选的序号（1 起）；默认不勾选，仅翻译勾选条目 */
  const [selected, setSelected] = useState<Set<number>>(new Set());
  /** 前端分页：字幕条可能上千条，避免一次渲染 */
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(50);
  /** 左侧历史记录搜索关键字（匹配文件名/目标语言） */
  const [historyKw, setHistoryKw] = useState('');
  /** 右侧字幕条搜索关键字与范围（文本内容/翻译内容） */
  const [cueKw, setCueKw] = useState('');
  const [cueScope, setCueScope] = useState<'text' | 'translated'>('text');
  /** 正在编辑的字幕条序号：过滤状态下该行即使改后不再命中也保留，避免输入时行突然消失 */
  const [editingIndex, setEditingIndex] = useState<number | null>(null);

  const loadList = async () => {
    setLoadingList(true);
    try {
      const data = await subtitleApi.list();
      setList(data);
    } finally {
      setLoadingList(false);
    }
  };

  const loadLangs = async () => {
    const data = await subtitleApi.listLangs();
    setLangs(data);
    // 当前选中语言已被删除时回落到第一项
    setTargetLang((prev) => (data.some((l) => l.name === prev) ? prev : data[0]?.name ?? ''));
  };

  useEffect(() => {
    loadLangs();
    loadList();
  }, []);

  /** 历史记录前端过滤：文件名 + 目标语言标签 */
  const filteredList = useMemo(() => {
    const kw = historyKw.trim().toLowerCase();
    if (!kw) return list;
    return list.filter(
      (item) =>
        item.originalName.toLowerCase().includes(kw) ||
        (item.targetLang ?? '').toLowerCase().includes(kw),
    );
  }, [list, historyKw]);

  /** 当前字幕按搜索范围过滤后的条目（分页作用于过滤结果） */
  const filteredCues = useMemo(() => {
    if (!current) return [];
    const kw = cueKw.trim().toLowerCase();
    if (!kw) return current.cues;
    return current.cues.filter((c) =>
      // 正在编辑的行豁免过滤：否则译文/正文一改得不再命中关键字，行会立刻从列表消失
      c.index === editingIndex ||
      (cueScope === 'text' ? c.text : c.translated ?? '').toLowerCase().includes(kw),
    );
  }, [current, cueKw, cueScope, editingIndex]);

  const handleUpload = async (file: File) => {
    if (!/\.(vtt|srt|ass)$/i.test(file.name)) {
      message.error('仅支持 .vtt / .srt / .ass 格式字幕文件');
      return Upload.LIST_IGNORE;
    }
    setUploading(true);
    try {
      // 秒传：计算 SHA-256，同文件已存在时后端直接复用
      const hasher = await createSHA256();
      hasher.update(new Uint8Array(await file.arrayBuffer()));
      const view = await subtitleApi.upload(file, hasher.digest('hex'));
      setCurrent(view);
      setSelected(new Set());
      setPage(1);
      setDirty(false);
      await loadList();
      message.success('字幕转换成功');
    } finally {
      setUploading(false);
    }
    return false; // 阻止 antd 自动上传
  };

  const handleSelect = async (item: SubtitleListItem) => {
    if (dirty && current) {
      const ok = await new Promise<boolean>((resolve) => {
        // 简单确认：有未保存修改时提示
        const res = window.confirm('当前有未保存的修改，切换将丢失，是否继续？');
        resolve(res);
      });
      if (!ok) return;
    }
    const view = await subtitleApi.get(item.id);
    setCurrent(view);
    setSelected(new Set());
    setPage(1);
  };

  const deleteSubtitle = async (item: SubtitleListItem) => {
    await subtitleApi.remove(item.id);
    message.success('字幕记录已删除');
    if (current?.id === item.id) {
      setCurrent(null);
      setSelected(new Set());
      setDirty(false);
    }
    await loadList();
  };

  const handleTranslate = async () => {
    if (!current) return;
    if (!targetLang) {
      message.warning('请先选择或添加目标语言');
      return;
    }
    if (selected.size === 0) {
      message.warning('请先勾选要翻译的字幕条');
      return;
    }
    setTranslating(true);
    try {
      const view = await subtitleApi.translate(current.id, {
        targetLang,
        indices: [...selected].sort((a, b) => a - b),
      });
      setCurrent(view);
      setSelected(new Set());
      setPage(1);
      setDirty(false);
      message.success('翻译完成');
    } finally {
      setTranslating(false);
    }
  };

  const handleToggleCue = (index: number, checked: boolean) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (checked) {
        next.add(index);
      } else {
        next.delete(index);
      }
      return next;
    });
  };

  const handleToggleAll = (checked: boolean) => {
    if (!current) return;
    setSelected(checked ? new Set(current.cues.map((c) => c.index)) : new Set());
  };

  const handleCueTextChange = (index: number, text: string) => {
    if (!current) return;
    const cues = current.cues.map((c) =>
      c.index === index ? { ...c, text } : c,
    );
    setCurrent({ ...current, cues });
    setDirty(true);
  };

  /** 译文人工修改（保存时由后端随原文一起落库） */
  const handleTranslatedChange = (index: number, translated: string) => {
    if (!current) return;
    const cues = current.cues.map((c) =>
      c.index === index ? { ...c, translated } : c,
    );
    setCurrent({ ...current, cues });
    setDirty(true);
  };

  const handleSave = async () => {
    if (!current) return;
    setSaving(true);
    try {
      const view = await subtitleApi.update(current.id, { cues: current.cues });
      setCurrent(view);
      setDirty(false);
      message.success('已保存');
    } finally {
      setSaving(false);
    }
  };

  const handleDownload = async () => {
    if (!current) return;
    const blob = await subtitleApi.download(current.id);
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    // 文件名带目标语言，便于区分不同语言版本（与后端归档/下载名一致）
    const dot = current.originalName.lastIndexOf('.');
    const base = dot > 0 ? current.originalName.slice(0, dot) : current.originalName;
    a.download = base + (current.targetLang ? `_${current.targetLang}` : '') + '.srt';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  };

  return (
    <Row gutter={16} style={{ height: 'calc(100vh - 120px)' }}>
      {/* 左侧：上传 + 历史列表 */}
      <Col span={8} style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        <Card size="small" title="上传字幕">
          <Dragger
            name="file"
            accept=".vtt,.srt,.ass"
            multiple={false}
            showUploadList={false}
            beforeUpload={handleUpload}
            style={{ padding: '12px 0' }}
          >
            <p className="ant-upload-drag-icon">
              <UploadOutlined />
            </p>
            <p className="ant-upload-text">点击或拖拽 .vtt / .srt / .ass 文件到此处</p>
            <p className="ant-upload-hint">自动转换为 SRT 格式，时间轴保持不变</p>
          </Dragger>
          {uploading && (
            <div style={{ textAlign: 'center', marginTop: 8 }}>
              <Spin /> <Text type="secondary">转换中…</Text>
            </div>
          )}
        </Card>

        <Card
          size="small"
          title="历史记录"
          extra={
            <Button
              size="small"
              icon={<ReloadOutlined />}
              loading={loadingList}
              onClick={loadList}
            >
              刷新
            </Button>
          }
          style={{ flex: 1, overflow: 'auto' }}
          styles={{ body: { padding: 0 } }}
        >
          <div style={{ padding: '8px 12px', borderBottom: '1px solid #f0f0f0' }}>
            <Input
              size="small"
              allowClear
              prefix={<SearchOutlined style={{ color: '#bfbfbf' }} />}
              placeholder="搜索文件名 / 语言"
              value={historyKw}
              onChange={(e) => setHistoryKw(e.target.value)}
            />
          </div>
          {loadingList ? (
            <div style={{ textAlign: 'center', padding: 24 }}>
              <Spin />
            </div>
          ) : list.length === 0 ? (
            <Empty description="暂无记录" image={Empty.PRESENTED_IMAGE_SIMPLE} />
          ) : filteredList.length === 0 ? (
            <Empty description="无匹配记录" image={Empty.PRESENTED_IMAGE_SIMPLE} />
          ) : (
            <List
              dataSource={filteredList}
              renderItem={(item) => (
                <List.Item
                  onClick={() => handleSelect(item)}
                  style={{
                    cursor: 'pointer',
                    padding: '12px 16px',
                    background: current?.id === item.id ? '#e6f4ff' : undefined,
                  }}
                >
                  <List.Item.Meta
                    avatar={<FileTextOutlined style={{ fontSize: 20, color: '#1677ff' }} />}
                    title={
                      <Text ellipsis style={{ maxWidth: 160 }}>
                        {item.originalName}
                      </Text>
                    }
                    description={
                      <Space size={4}>
                        {item.targetLang && (
                          <Tag color="blue" style={{ margin: 0 }}>
                            {item.targetLang}
                          </Tag>
                        )}
                        <Text type="secondary" style={{ fontSize: 12 }}>
                          {item.cueCount} 条 · {formatTime(item.updatedAt)}
                        </Text>
                      </Space>
                    }
                  />
                  <Popconfirm
                    title="删除该字幕记录？"
                    description="将删除全部字幕条及原始上传文件，不可恢复；翻译后保存到文件库的文件会保留"
                    okText="删除"
                    okButtonProps={{ danger: true }}
                    cancelText="取消"
                    onConfirm={() => deleteSubtitle(item)}
                  >
                    <Button
                      size="small"
                      type="link"
                      danger
                      icon={<DeleteOutlined />}
                      onClick={(e) => e.stopPropagation()}
                    >
                      删除
                    </Button>
                  </Popconfirm>
                </List.Item>
              )}
            />
          )}
        </Card>
      </Col>

      {/* 右侧：字幕编辑区 */}
      <Col span={16}>
        {current ? (
          <Card
            title={
              <Space>
                <Text strong>{current.originalName}</Text>
                {current.targetLang && (
                  <Tag color="blue">已翻译：{current.targetLang}</Tag>
                )}
              </Space>
            }
            extra={
              <Space>
                <Select
                  value={targetLang || undefined}
                  onChange={(v) => setTargetLang(v)}
                  options={langs.map((l) => ({ value: l.name, label: l.name }))}
                  style={{ width: 130 }}
                  placeholder="目标语言"
                />
                <Button
                  type="primary"
                  icon={<TranslationOutlined />}
                  loading={translating}
                  onClick={handleTranslate}
                >
                  {selected.size > 0 ? `翻译（已选 ${selected.size}）` : '翻译'}
                </Button>
                <Button
                  icon={<SaveOutlined />}
                  type={dirty ? 'primary' : 'default'}
                  loading={saving}
                  onClick={handleSave}
                >
                  保存
                </Button>
                <Button
                  icon={<DownloadOutlined />}
                  onClick={handleDownload}
                >
                  下载 SRT
                </Button>
              </Space>
            }
            style={{ height: '100%', overflow: 'auto' }}
            styles={{ body: { padding: 0 } }}
          >
            <div style={{ position: 'sticky', top: 0, zIndex: 2, background: '#fff' }}>
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 8,
                  padding: '8px 12px',
                  borderBottom: '1px solid #f0f0f0',
                }}
              >
                <Input
                  size="small"
                  allowClear
                  prefix={<SearchOutlined style={{ color: '#bfbfbf' }} />}
                  placeholder="搜索字幕条"
                  value={cueKw}
                  style={{ width: 220 }}
                  onChange={(e) => {
                    setCueKw(e.target.value);
                    setPage(1);
                  }}
                />
                <Select
                  size="small"
                  value={cueScope}
                  style={{ width: 110 }}
                  onChange={(v: 'text' | 'translated') => {
                    setCueScope(v);
                    setPage(1);
                  }}
                  options={[
                    { value: 'text', label: '文本内容' },
                    { value: 'translated', label: '翻译内容' },
                  ]}
                />
                {cueKw.trim() && (
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    匹配 {filteredCues.length} / {current.cues.length} 条
                  </Text>
                )}
              </div>
              <div
                style={{
                  display: 'grid',
                  gridTemplateColumns: '72px 200px 1fr 1fr',
                  gap: 8,
                  padding: 12,
                  borderBottom: '1px solid #f0f0f0',
                  fontWeight: 600,
                  background: '#fafafa',
                }}
              >
                <Checkbox
                  checked={current.cues.length > 0 && selected.size === current.cues.length}
                  indeterminate={selected.size > 0 && selected.size < current.cues.length}
                  onChange={(e) => handleToggleAll(e.target.checked)}
                >
                  序号
                </Checkbox>
                <span>时间轴（不可修改）</span>
                <span>文本内容</span>
                <span>翻译内容（可人工修改）</span>
              </div>
            </div>
            {filteredCues.length === 0 ? (
              <div style={{ padding: 24 }}>
                <Empty description="无匹配字幕条" image={Empty.PRESENTED_IMAGE_SIMPLE} />
              </div>
            ) : (
              filteredCues
                .slice((page - 1) * pageSize, page * pageSize)
                .map((cue) => (
                  <CueRow
                    key={cue.index}
                    cue={cue}
                    checked={selected.has(cue.index)}
                    onChange={(text) => handleCueTextChange(cue.index, text)}
                    onTranslatedChange={(text) => handleTranslatedChange(cue.index, text)}
                    onCheck={(checked) => handleToggleCue(cue.index, checked)}
                    onEditFocus={() => setEditingIndex(cue.index)}
                    onEditBlur={() => setEditingIndex(null)}
                  />
                ))
            )}
            <div style={{ display: 'flex', justifyContent: 'flex-end', padding: 12 }}>
              <Pagination
                current={page}
                pageSize={pageSize}
                total={filteredCues.length}
                showSizeChanger
                pageSizeOptions={[20, 50, 100]}
                showTotal={(t) => `共 ${t} 条`}
                onChange={(p, s) => {
                  setPage(p);
                  setPageSize(s);
                }}
              />
            </div>
          </Card>
        ) : (
          <Card style={{ height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <Empty
              description="上传字幕或选择左侧记录开始编辑"
              image={Empty.PRESENTED_IMAGE_SIMPLE}
            />
          </Card>
        )}
      </Col>
    </Row>
  );
}

function CueRow({
  cue,
  checked,
  onChange,
  onTranslatedChange,
  onCheck,
  onEditFocus,
  onEditBlur,
}: {
  cue: SubtitleCue;
  checked: boolean;
  onChange: (text: string) => void;
  onTranslatedChange: (text: string) => void;
  onCheck: (checked: boolean) => void;
  onEditFocus: () => void;
  onEditBlur: () => void;
}) {
  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: '72px 200px 1fr 1fr',
        gap: 8,
        padding: 12,
        borderBottom: '1px solid #f0f0f0',
        alignItems: 'start',
      }}
    >
      <div style={{ paddingTop: 6, textAlign: 'center' }}>
        <Checkbox checked={checked} onChange={(e) => onCheck(e.target.checked)} />
        <div style={{ color: '#8c8c8c', fontSize: 12, lineHeight: '16px' }}>{cue.index}</div>
      </div>
      <div
        style={{
          padding: '4px 8px',
          background: '#f5f5f5',
          borderRadius: 4,
          fontFamily: 'monospace',
          fontSize: 12,
          color: '#595959',
          userSelect: 'all',
        }}
      >
        {cue.start}
        <br />
        <span style={{ color: '#bfbfbf' }}>↓</span>
        <br />
        {cue.end}
      </div>
      <div>
        <Input.TextArea
          value={cue.text}
          onChange={(e) => onChange(e.target.value)}
          onFocus={onEditFocus}
          onBlur={onEditBlur}
          autoSize={{ minRows: 1, maxRows: 6 }}
          placeholder="字幕文本"
        />
      </div>
      <div>
        <Input.TextArea
          value={cue.translated ?? ''}
          onChange={(e) => onTranslatedChange(e.target.value)}
          onFocus={onEditFocus}
          onBlur={onEditBlur}
          autoSize={{ minRows: 1, maxRows: 6 }}
          placeholder="未翻译"
          style={{ background: '#f6ffed', borderColor: '#b7eb8f' }}
        />
      </div>
    </div>
  );
}
