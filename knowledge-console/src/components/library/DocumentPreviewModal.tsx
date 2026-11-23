import { useEffect, useRef, useState } from 'react';
import { Drawer, Modal, Select, Space, Spin, Typography } from 'antd';
import { libraryApi } from '../../api/library';
import type { LibraryFile } from '../../api/types';
import MarkdownViewer from '../MarkdownViewer';
import { previewKind } from './media';

interface DocumentPreviewModalProps {
  file: LibraryFile;
  token: string | null;
  onClose: () => void;
}

/** 文本/Markdown/图片只读预览（Drawer）与音频播放（Modal）。 */
export default function DocumentPreviewModal({ file, token, onClose }: DocumentPreviewModalProps) {
  const kind = previewKind(file);
  const [content, setContent] = useState('');
  const [loadingContent, setLoadingContent] = useState(false);
  const [imageUrl, setImageUrl] = useState('');
  const [speed, setSpeed] = useState(1);
  const mediaRef = useRef<HTMLMediaElement | null>(null);

  useEffect(() => {
    if (kind !== 'image' && kind !== 'markdown' && kind !== 'text') return;
    let cancelled = false;
    let url = '';
    setContent('');
    setImageUrl('');
    setLoadingContent(true);
    const task = kind === 'image'
      ? libraryApi.download(file.id).then((blob) => {
          if (!cancelled) {
            url = URL.createObjectURL(blob);
            setImageUrl(url);
          }
        })
      : libraryApi.content(file.id).then((c) => {
          if (!cancelled) setContent(c);
        });
    task.finally(() => {
      if (!cancelled) setLoadingContent(false);
    });
    return () => {
      cancelled = true;
      if (url) URL.revokeObjectURL(url);
    };
  }, [file.id, kind]);

  useEffect(() => {
    if (mediaRef.current) mediaRef.current.playbackRate = speed;
  }, [speed, file.id]);

  return (
    <>
      <Drawer
        title={file.fileName}
        width={['image', 'markdown'].includes(kind ?? '') ? 820 : 640}
        open={['text', 'markdown', 'image'].includes(kind ?? '')}
        onClose={onClose}
        destroyOnHidden
      >
        {loadingContent ? (
          <div style={{ textAlign: 'center', padding: 40 }}>
            <Spin />
          </div>
        ) : kind === 'image' ? (
          imageUrl ? (
            <img
              src={imageUrl}
              alt={file.fileName}
              style={{ maxWidth: '100%', borderRadius: 6, display: 'block', margin: '0 auto' }}
            />
          ) : null
        ) : kind === 'markdown' ? (
          <MarkdownViewer content={content} compact />
        ) : (
          <Typography.Paragraph>
            <pre
              style={{
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-all',
                fontSize: 13,
                background: '#fafafa',
                padding: 12,
                borderRadius: 6,
                maxHeight: 'calc(100vh - 200px)',
                overflow: 'auto',
              }}
            >
              {content}
            </pre>
          </Typography.Paragraph>
        )}
      </Drawer>

      <Modal
        title={file.fileName}
        open={kind === 'audio'}
        onCancel={onClose}
        footer={null}
        centered
        destroyOnHidden
        width={480}
      >
        <div style={{ padding: '12px 0' }}>
          <audio
            ref={(el) => { mediaRef.current = el; }}
            controls
            autoPlay
            preload="metadata"
            src={`/api/v1/library/files/${file.id}/stream?token=${encodeURIComponent(token ?? '')}`}
            style={{ width: '100%' }}
          />
          <Space style={{ marginTop: 12 }}>
            <span>倍速</span>
            <Select
              size="small"
              value={speed}
              style={{ width: 90 }}
              options={[0.5, 0.75, 1, 1.25, 1.5, 2].map((v) => ({ value: v, label: `${v}x` }))}
              onChange={setSpeed}
            />
          </Space>
        </div>
      </Modal>
    </>
  );
}
