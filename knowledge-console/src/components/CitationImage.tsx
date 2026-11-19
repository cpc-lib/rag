import { useEffect, useState } from 'react';
import { Modal, Spin, Typography } from 'antd';
import type { CSSProperties } from 'react';
import { http } from '../api/client';

/** chunkId → 已创建的 object URL，引用区与正文复用同一张图，避免重复请求 */
const blobCache = new Map<number, string>();
const loading = new Map<number, Promise<string>>();

function fetchImage(chunkId: number, imageUrl: string): Promise<string> {
  const cached = blobCache.get(chunkId);
  if (cached) {
    return Promise.resolve(cached);
  }
  const inflight = loading.get(chunkId);
  if (inflight) {
    return inflight;
  }
  // 图片接口需鉴权，用统一 http 实例带 Token 拉取 blob
  const path = imageUrl.startsWith('/api/v1') ? imageUrl.slice('/api/v1'.length) : imageUrl;
  const task = http
    .get(path, { responseType: 'blob' })
    .then((resp) => {
      const url = URL.createObjectURL(resp.data as Blob);
      blobCache.set(chunkId, url);
      return url;
    })
    .finally(() => loading.delete(chunkId));
  loading.set(chunkId, task);
  return task;
}

interface Props {
  chunkId: number;
  imageUrl: string;
  height?: number;
  /** 正文插图用块级布局，引用卡片用行内布局 */
  block?: boolean;
  style?: CSSProperties;
}

/** 引用图片：鉴权拉取 + 缩略图 + 点击放大预览 */
export default function CitationImage({ chunkId, imageUrl, height = 160, block, style }: Props) {
  const [src, setSrc] = useState<string | null>(blobCache.get(chunkId) ?? null);
  const [failed, setFailed] = useState(false);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    let alive = true;
    fetchImage(chunkId, imageUrl)
      .then((url) => {
        if (alive) setSrc(url);
      })
      .catch(() => {
        if (alive) setFailed(true);
      });
    return () => {
      alive = false;
    };
  }, [chunkId, imageUrl]);

  if (failed) {
    return (
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        （图片加载失败）
      </Typography.Text>
    );
  }
  if (!src) {
    return (
      <span style={{ display: block ? 'block' : 'inline-block', margin: '6px 0' }}>
        <Spin size="small" />
      </span>
    );
  }
  return (
    <span style={{ display: block ? 'block' : 'inline-block', margin: block ? '8px 0' : '6px 0', ...style }}>
      <img
        src={src}
        alt="引用图片"
        onClick={() => setOpen(true)}
        style={{
          display: 'block',
          height,
          maxWidth: '100%',
          objectFit: 'contain',
          borderRadius: 6,
          border: '1px solid #eef2f7',
          background: '#fff',
          cursor: 'zoom-in',
        }}
      />
      <Modal open={open} onCancel={() => setOpen(false)} footer={null} width={900} centered title="引用图片">
        <img src={src} alt="引用图片" style={{ display: 'block', width: '100%' }} />
      </Modal>
    </span>
  );
}
