import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 苍穹外卖用户端 H5
// 后端默认 http://localhost:8080（与原小程序 env.js 注释中的 nginx 转发地址一致）
const backend = process.env.VITE_PROXY_TARGET ?? 'http://localhost:8080'
const agent = process.env.VITE_AGENT_PROXY_TARGET ?? 'http://localhost:8000'

// dev 与 preview 用同一套 HTTP 和 WebSocket 代理。
const proxy = {
  '/api': {
    target: backend,
    changeOrigin: true,
    rewrite: (path: string) => path.replace(/^\/api/, ''),
  },
  '/ws/voice': {
    target: agent,
    changeOrigin: true,
    ws: true,
  },
}

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy,
  },
  preview: {
    port: 4173,
    proxy,
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 1500,
  },
})
