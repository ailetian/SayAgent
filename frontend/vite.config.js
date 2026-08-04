import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// 开发期代理 /api -> 后端（后端端口见 backend/src/main/resources/application.yml 的 server.port=9095）
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 6177,
    proxy: {
      '/api': {
        target: 'http://localhost:9095',
        changeOrigin: true
      }
    }
  }
})
