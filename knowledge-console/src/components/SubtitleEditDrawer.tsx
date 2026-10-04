import { useEffect, useMemo, useState } from 'react';
import { App, Button, Drawer, Empty, Input, Pagination, Select, Spin, Typography } from 'antd';
import { CopyOutlined, SaveOutlined, SearchOutlined } from '@ant-design/icons';
import { libraryApi } from '../api/library';
import { subtitleApi } from '../api/subtitles';
import type { Subtitle, SubtitleCue } from '../api/types';

const { Text } = Typography;

/** 复制文本到剪贴板：优先 Clipboard API，非安全上下文下降级到 execCommand */
async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    // 继续尝试降级方案
  }
  const ta = document.createElement('textarea');
  ta.value = text;
  ta.style.position = 'fixed';
  ta.style.opacity = '0';
  document.body.appendChild(ta);
  ta.select();
  let ok = false;
  try {
    ok = document.execCommand('copy');
  } catch {
    ok = false;
  }
  document.body.removeChild(ta);
  return ok;
}

/** 条目表格三列布局：序号 / 时间轴 / 文本 / 译文 */
const GRID_COLS = '56px 190px 1fr 1fr';

/**
 * 文件库“查看内容”：以字幕编辑同款条目视图展示归档文件关联字幕的原文/译文，
 * 支持按文本/翻译检索与直接修改；保存时原地覆盖该归档文件（不新增版本、不改字幕主数据）。
 */
export default function SubtitleEditDrawer({
  open,
  fileId,
  subtitleId,
  title,
  onClose,
  onSaved,
}: {
  open: boolean;
  /** 被查看/覆盖保存的文件库归档文件 ID */
  fileId: number;
  subtitleId: number;
  title: string;
  onClose: () => void;
  onSaved?: () => void;
}) {
  const { message } = App.useApp();
  const [detail, setDetail] = useState<Subtitle | null>(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);
  /** 条目检索关键字与范围（文本内容/翻译内容） */
  const [kw, setKw] = useState('');
  const [scope, setScope] = useState<'text' | 'translated'>('text');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(50);

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    setLoading(true);
    setDetail(null);
    setDirty(false);
    setKw('');
    setPage(1);
    subtitleApi
      .get(subtitleId)
      .then((v) => {
        if (!cancelled) setDetail(v);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [open, subtitleId]);

  /** 按检索范围过滤条目，分页作用于过滤结果 */
  const filtered = useMemo(() => {
    if (!detail) return [];
    const k = kw.trim().toLowerCase();
    if (!k) return detail.cues;
    return detail.cues.filter((c) =>
      (scope === 'text' ? c.text : c.translated ?? '').toLowerCase().includes(k),
    );
  }, [detail, kw, scope]);

  const updateCue = (index: number, patch: Partial<SubtitleCue>) => {
    if (!detail) return;
    setDetail({
      ...detail,
      cues: detail.cues.map((c) => (c.index === index ? { ...c, ...patch } : c)),
    });
    setDirty(true);
  };

  const handleSave = async () => {
    if (!detail) return;
    setSaving(true);
    try {
      // 原地覆盖该归档文件本身（objectKey 不变，不新增版本）
      await libraryApi.saveCues(fileId, detail.cues);
      setDirty(false);
      message.success('文件已更新');
      onSaved?.();
    } finally {
      setSaving(false);
    }
  };

  /** 复制当前（搜索过滤后）条目文本；译文为空的条目复制译文时跳过 */
  const handleCopy = async (which: 'text' | 'translated') => {
    const lines = filtered
      .map((c) => (which === 'text' ? c.text : c.translated ?? ''))
      .filter((t) => t.trim() !== '');
    if (lines.length === 0) {
      message.warning(which === 'text' ? '暂无可复制的原文' : '暂无可复制的译文');
      return;
    }
    const ok = await copyText(lines.join('\n'));
    if (ok) {
      message.success(`已复制 ${lines.length} 条${which === 'text' ? '原文' : '译文'}`);
    } else {
      message.error('复制失败，请手动选择文本复制');
    }
  };

  return (
    <Drawer
      title={title}
      width="92%"
      open={open}
      onClose={onClose}
      destroyOnClose
      extra={
        <Button
          type={dirty ? 'primary' : 'default'}
          icon={<SaveOutlined />}
          loading={saving}
          disabled={!dirty}
          onClick={handleSave}
        >
          保存
        </Button>
      }
    >
      {loading ? (
        <div style={{ textAlign: 'center', padding: 40 }}>
          <Spin />
        </div>
      ) : !detail ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} />
      ) : (
        <>
          <div style={{ position: 'sticky', top: 0, zIndex: 2, background: '#fff' }}>
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 8,
                padding: '8px 0',
                borderBottom: '1px solid #f0f0f0',
              }}
            >
              <Input
                size="small"
                allowClear
                prefix={<SearchOutlined style={{ color: '#bfbfbf' }} />}
                placeholder="搜索字幕条"
                value={kw}
                style={{ width: 220 }}
                onChange={(e) => {
                  setKw(e.target.value);
                  setPage(1);
                }}
              />
              <Select
                size="small"
                value={scope}
                style={{ width: 110 }}
                onChange={(v: 'text' | 'translated') => {
                  setScope(v);
                  setPage(1);
                }}
                options={[
                  { value: 'text', label: '文本内容' },
                  { value: 'translated', label: '翻译内容' },
                ]}
              />
              {kw.trim() && (
                <Text type="secondary" style={{ fontSize: 12 }}>
                  匹配 {filtered.length} / {detail.cues.length} 条
                </Text>
              )}
              <div style={{ flex: 1 }} />
              <Button
                size="small"
                icon={<CopyOutlined />}
                onClick={() => handleCopy('text')}
              >
                复制原文
              </Button>
              <Button
                size="small"
                icon={<CopyOutlined />}
                onClick={() => handleCopy('translated')}
              >
                复制译文
              </Button>
            </div>
            <div
              style={{
                display: 'grid',
                gridTemplateColumns: GRID_COLS,
                gap: 8,
                padding: 12,
                borderBottom: '1px solid #f0f0f0',
                fontWeight: 600,
                background: '#fafafa',
              }}
            >
              <span>序号</span>
              <span>时间轴（不可修改）</span>
              <span>文本内容</span>
              <span>翻译内容（可人工修改）</span>
            </div>
          </div>
          {filtered.length === 0 ? (
            <div style={{ padding: 24 }}>
              <Empty description="无匹配字幕条" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            </div>
          ) : (
            filtered.slice((page - 1) * pageSize, page * pageSize).map((cue) => (
              <div
                key={cue.index}
                style={{
                  display: 'grid',
                  gridTemplateColumns: GRID_COLS,
                  gap: 8,
                  padding: 12,
                  borderBottom: '1px solid #f0f0f0',
                  alignItems: 'start',
                }}
              >
                <div style={{ paddingTop: 6, textAlign: 'center', color: '#8c8c8c', fontSize: 12 }}>
                  {cue.index}
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
                <Input.TextArea
                  value={cue.text}
                  onChange={(e) => updateCue(cue.index, { text: e.target.value })}
                  autoSize={{ minRows: 1, maxRows: 6 }}
                  placeholder="字幕文本"
                />
                <Input.TextArea
                  value={cue.translated ?? ''}
                  onChange={(e) => updateCue(cue.index, { translated: e.target.value })}
                  autoSize={{ minRows: 1, maxRows: 6 }}
                  placeholder="未翻译"
                  style={{ background: '#f6ffed', borderColor: '#b7eb8f' }}
                />
              </div>
            ))
          )}
          <div style={{ display: 'flex', justifyContent: 'flex-end', padding: 12 }}>
            <Pagination
              current={page}
              pageSize={pageSize}
              total={filtered.length}
              showSizeChanger
              pageSizeOptions={[20, 50, 100]}
              showTotal={(t) => `共 ${t} 条`}
              onChange={(p, s) => {
                setPage(p);
                setPageSize(s);
              }}
            />
          </div>
        </>
      )}
    </Drawer>
  );
}
