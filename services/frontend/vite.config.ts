import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// vite.config는 Node 환경에서 실행된다(@types/node 미설치 → process를 로컬 선언).
declare const process: { env: Record<string, string | undefined> }

// 개발 서버에서 /api 요청을 operations-backend로 프록시한다(배포 시엔 nginx가 담당).
// 타깃은 VITE_BACKEND_ORIGIN(기본 http://localhost:8080).
const backend = process.env.VITE_BACKEND_ORIGIN ?? 'http://localhost:8080'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    proxy: {
      '/api': {
        target: backend,
        changeOrigin: true,
      },
    },
  },
})
