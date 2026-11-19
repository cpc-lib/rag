import { useEffect, useRef, useState } from 'react';

type MermaidApi = typeof import('mermaid')['default'];

let api: MermaidApi | null = null;
let loading: Promise<MermaidApi> | null = null;
let seq = 0;

/** 按需加载 mermaid（约 1MB），避免拖慢首屏包体。 */
const getMermaid = async (): Promise<MermaidApi> => {
  if (api) {
    return api;
  }
  if (!loading) {
    loading = import('mermaid').then((m) => {
      m.default.initialize({
        startOnLoad: false,
        securityLevel: 'strict',
        theme: 'default',
        fontFamily: 'inherit',
        // 默认 xychart 配色极淡（#FFF4DD 一类的浅色），折线几乎看不清，这里改成高对比配色
        themeVariables: {
          xyChart: {
            plotColorPalette: '#1677ff,#ff7a45,#52c41a,#722ed1,#13c2c2,#eb2f96',
            backgroundColor: '#ffffff',
            titleColor: '#1f2937',
            xAxisLabelColor: '#4b5563',
            xAxisTitleColor: '#4b5563',
            xAxisTickColor: '#9ca3af',
            xAxisLineColor: '#9ca3af',
            yAxisLabelColor: '#4b5563',
            yAxisTitleColor: '#4b5563',
            yAxisTickColor: '#9ca3af',
            yAxisLineColor: '#9ca3af',
          },
        },
      });
      api = m.default;
      return m.default;
    });
  }
  return loading;
};

/**
 * 渲染 ```mermaid 代码块。
 * 流式输出时代码不完整会渲染失败，此时降级显示原始代码；debounce 避免每个增量都重复解析。
 */
export default function Mermaid({ code }: { code: string }) {
  const host = useRef<HTMLDivElement>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setFailed(false);
    const timer = setTimeout(() => {
      getMermaid()
        .then((m) => m.render(`mermaid-svg-${Date.now()}-${seq++}`, code))
        .then(({ svg }) => {
          if (!cancelled && host.current) {
            host.current.innerHTML = svg;
          }
        })
        .catch(() => {
          if (!cancelled) {
            setFailed(true);
          }
        });
    }, 300);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [code]);

  if (failed) {
    return (
      <pre
        style={{
          background: '#f5f5f5',
          padding: 10,
          borderRadius: 6,
          overflowX: 'auto',
          fontSize: 12,
        }}
      >
        {code}
      </pre>
    );
  }
  return <div ref={host} style={{ overflowX: 'auto', padding: '8px 0' }} />;
}
