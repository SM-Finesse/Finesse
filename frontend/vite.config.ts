/// <reference types="vitest/config" />
import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig(({ mode }) => {
  /* 백엔드가 다른 PC에서 돌면 .env.local에 VITE_API_PROXY_TARGET=http://<그 PC 주소>:8080 */
  const target = loadEnv(mode, process.cwd(), '').VITE_API_PROXY_TARGET || 'http://localhost:8080'
  const proxy = { '/api': { target, changeOrigin: true } }

  return {
    plugins: [react(), tailwindcss()],
    server: { proxy },
    preview: { proxy },
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      coverage: {
        provider: 'v8',
        include: ['src/**/*.{ts,tsx}'],
        exclude: ['src/main.tsx', 'src/test/**'],
      },
    },
  }
})
