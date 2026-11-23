import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 与 rag-api(8080) 同源代理：常规请求与 SSE 均走 /api，避免跨域
export default defineConfig({
  plugins: [react()],
  build: {
    chunkSizeWarningLimit: 1200,
    rollupOptions: {
      output: {
        // 第三方大依赖单独成块，配合路由懒加载降低首屏体积（mermaid/xlsx/docx-preview 仅预览页用）
        manualChunks: {
          'react-vendor': ['react', 'react-dom', 'react-router-dom'],
          'antd-vendor': ['antd'],
          'markdown-vendor': ['react-markdown', 'remark-gfm'],
          'preview-vendor': ['mermaid', 'docx-preview', 'xlsx', 'jszip', 'hls.js'],
        },
      },
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      // WebSocket：转码进度推送（ws 必须显式开启）
      '/ws': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        ws: true,
      },
    },
  },
});
