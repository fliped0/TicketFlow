import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const target = process.env.TF_FRONTEND_API_TARGET ?? 'http://127.0.0.1:8080'
if (!/^http:\/\/127\.0\.0\.1:\d{1,5}$/.test(target) || Number(new URL(target).port) > 65535 || Number(new URL(target).port) < 1) {
  throw new Error('TF_FRONTEND_API_TARGET must be an explicit local loopback origin')
}
const proxy = { '/api': { target, changeOrigin: false } }

export default defineConfig({
  plugins: [vue()],
  server: { host: '127.0.0.1', port: 5173, strictPort: true, proxy },
  preview: { host: '127.0.0.1', port: 4173, strictPort: true, proxy },
})
