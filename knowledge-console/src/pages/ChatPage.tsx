import { useEffect, useRef, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Empty,
  Input,
  Select,
  Space,
  Spin,
  Tag,
  Typography,
} from 'antd';
import {
  SendOutlined,
  StopOutlined,
  ReloadOutlined,
  PaperClipOutlined,
  PlusOutlined,
} from '@ant-design/icons';
import ReactMarkdown from 'react-markdown';
import type { ReactNode } from 'react';
import { kbApi } from '../api/knowledgeBases';
import { promptApi } from '../api/prompts';
import { streamChat } from '../api/chat';
import { chatSessionApi } from '../api/chatSessions';
import { useAuthStore } from '../store/auth';
import type {
  Citation,
  ChatSession,
  ChatSessionDetail,
  KnowledgeBase,
  PromptTemplate,
} from '../api/types';

interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  citations: Citation[];
  status: 'streaming' | 'done' | 'error';
  searching: string | null;
  errorMsg?: string;
  usage?: { promptTokens: number; completionTokens: number };
  /** assistant 消息保留原始问题与 KB，用于重新生成 */
  question?: string;
  kbId?: number;
  promptId?: number;
}

const SEARCH_HINT: Record<string, string> = {
  kb: '正在检索知识库…',
  weather: '正在查询实时天气…',
  tavily: '正在联网搜索最新资讯…',
};

/** 将 Markdown 文本中的 [n] 角标渲染为可点击引用按钮 */
function renderWithCitations(
  text: string,
  msgId: string,
  onCite: (msgId: string, n: number) => void,
): ReactNode {
  const parts = text.split(/\[(\d+)\]/g);
  return parts.map((part, i) => {
    if (i % 2 === 1) {
      const n = Number(part);
      return (
        <Button
          key={i}
          type="text"
          size="small"
          className="cite-badge"
          onClick={() => onCite(msgId, n)}
          style={{ padding: '0 4px', height: 20, fontSize: 12, verticalAlign: 'baseline' }}
        >
          [{n}]
        </Button>
      );
    }
    return part;
  });
}

/** 遍历 Markdown 节点，替换文本中的角标 */
function renderNodes(
  nodes: ReactNode,
  msgId: string,
  onCite: (msgId: string, n: number) => void,
): ReactNode {
  if (typeof nodes === 'string') {
    return renderWithCitations(nodes, msgId, onCite);
  }
  if (Array.isArray(nodes)) {
    return nodes.map((c, i) =>
      typeof c === 'string' ? <span key={i}>{renderWithCitations(c, msgId, onCite)}</span> : c,
    );
  }
  return nodes;
}

/** 会话详情消息映射为页面消息（每行 chat_message 在后端已拆成 user/assistant 两条）。 */
function mapHistory(detail: ChatSessionDetail): ChatMessage[] {
  const out: ChatMessage[] = [];
  let lastQuestion: string | null = null;
  for (const m of detail.messages) {
    if (m.role === 'user') {
      lastQuestion = m.content;
      out.push({
        id: `h-${m.dbId}-user`,
        role: 'user',
        content: m.content,
        citations: [],
        status: 'done',
        searching: null,
        kbId: detail.session.kbId,
      });
    } else {
      out.push({
        id: `h-${m.dbId}-assistant`,
        role: 'assistant',
        content: m.content,
        citations: m.citations ?? [],
        status: 'done',
        searching: null,
        question: lastQuestion ?? undefined,
        kbId: detail.session.kbId,
      });
    }
  }
  return out;
}

export default function ChatPage() {
  const { token, user } = useAuthStore();
  const [kbs, setKbs] = useState<KnowledgeBase[]>([]);
  const [kbId, setKbId] = useState<number | null>(null);
  const [prompts, setPrompts] = useState<PromptTemplate[]>([]);
  const [promptId, setPromptId] = useState<number | null>(null);
  const [input, setInput] = useState('');
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [sessions, setSessions] = useState<ChatSession[]>([]);
  const [currentSessionId, setCurrentSessionId] = useState<number | null>(null);
  const [running, setRunning] = useState(false);
  const abortRef = useRef<AbortController | null>(null);
  const timerRef = useRef<number | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    kbApi.list().then((list) => {
      setKbs(list);
      if (list.length > 0) setKbId(list[0].id);
    });
    chatSessionApi.list().then(setSessions).catch(() => {
      /* 会话列表加载失败不阻塞问答 */
    });
  }, []);

  // 提示词列表随知识库切换：仅显示该知识库下当前用户被授权的模板。
  useEffect(() => {
    if (kbId == null) {
      setPrompts([]);
      setPromptId(null);
      return;
    }
    let cancelled = false;
    promptApi
      .list(kbId)
      .then((list) => {
        if (cancelled) return;
        setPrompts(list);
        const def = list.find((p) => p.isDefault) ?? list[0];
        setPromptId(def ? def.id : null);
      })
      .catch(() => {
        if (!cancelled) {
          setPrompts([]);
          setPromptId(null);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [kbId]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [messages]);

  const patchMessage = (id: string, patch: Partial<ChatMessage>) => {
    setMessages((prev) => prev.map((m) => (m.id === id ? { ...m, ...patch } : m)));
  };

  const clearTimer = () => {
    if (timerRef.current) {
      window.clearTimeout(timerRef.current);
      timerRef.current = null;
    }
  };

  const loadSessions = () =>
    chatSessionApi
      .list()
      .then(setSessions)
      .catch(() => {
        /* 静默 */
      });

  /** 新对话：清空当前消息与会话归属（首轮发问时后端自动建会话）。 */
  const handleNewChat = () => {
    if (running) return;
    setCurrentSessionId(null);
    setMessages([]);
  };

  /** 打开历史会话：回看全部消息，并把知识库选择器恢复到该会话知识库。 */
  const openSession = async (id: number) => {
    if (running) return;
    const detail = await chatSessionApi.get(id);
    setCurrentSessionId(id);
    setKbId(detail.session.kbId);
    setMessages(mapHistory(detail));
  };

  /** 发起（或重新生成）一次问答。targetId 存在时替换该 assistant 消息。 */
  const ask = async (question: string, targetKbId: number, targetId?: string, targetPromptId?: number) => {
    if (!token || !user) return;
    const usePromptId = targetPromptId ?? promptId ?? undefined;
    setRunning(true);

    let msgId = targetId;
    if (!msgId) {
      const userMsg: ChatMessage = {
        id: `u-${Date.now()}`,
        role: 'user',
        content: question,
        citations: [],
        status: 'done',
        searching: null,
        kbId: targetKbId,
      };
      msgId = `a-${Date.now()}`;
      const assistantMsg: ChatMessage = {
        id: msgId,
        role: 'assistant',
        content: '',
        citations: [],
        status: 'streaming',
        searching: SEARCH_HINT.kb,
        question,
        kbId: targetKbId,
        promptId: usePromptId,
      };
      setMessages((prev) => [...prev, userMsg, assistantMsg]);
    } else {
      // 重新生成：清空旧答案重新请求
      patchMessage(msgId, {
        content: '',
        citations: [],
        status: 'streaming',
        errorMsg: undefined,
        usage: undefined,
        searching: SEARCH_HINT.kb,
      });
    }

    const currentMsgId = msgId;
    const controller = new AbortController();
    abortRef.current = controller;
    // 与后端 rag.chat.sse-timeout-seconds=60 对齐
    timerRef.current = window.setTimeout(() => {
      controller.abort();
      patchMessage(currentMsgId, {
        status: 'error',
        searching: null,
        errorMsg: '响应超时（60s），可点击「重新生成」重试',
      });
      setRunning(false);
    }, 60000);

    try {
      await streamChat(
        {
          kbId: targetKbId,
          question,
          promptId: usePromptId,
          sessionId: currentSessionId ?? undefined,
        },
        token,
        user.tenantId,
        {
          onSession: (d) => {
            setCurrentSessionId(d.sessionId);
            loadSessions();
          },
          onSearchStart: (d) => {
            const hint = d.stage === 'tool' ? SEARCH_HINT[d.tool ?? 'tavily'] : SEARCH_HINT.kb;
            patchMessage(currentMsgId, { searching: hint });
          },
          onSearchResult: (d) => {
            patchMessage(currentMsgId, { citations: d.citations, searching: '正在生成回答…' });
          },
          onMessage: (d) => {
            setMessages((prev) =>
              prev.map((m) =>
                m.id === currentMsgId
                  ? { ...m, content: m.content + d.delta, searching: null }
                  : m,
              ),
            );
          },
          onError: (d) => {
            patchMessage(currentMsgId, {
              status: 'error',
              searching: null,
              errorMsg: d.message,
            });
          },
          onDone: (d) => {
            patchMessage(currentMsgId, { status: 'done', searching: null, usage: d.usage });
            loadSessions();
          },
        },
        controller.signal,
      );
    } catch (e) {
      if ((e as Error).name === 'AbortError') {
        patchMessage(currentMsgId, {
          status: 'error',
          searching: null,
          errorMsg: '已手动停止，已保留当前内容',
        });
      } else if ((e as Error).message !== '__done__') {
        patchMessage(currentMsgId, {
          status: 'error',
          searching: null,
          errorMsg: (e as Error).message || '生成失败',
        });
      }
    } finally {
      clearTimer();
      abortRef.current = null;
      setRunning(false);
    }
  };

  const handleSend = () => {
    const q = input.trim();
    if (!q || kbId == null || running) return;
    setInput('');
    ask(q, kbId);
  };

  const handleStop = () => {
    abortRef.current?.abort();
  };

  const handleRegenerate = (m: ChatMessage) => {
    if (running || !m.question || m.kbId == null) return;
    ask(m.question, m.kbId, m.id, m.promptId);
  };

  const jumpToCitation = (msgId: string, n: number) => {
    document
      .getElementById(`cite-${msgId}-${n}`)
      ?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  };

  return (
    <Card
      styles={{
        body: { padding: 0, height: 'calc(100vh - 130px)' },
      }}
    >
      <div style={{ display: 'flex', height: '100%' }}>
        {/* 会话栏 */}
        <div
          style={{
            width: 232,
            flexShrink: 0,
            borderRight: '1px solid #f0f0f0',
            display: 'flex',
            flexDirection: 'column',
            background: '#fafbfc',
          }}
        >
          <div style={{ padding: 12 }}>
            <Button
              block
              type="primary"
              icon={<PlusOutlined />}
              onClick={handleNewChat}
              disabled={running}
            >
              新对话
            </Button>
          </div>
          <div style={{ flex: 1, overflow: 'auto', padding: '0 8px 8px' }}>
            {sessions.length === 0 ? (
              <Typography.Text
                type="secondary"
                style={{ display: 'block', textAlign: 'center', marginTop: 12, fontSize: 12 }}
              >
                暂无历史会话
              </Typography.Text>
            ) : (
              sessions.map((s) => (
                <div
                  key={s.id}
                  title={running ? '生成中，暂不能切换' : s.title}
                  onClick={() => openSession(s.id)}
                  style={{
                    padding: '8px 10px',
                    borderRadius: 8,
                    marginBottom: 4,
                    cursor: running ? 'not-allowed' : 'pointer',
                    background: s.id === currentSessionId ? '#e6f4ff' : 'transparent',
                  }}
                >
                  <div
                    style={{
                      fontSize: 13,
                      whiteSpace: 'nowrap',
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      color: s.id === currentSessionId ? '#1677ff' : 'inherit',
                    }}
                  >
                    {s.title}
                  </div>
                </div>
              ))
            )}
          </div>
        </div>

        {/* 右侧问答区 */}
        <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
          {/* KB / 提示词选择栏 */}
      <div style={{ padding: '12px 20px', borderBottom: '1px solid #f0f0f0' }}>
        <Space size={32}>
          <Space>
            <Typography.Text strong>知识库：</Typography.Text>
            <Select
              style={{ width: 320 }}
              placeholder="请选择知识库"
              value={kbId}
              onChange={setKbId}
              options={kbs.map((k) => ({ value: k.id, label: k.name }))}
              showSearch
              optionFilterProp="label"
            />
          </Space>
          <Space>
            <Typography.Text strong>提示词：</Typography.Text>
            <Select
              style={{ width: 240 }}
              placeholder="默认模板"
              value={promptId}
              onChange={setPromptId}
              options={prompts.map((p) => ({
                value: p.id,
                label: p.name,
              }))}
              showSearch
              optionFilterProp="label"
            />
          </Space>
        </Space>
      </div>

      {/* 消息区 */}
      <div style={{ flex: 1, overflow: 'auto', padding: 20, background: '#f7faff' }}>
        {messages.length === 0 ? (
          <Empty
            style={{ marginTop: 120 }}
            description={
              <Typography.Text type="secondary">
                选择知识库后开始提问。回答中的 [n] 角标可点击查看引用来源。
              </Typography.Text>
            }
          />
        ) : (
          messages.map((m) => (
            <div
              key={m.id}
              style={{
                display: 'flex',
                justifyContent: m.role === 'user' ? 'flex-end' : 'flex-start',
                marginBottom: 16,
              }}
            >
              <div style={{ maxWidth: '85%' }}>
                <div
                  style={{
                    padding: '12px 16px',
                    borderRadius: 12,
                    background: m.role === 'user' ? '#1677ff' : '#fff',
                    color: m.role === 'user' ? '#fff' : 'inherit',
                    boxShadow: '0 2px 8px rgba(0,0,0,0.05)',
                    border: m.role === 'assistant' ? '1px solid #eef2f7' : undefined,
                  }}
                >
                  {m.role === 'user' ? (
                    m.content
                  ) : (
                    <div className="markdown-body" style={{ fontSize: 14, lineHeight: 1.8 }}>
                      {m.content ? (
                        <ReactMarkdown
                          components={{
                            p: ({ children }) => (
                              <p style={{ marginBottom: 8 }}>
                                {renderNodes(children, m.id, jumpToCitation)}
                              </p>
                            ),
                            li: ({ children }) => (
                              <li>{renderNodes(children, m.id, jumpToCitation)}</li>
                            ),
                            pre: ({ children }) => (
                              <pre
                                style={{
                                  background: '#f5f5f5',
                                  padding: 10,
                                  borderRadius: 6,
                                  overflowX: 'auto',
                                }}
                              >
                                {children}
                              </pre>
                            ),
                          }}
                        >
                          {m.content}
                        </ReactMarkdown>
                      ) : m.status === 'streaming' ? null : (
                        <Typography.Text type="secondary">（无回答内容）</Typography.Text>
                      )}
                      {m.status === 'streaming' && !m.searching && (
                        <span style={{ color: '#1677ff' }}>▍</span>
                      )}
                    </div>
                  )}
                </div>

                {/* 检索中指示 */}
                {m.role === 'assistant' && m.searching && (
                  <Space style={{ marginTop: 8, color: '#1677ff' }} size={8}>
                    <Spin size="small" />
                    <Typography.Text type="secondary">{m.searching}</Typography.Text>
                  </Space>
                )}

                {/* 引用来源（search_result 提前渲染，spec 5.2） */}
                {m.role === 'assistant' && m.citations.length > 0 && (
                  <div style={{ marginTop: 10 }}>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      <PaperClipOutlined /> 引用来源（{m.citations.length}）
                    </Typography.Text>
                    <Space direction="vertical" size={6} style={{ width: '100%', marginTop: 6 }}>
                      {m.citations.map((c) => (
                        <Card
                          key={c.chunkId}
                          id={`cite-${m.id}-${c.seq}`}
                          size="small"
                          style={{ background: '#fbfdff', borderColor: '#e6f0ff', scrollMargin: 80 }}
                          styles={{ body: { padding: '8px 12px' } }}
                        >
                          <Space direction="vertical" size={2} style={{ width: '100%' }}>
                            <Space size={8} wrap>
                              <Tag color="blue">[{c.seq}]</Tag>
                              <Typography.Text strong style={{ fontSize: 13 }}>
                                {c.documentName}
                              </Typography.Text>
                              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                                第 {c.page + 1} 页
                              </Typography.Text>
                              {c.sectionPath && (
                                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                                  {c.sectionPath}
                                </Typography.Text>
                              )}
                              {c.contentType && c.contentType !== 'TEXT' && (
                                <Tag>{({ TABLE: '表格', CODE: '代码', IMAGE: '图片' } as Record<string, string>)[c.contentType] ?? c.contentType}</Tag>
                              )}
                              {c.previewUrl && (
                                <a
                                  href={c.previewUrl}
                                  target="_blank"
                                  rel="noreferrer"
                                  style={{ fontSize: 12 }}
                                >
                                  预览源文件
                                </a>
                              )}
                            </Space>
                            <Typography.Paragraph
                              type="secondary"
                              ellipsis={{ rows: 2, tooltip: c.content }}
                              style={{ marginBottom: 0, fontSize: 12 }}
                            >
                              {c.content}
                            </Typography.Paragraph>
                          </Space>
                        </Card>
                      ))}
                    </Space>
                  </div>
                )}

                {/* 错误 + 重新生成（spec 5.3：保留半成品，手动重新生成） */}
                {m.role === 'assistant' && m.status === 'error' && (
                  <div style={{ marginTop: 8 }}>
                    <Alert
                      type="error"
                      showIcon
                      message={m.errorMsg || '生成失败'}
                      action={
                        <Button
                          size="small"
                          icon={<ReloadOutlined />}
                          onClick={() => handleRegenerate(m)}
                        >
                          重新生成
                        </Button>
                      }
                    />
                  </div>
                )}

                {/* 正常结束后的用量与重新生成 */}
                {m.role === 'assistant' && m.status === 'done' && (
                  <Space style={{ marginTop: 8 }} size={16}>
                    <Button size="small" icon={<ReloadOutlined />} onClick={() => handleRegenerate(m)}>
                      重新生成
                    </Button>
                    {m.usage && (
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        Tokens：{m.usage.promptTokens + m.usage.completionTokens}
                        （提示 {m.usage.promptTokens} / 生成 {m.usage.completionTokens}）
                      </Typography.Text>
                    )}
                  </Space>
                )}
              </div>
            </div>
          ))
        )}
        <div ref={bottomRef} />
      </div>

      {/* 输入区 */}
      <div style={{ padding: 16, borderTop: '1px solid #f0f0f0', background: '#fff' }}>
        <Space.Compact style={{ width: '100%' }}>
          <Input.TextArea
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder={kbId == null ? '请先选择知识库' : '输入问题，Enter 发送，Shift+Enter 换行'}
            disabled={kbId == null}
            autoSize={{ minRows: 1, maxRows: 4 }}
            onPressEnter={(e) => {
              if (!e.shiftKey) {
                e.preventDefault();
                handleSend();
              }
            }}
          />
          {running ? (
            <Button danger icon={<StopOutlined />} onClick={handleStop} style={{ height: 'auto' }}>
              停止
            </Button>
          ) : (
            <Button
              type="primary"
              icon={<SendOutlined />}
              onClick={handleSend}
              disabled={kbId == null || !input.trim()}
              style={{ height: 'auto' }}
            >
              发送
            </Button>
          )}
        </Space.Compact>
      </div>
        </div>
      </div>
    </Card>
  );
}
