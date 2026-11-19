import { Button, Empty, Select, Space, Spin, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { documentApi } from '../api/documents';
import MarkdownViewer from '../components/MarkdownViewer';

type Kind = 'pdf' | 'image' | 'video' | 'audio' | 'markdown' | 'text' | 'docx' | 'doc' | 'xlsx' | 'other';

const IMAGE_EXT = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp']);
const VIDEO_EXT = new Set(['mp4', 'webm', 'ogv', 'mov']);
const AUDIO_EXT = new Set(['mp3', 'wav', 'ogg', 'flac', 'm4a', 'aac']);
const MARKDOWN_EXT = new Set(['md', 'markdown']);
const TEXT_EXT = new Set([
  'txt', 'csv', 'json', 'log', 'xml', 'yml', 'yaml', 'html', 'htm',
  'sql', 'sh', 'bat', 'ini', 'conf', 'properties',
  'java', 'py', 'js', 'jsx', 'ts', 'tsx', 'go', 'rs', 'c', 'cpp', 'h', 'css',
]);

function extOf(name: string): string {
  const i = name.lastIndexOf('.');
  return i >= 0 ? name.slice(i + 1).toLowerCase() : '';
}

/** docx-preview 解析后的模型节点（只声明排版修复用到的字段） */
type DocxNode = {
  type?: string;
  children?: DocxNode[];
  columns?: { width?: string }[];
  gridBefore?: number;
  gridAfter?: number;
  span?: number;
  verticalMerge?: string;
  cssStyle?: Record<string, string>;
};

/** 表级边框（w:tblBorders），docx-preview 只保留四个外边，insideH/insideV 会丢失 */
type TableBorders = {
  top?: string;
  bottom?: string;
  left?: string;
  right?: string;
  insideH?: string;
  insideV?: string;
};

const WORD_NS = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main';

function wAttr(el: Element, name: string): string | null {
  return el.getAttributeNS(WORD_NS, name) ?? el.getAttribute(`w:${name}`);
}

/** w:top/w:insideH 等边框元素 → CSS border 值（sz 为 1/8 磅） */
function cssBorder(el: Element): string | undefined {
  const val = wAttr(el, 'val') ?? '';
  if (!val) {
    return undefined;
  }
  if (val === 'none' || val === 'nil') {
    return 'none';
  }
  const size = Number(wAttr(el, 'sz') ?? 0);
  const color = wAttr(el, 'color') ?? 'auto';
  const type =
    val === 'double' ? 'double' : val === 'dotted' ? 'dotted' : val.startsWith('dash') ? 'dashed' : 'solid';
  const width = Math.min(12, Math.max(0.25, size * 0.125));
  return `${width.toFixed(2)}pt ${type} ${color === 'auto' ? '#000000' : `#${color}`}`;
}

/** 按文档顺序读取每张表的 w:tblBorders（含内框），供渲染前补齐单元格边框 */
async function readTableBorders(blob: Blob): Promise<TableBorders[]> {
  const { default: JSZip } = await import('jszip');
  const zip = await JSZip.loadAsync(blob);
  const xml = await zip.file('word/document.xml')?.async('string');
  if (!xml) {
    return [];
  }
  const dom = new DOMParser().parseFromString(xml, 'application/xml');
  return Array.from(dom.getElementsByTagNameNS(WORD_NS, 'tbl')).map((table) => {
    const result: TableBorders = {};
    const tblPr = Array.from(table.children).find((c) => c.localName === 'tblPr');
    const borders = tblPr && Array.from(tblPr.children).find((c) => c.localName === 'tblBorders');
    for (const side of Array.from(borders?.children ?? [])) {
      const value = cssBorder(side);
      switch (side.localName) {
        case 'top':
          result.top = value;
          break;
        case 'bottom':
          result.bottom = value;
          break;
        case 'left':
        case 'start':
          result.left = value;
          break;
        case 'right':
        case 'end':
          result.right = value;
          break;
        case 'insideH':
          result.insideH = value;
          break;
        case 'insideV':
          result.insideV = value;
          break;
      }
    }
    return result;
  });
}

/**
 * 渲染前修正 docx-preview 的表格模型：
 * 1. gridBefore/gridAfter 只生成占位 td、不推进列游标，导致后续单元格纵向合并键错位；
 * 2. Word 常用 gridBefore 表达"该行首列被上方纵向合并覆盖"，库不会延长上方合并单元格的 rowSpan；
 * 3. 续接单元格缺少 gridSpan 时列游标少算，后续单元格整体左移；
 * 4. tblGrid 的列宽可能与单元格 tcW 不一致（脚本生成的文档常把 gridCol 写成等宽占位值），
 *    库只按 tblGrid 出列宽，导致各列宽窄错乱；
 * 5. 库只把 tblBorders 的外框画在 table 上，insideH/insideV 被丢弃，单元格无边框 → 只剩外框。
 * 做法：把 gridBefore/gridAfter 展开成显式单元格（合并区域补续接单元格），补齐续接单元格跨度，
 * 用单元格 tcW 覆盖列宽，最后按表级边框给每个单元格补齐四边。
 */
function normalizeTables(elements: DocxNode[], borders: TableBorders[], cursor: { index: number }) {
  for (const el of elements) {
    if (el.type !== 'table') {
      continue;
    }
    const border = borders[cursor.index++] ?? {};
    // 网格列 -> 该列所属纵向合并区域的跨度
    const active = new Map<number, number>();
    for (const row of (el.children ?? []).filter((c) => c.type === 'row')) {
      const cells: DocxNode[] = [];
      let col = 0;
      // 展开被省略的网格列：有纵向合并则补续接单元格，否则补空占位单元格
      const fill = (count: number) => {
        for (let filled = 0; filled < count; ) {
          const span = active.get(col) ?? 1;
          cells.push(
            active.has(col)
              ? { type: 'cell', children: [], verticalMerge: 'continue', span }
              : { type: 'cell', children: [], span: 1, cssStyle: { border: 'none' } },
          );
          col += span;
          filled += span;
        }
      };
      fill(row.gridBefore ?? 0);
      for (const cell of row.children ?? []) {
        if (cell.type !== 'cell') {
          cells.push(cell);
          continue;
        }
        let span = cell.span ?? 1;
        if (cell.verticalMerge === 'continue' && !cell.span) {
          const inherit = active.get(col);
          if (inherit) {
            span = inherit;
            cell.span = inherit;
          }
        }
        cells.push(cell);
        if (cell.verticalMerge === 'restart') {
          for (let i = 0; i < span; i += 1) {
            active.set(col + i, span);
          }
        } else if (cell.verticalMerge !== 'continue') {
          for (let i = 0; i < span; i += 1) {
            active.delete(col + i);
          }
        }
        col += span;
        normalizeTables(cell.children ?? [], borders, cursor);
      }
      fill(row.gridAfter ?? 0);
      row.children = cells;
      row.gridBefore = 0;
      row.gridAfter = 0;
    }
    // 用不跨列的单元格 tcW 推导列宽（同样宽度的列只在首次出现时取值）
    if (el.columns?.length) {
      const widths: (string | undefined)[] = [];
      for (const row of (el.children ?? []).filter((c) => c.type === 'row')) {
        let col = 0;
        for (const cell of row.children ?? []) {
          if (cell.type !== 'cell') {
            continue;
          }
          const span = cell.span ?? 1;
          const width = cell.cssStyle?.width;
          if (span === 1 && width && /^[\d.]+pt$/.test(width) && !widths[col]) {
            widths[col] = width;
          }
          col += span;
        }
      }
      el.columns.forEach((column, i) => {
        if (widths[i]) {
          column.width = widths[i];
        }
      });
    }
    applyTableBorders(el, border);
  }
}

/** 把表级边框落到单元格四边：外圈用表外框，内部用 insideH/insideV；已有 tcBorders 的边不覆盖 */
function applyTableBorders(el: DocxNode, border: TableBorders) {
  if (!border.top && !border.bottom && !border.left && !border.right && !border.insideH && !border.insideV) {
    return;
  }
  const rows = (el.children ?? []).filter((c) => c.type === 'row');
  const total =
    el.columns?.length ??
    rows.reduce(
      (max, row) => Math.max(max, (row.children ?? []).reduce((sum, c) => sum + (c.span ?? 1), 0)),
      0,
    );
  rows.forEach((row, r) => {
    let col = 0;
    for (const cell of row.children ?? []) {
      if (cell.type !== 'cell') {
        continue;
      }
      const span = cell.span ?? 1;
      // 合并占位格不是真实单元格，不画边框
      if (cell.cssStyle?.border === 'none') {
        col += span;
        continue;
      }
      const style = cell.cssStyle ?? (cell.cssStyle = {});
      const set = (key: string, value?: string) => {
        if (value && !style[key]) {
          style[key] = value;
        }
      };
      set('border-top', r === 0 ? border.top : border.insideH);
      set('border-bottom', r === rows.length - 1 ? border.bottom : border.insideH);
      set('border-left', col === 0 ? border.left : border.insideV);
      set('border-right', col + span >= total ? border.right : border.insideV);
      col += span;
    }
  });
}

/**
 * docx 渲染：parse → 修正表格模型（合并/列宽/边框）→ render。
 * 关闭字体与页眉页脚解析、关闭分页以提速；30s 未完成视为异常文档，避免一直停在加载态。
 */
async function renderDocxInto(blob: Blob, container: HTMLDivElement) {
  const docx = await import('docx-preview');
  const options = {
    inWrapper: true,
    ignoreWidth: false,
    ignoreFonts: true,
    breakPages: false,
    renderHeaders: false,
    renderFooters: false,
    useBase64URL: true,
  };
  const render = async () => {
    const doc = await docx.parseAsync(blob, options);
    const borders = await readTableBorders(blob);
    normalizeTables(doc.documentPart?.body?.children ?? [], borders, { index: 0 });
    const nodes = await docx.renderDocument(doc, options);
    container.innerHTML = '';
    nodes.forEach((node) => container.appendChild(node));
  };
  await Promise.race([
    render(),
    new Promise((_, reject) => {
      setTimeout(() => reject(new Error('docx render timeout')), 30000);
    }),
  ]);
}

function kindOf(ext: string): Kind {
  if (ext === 'pdf') return 'pdf';
  if (IMAGE_EXT.has(ext)) return 'image';
  if (VIDEO_EXT.has(ext)) return 'video';
  if (AUDIO_EXT.has(ext)) return 'audio';
  if (MARKDOWN_EXT.has(ext)) return 'markdown';
  if (TEXT_EXT.has(ext)) return 'text';
  // docx-preview 只支持 OOXML(.docx)；老的 .doc 由后端用 POI 转 HTML 后走 iframe 渲染
  if (ext === 'docx') return 'docx';
  if (ext === 'doc') return 'doc';
  if (ext === 'xlsx' || ext === 'xls' || ext === 'xlsm') return 'xlsx';
  return 'other';
}

/**
 * 原文件在线预览页（独立窗口，路由 /preview/:id）：按扩展名渲染，内容直接取后端返回的原始字节，
 * 不做文本提取或格式转换。PDF/图片/音视频用浏览器原生渲染，docx 用 docx-preview 还原排版。
 */
export default function FilePreviewPage() {
  const { id } = useParams();
  const [sp] = useSearchParams();
  const documentId = Number(id);
  const fileName = sp.get('name') || '文件预览';

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [url, setUrl] = useState<string | null>(null);
  const [text, setText] = useState<string | null>(null);
  const [sheets, setSheets] = useState<
    { name: string; rows: unknown[][]; merges: { s: { r: number; c: number }; e: { r: number; c: number } }[] }[]
  >([]);
  const [sheetIdx, setSheetIdx] = useState(0);
  /** 老版 .doc 由后端转成的 HTML */
  const [docHtml, setDocHtml] = useState<string | null>(null);
  const [hasFile, setHasFile] = useState(false);
  const blobRef = useRef<Blob | null>(null);
  const docxRef = useRef<HTMLDivElement>(null);

  const ext = extOf(fileName);
  const kind = kindOf(ext);

  useEffect(() => {
    document.title = fileName;
  }, [fileName]);

  useEffect(() => {
    if (!documentId) {
      setError('缺少文档参数');
      setLoading(false);
      return;
    }
    let cancelled = false;
    let objectUrl: string | null = null;
    setLoading(true);
    setError(null);
    setUrl(null);
    setText(null);
    setSheets([]);
    setSheetIdx(0);
    setDocHtml(null);
    documentApi
      .preview(documentId)
      .then(async (blob) => {
        if (cancelled) {
          return;
        }
        blobRef.current = blob;
        setHasFile(true);
        if (kind === 'docx') {
          const container = docxRef.current;
          if (cancelled) {
            return;
          }
          if (!container) {
            setError('预览容器未就绪，请刷新重试');
            setLoading(false);
            return;
          }
          container.innerHTML = '';
          try {
            await renderDocxInto(blob, container);
          } catch {
            if (!cancelled) {
              setError('文档渲染失败（文件较大或结构异常），请下载后查看');
              setLoading(false);
            }
            return;
          }
        } else if (kind === 'text' || kind === 'markdown') {
          setText(await blob.text());
        } else if (kind === 'doc') {
          const html = await documentApi.previewHtml(documentId);
          if (!cancelled) {
            setDocHtml(html);
          }
        } else if (kind === 'xlsx') {
          const XLSX = await import('xlsx');
          const wb = XLSX.read(await blob.arrayBuffer(), { type: 'array', cellDates: true });
          const parsed = wb.SheetNames.map((name) => {
            const ws = wb.Sheets[name];
            return {
              name,
              rows: XLSX.utils.sheet_to_json<unknown[]>(ws, {
                header: 1,
                defval: '',
                blankrows: false,
              }),
              merges: (ws['!merges'] ?? []).map((m) => ({ s: m.s, e: m.e })),
            };
          });
          if (!cancelled) {
            setSheets(parsed);
          }
        } else if (kind !== 'other') {
          objectUrl = URL.createObjectURL(blob);
          setUrl(objectUrl);
        }
        if (!cancelled) {
          setLoading(false);
        }
      })
      .catch(async (e) => {
        if (cancelled) {
          return;
        }
        let msg = '文件预览失败';
        try {
          // 后端错误体是 JSON，但请求按 blob 解析，这里手动解出来
          const raw = await (e?.response?.data as Blob | undefined)?.text?.();
          const parsed = raw ? JSON.parse(raw) : null;
          msg = parsed?.message || msg;
        } catch {
          // 非 JSON 错误体，保留默认提示
        }
        setError(msg);
        setLoading(false);
      });
    return () => {
      cancelled = true;
      if (objectUrl) {
        URL.revokeObjectURL(objectUrl);
      }
    };
    // kind 由 fileName 推导，随 documentId/fileName 一起变化
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [documentId, fileName]);

  const handleDownload = () => {
    const blob = blobRef.current;
    if (!blob) {
      return;
    }
    const objectUrl = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = objectUrl;
    a.download = fileName;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(objectUrl);
  };

  const content = () => {
    if (loading) {
      return (
        <div style={{ padding: '120px 0', textAlign: 'center' }}>
          <Spin size="large" />
          <div style={{ marginTop: 12, color: '#8c8c8c' }}>正在加载原文件…</div>
        </div>
      );
    }
    if (error) {
      return (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description={<Typography.Text type="danger">{error}</Typography.Text>}
          style={{ padding: '100px 0' }}
        >
          {hasFile && (
            <Button type="primary" onClick={handleDownload}>
              下载原文件
            </Button>
          )}
        </Empty>
      );
    }
    switch (kind) {
      case 'pdf':
        return url ? (
          <iframe src={url} title={fileName} style={{ width: '100%', height: '100%', border: 0 }} />
        ) : null;
      case 'image':
        return url ? (
          <div style={{ padding: 16, textAlign: 'center' }}>
            <img src={url} alt={fileName} style={{ maxWidth: '100%', maxHeight: '100%' }} />
          </div>
        ) : null;
      case 'video':
        return url ? (
          <div style={{ padding: 16, textAlign: 'center' }}>
            <video src={url} controls style={{ maxWidth: '100%', maxHeight: '100%' }} />
          </div>
        ) : null;
      case 'audio':
        return url ? (
          <audio src={url} controls style={{ width: '100%', padding: 16 }} />
        ) : null;
      case 'markdown':
        return <MarkdownViewer content={text ?? ''} />;
      case 'text':
        return (
          <pre
            style={{
              margin: 0,
              padding: 16,
              fontFamily: 'Consolas, Menlo, monospace',
              fontSize: 13,
              lineHeight: 1.7,
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-all',
            }}
          >
            {text}
          </pre>
        );
      case 'doc':
        // 沙箱 iframe：禁用脚本与同源，仅渲染转换后的静态 HTML
        return docHtml ? (
          <iframe
            sandbox=""
            srcDoc={docHtml}
            title={fileName}
            style={{ width: '100%', height: '100%', border: 0, background: '#fff' }}
          />
        ) : null;
      case 'docx':
        // 容器常驻在下方 JSX 中（加载中也挂载），这里不再重复渲染
        return null;
      case 'xlsx': {
        const sheet = sheets[sheetIdx];
        if (!sheet) {
          return null;
        }
        const { rows, merges } = sheet;
        // 合并区域：左上角单元格带 rowSpan/colSpan，被覆盖的单元格直接不渲染
        const spans = new Map<string, { rowSpan: number; colSpan: number }>();
        const covered = new Set<string>();
        merges.forEach(({ s, e }) => {
          spans.set(`${s.r},${s.c}`, { rowSpan: e.r - s.r + 1, colSpan: e.c - s.c + 1 });
          for (let r = s.r; r <= e.r; r += 1) {
            for (let c = s.c; c <= e.c; c += 1) {
              if (r !== s.r || c !== s.c) {
                covered.add(`${r},${c}`);
              }
            }
          }
        });
        // 列数取 行实际宽度 与 合并区域右边界 的最大值，避免合并单元格落在空列上时补不齐
        const width = Math.max(
          rows.reduce((max, r) => Math.max(max, r.length), 0),
          ...Array.from(spans.keys()).map((k) => Number(k.split(',')[1]) + 1),
          1,
        );
        const format = (v: unknown) => {
          if (v == null) {
            return '';
          }
          // cellDates 下日期是 Date 对象，按 YYYY-MM-DD 展示，避免出现一长串 locale 时间
          if (v instanceof Date) {
            return v.toISOString().slice(0, 10);
          }
          return String(v);
        };
        return (
          <div style={{ padding: 16, background: '#fff' }}>
            {sheets.length > 1 && (
              <Select
                size="small"
                value={sheetIdx}
                onChange={setSheetIdx}
                style={{ width: 220, marginBottom: 8 }}
                options={sheets.map((s, i) => ({ value: i, label: s.name }))}
              />
            )}
            <div style={{ overflow: 'auto' }}>
              <table
                style={{
                  borderCollapse: 'collapse',
                  fontSize: 13,
                  whiteSpace: 'nowrap',
                }}
              >
                <tbody>
                  {rows.map((row, r) => (
                    <tr key={r}>
                      {Array.from({ length: width }, (_, c) => {
                        const key = `${r},${c}`;
                        if (covered.has(key)) {
                          return null;
                        }
                        const span = spans.get(key);
                        return (
                          <td
                            key={c}
                            rowSpan={span?.rowSpan}
                            colSpan={span?.colSpan}
                            style={{
                              border: '1px solid #e5e7eb',
                              padding: '4px 10px',
                              verticalAlign: 'top',
                              // 首行按表头样式处理，并吸顶
                              ...(r === 0
                                ? {
                                    background: '#fafafa',
                                    fontWeight: 600,
                                    position: 'sticky',
                                    top: 0,
                                    zIndex: 1,
                                  }
                                : {}),
                            }}
                          >
                            {format(row[c])}
                          </td>
                        );
                      })}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              共 {Math.max(rows.length - 1, 0)} 行 · 工作表 {sheet.name}
            </Typography.Text>
          </div>
        );
      }
      default:
        return (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={`浏览器无法直接渲染 .${ext} 格式，可下载后用本地应用打开`}
            style={{ padding: '100px 0' }}
          >
            <Button type="primary" onClick={handleDownload}>
              下载原文件
            </Button>
          </Empty>
        );
    }
  };

  return (
    <div style={{ height: '100vh', display: 'flex', flexDirection: 'column', background: '#f5f5f5' }}>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 12,
          padding: '8px 16px',
          background: '#fff',
          borderBottom: '1px solid #e5e7eb',
        }}
      >
        <Typography.Text strong ellipsis style={{ flex: 1, marginBottom: 0 }}>
          {fileName}
        </Typography.Text>
        <Space size={8}>
          <Button size="small" onClick={handleDownload} disabled={!hasFile}>
            下载原文件
          </Button>
          <Button size="small" onClick={() => window.close()}>
            关闭
          </Button>
        </Space>
      </div>
      <div
        style={{
          flex: 1,
          minHeight: 0,
          overflow: kind === 'pdf' || kind === 'doc' ? 'hidden' : 'auto',
          background: kind === 'docx' || kind === 'markdown' ? '#f5f5f5' : '#fff',
        }}
      >
        {/* docx 渲染容器必须常驻挂载：loading 期间也要存在于 DOM 中，否则 ref 取不到会一直停在加载态 */}
        {kind === 'docx' && (
          <div
            ref={docxRef}
            style={{ padding: 16, display: loading || error ? 'none' : 'block' }}
          />
        )}
        {content()}
      </div>
    </div>
  );
}
