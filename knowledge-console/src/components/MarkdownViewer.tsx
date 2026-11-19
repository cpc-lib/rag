import { Segmented, Typography } from 'antd';
import { useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import Mermaid from './Mermaid';

type ViewMode = 'preview' | 'source';

interface MarkdownViewerProps {
  content: string;
  /** 紧凑模式（用于抽屉等窄容器，不额外加外层卡片阴影和居中留白） */
  compact?: boolean;
  /** 是否显示「渲染预览 / 源码」切换栏，默认开启 */
  showToggle?: boolean;
}

/**
 * 通用 Markdown 在线阅览组件：
 * 支持 GFM（表格、任务列表、删除线）、Mermaid 图表渲染，以及「渲染预览 / 源码」切换。
 */
export default function MarkdownViewer({
  content,
  compact = false,
  showToggle = true,
}: MarkdownViewerProps) {
  const [mode, setMode] = useState<ViewMode>('preview');

  const body =
    mode === 'source' ? (
      <pre
        style={{
          margin: 0,
          padding: compact ? 12 : 20,
          fontFamily: 'Consolas, Menlo, monospace',
          fontSize: 13,
          lineHeight: 1.7,
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-word',
          background: '#fafafa',
          borderRadius: 6,
          border: '1px solid #f0f0f0',
        }}
      >
        {content}
      </pre>
    ) : !content.trim() ? (
      <Typography.Text type="secondary">（文件内容为空）</Typography.Text>
    ) : (
      <div
        className="markdown-body"
        style={{
          fontSize: 14,
          lineHeight: 1.8,
          color: '#1f2937',
          wordBreak: 'break-word',
        }}
      >
        <ReactMarkdown
          remarkPlugins={[remarkGfm]}
          components={{
            h1: ({ children }) => (
              <h1
                style={{
                  fontSize: 24,
                  fontWeight: 700,
                  margin: '8px 0 16px',
                  paddingBottom: 8,
                  borderBottom: '1px solid #e5e7eb',
                }}
              >
                {children}
              </h1>
            ),
            h2: ({ children }) => (
              <h2
                style={{
                  fontSize: 20,
                  fontWeight: 600,
                  margin: '24px 0 12px',
                  paddingBottom: 6,
                  borderBottom: '1px solid #f0f0f0',
                }}
              >
                {children}
              </h2>
            ),
            h3: ({ children }) => (
              <h3 style={{ fontSize: 16, fontWeight: 600, margin: '18px 0 8px' }}>{children}</h3>
            ),
            h4: ({ children }) => (
              <h4 style={{ fontSize: 14, fontWeight: 600, margin: '14px 0 6px' }}>{children}</h4>
            ),
            p: ({ children }) => <p style={{ margin: '0 0 10px' }}>{children}</p>,
            ul: ({ children }) => (
              <ul style={{ margin: '0 0 10px', paddingLeft: 22 }}>{children}</ul>
            ),
            ol: ({ children }) => (
              <ol style={{ margin: '0 0 10px', paddingLeft: 22 }}>{children}</ol>
            ),
            li: ({ children }) => <li style={{ marginBottom: 4 }}>{children}</li>,
            blockquote: ({ children }) => (
              <blockquote
                style={{
                  margin: '10px 0',
                  padding: '6px 14px',
                  borderLeft: '4px solid #1677ff',
                  background: '#f8fafc',
                  color: '#475569',
                  borderRadius: '0 6px 6px 0',
                }}
              >
                {children}
              </blockquote>
            ),
            hr: () => (
              <hr style={{ border: 0, borderTop: '1px solid #e5e7eb', margin: '18px 0' }} />
            ),
            a: ({ href, children }) => (
              <a
                href={href}
                target="_blank"
                rel="noopener noreferrer"
                style={{ color: '#1677ff', textDecoration: 'none' }}
              >
                {children}
              </a>
            ),
            img: ({ src, alt }) => (
              <img
                src={src}
                alt={alt ?? ''}
                style={{ maxWidth: '100%', borderRadius: 6, margin: '8px 0' }}
              />
            ),
            table: ({ children }) => (
              <div style={{ overflowX: 'auto', margin: '12px 0' }}>
                <table
                  style={{
                    borderCollapse: 'collapse',
                    width: '100%',
                    fontSize: 13,
                  }}
                >
                  {children}
                </table>
              </div>
            ),
            th: ({ children }) => (
              <th
                style={{
                  border: '1px solid #e5e7eb',
                  padding: '8px 12px',
                  background: '#fafafa',
                  fontWeight: 600,
                  textAlign: 'left',
                }}
              >
                {children}
              </th>
            ),
            td: ({ children }) => (
              <td style={{ border: '1px solid #e5e7eb', padding: '7px 12px' }}>{children}</td>
            ),
            code: ({ className, children }) => {
              const lang = /language-(\w+)/.exec(className ?? '')?.[1];
              if (lang === 'mermaid') {
                return <Mermaid code={String(children).replace(/\n$/, '')} />;
              }
              const isBlock = Boolean(className);
              return (
                <code
                  className={className}
                  style={
                    isBlock
                      ? {
                          fontFamily: 'Consolas, Menlo, monospace',
                          fontSize: 13,
                        }
                      : {
                          fontFamily: 'Consolas, Menlo, monospace',
                          fontSize: '0.9em',
                          background: '#f3f4f6',
                          padding: '2px 5px',
                          borderRadius: 4,
                        }
                  }
                >
                  {children}
                </code>
              );
            },
            pre: ({ children }) => {
              const first = (Array.isArray(children) ? children[0] : children) as
                | { props?: { className?: string } }
                | undefined;
              // mermaid 代码块由 Mermaid 组件独立渲染，不套 pre
              if (first?.props?.className?.includes('language-mermaid')) {
                return <>{children}</>;
              }
              return (
                <pre
                  style={{
                    background: '#f6f8fa',
                    border: '1px solid #e5e7eb',
                    padding: 12,
                    borderRadius: 6,
                    overflowX: 'auto',
                    margin: '10px 0',
                  }}
                >
                  {children}
                </pre>
              );
            },
          }}
        >
          {content}
        </ReactMarkdown>
      </div>
    );

  if (compact) {
    return (
      <div>
        {showToggle && (
          <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 12 }}>
            <Segmented
              size="small"
              value={mode}
              onChange={(v) => setMode(v as ViewMode)}
              options={[
                { label: '渲染预览', value: 'preview' },
                { label: '源码', value: 'source' },
              ]}
            />
          </div>
        )}
        {body}
      </div>
    );
  }

  return (
    <div style={{ padding: '20px 16px' }}>
      <div
        style={{
          maxWidth: 940,
          margin: '0 auto',
          background: '#fff',
          borderRadius: 8,
          border: '1px solid #e5e7eb',
          padding: '20px 28px 28px',
          boxShadow: '0 1px 3px rgba(0, 0, 0, 0.04)',
        }}
      >
        {showToggle && (
          <div
            style={{
              display: 'flex',
              justifyContent: 'flex-end',
              marginBottom: 14,
              paddingBottom: 10,
              borderBottom: '1px solid #f0f0f0',
            }}
          >
            <Segmented
              size="small"
              value={mode}
              onChange={(v) => setMode(v as ViewMode)}
              options={[
                { label: '渲染预览', value: 'preview' },
                { label: '源码', value: 'source' },
              ]}
            />
          </div>
        )}
        {body}
      </div>
    </div>
  );
}
