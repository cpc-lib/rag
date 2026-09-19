import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 与 rag-api(8080) 同源代理：常规请求与 SSE 均走 /api，避免跨域
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
