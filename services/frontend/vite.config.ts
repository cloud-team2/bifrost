import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// vite.config는 Node 환경에서 실행된다(@types/node 미설치 → process를 로컬 선언).
declare const process: { env: Record<string, string | undefined> }

// 개발 서버에서 API 요청을 프록시한다(배포 시엔 nginx가 담당).
// 타깃은 VITE_BACKEND_ORIGIN / VITE_AI_ORIGIN으로 오버라이드 가능하다.
const backend = process.env.VITE_BACKEND_ORIGIN ?? 'http://localhost:8080'
const ai = process.env.VITE_AI_ORIGIN ?? 'http://localhost:8082'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    proxy: {
      '^/api/v1/agent(?:[/?]|$)': {
        target: ai,
        changeOrigin: true,
      },
      '^/api/v1/approvals(?:[/?]|$)': {
        target: ai,
        changeOrigin: true,
      },
      '^/api/v1/tools(?:[/?]|$)': {
        target: ai,
        changeOrigin: true,
      },
      '^/api/v1/catalogs(?:[/?]|$)': {
        target: ai,
        changeOrigin: true,
      },
      // FastAPI 메타 라우트(#377) — ai-service가 직접 서빙. generic '/api'(operations-backend)보다 먼저 매치돼야 한다.
      '^/api/v1/(health|ready|version|capabilities)(?:[/?]|$)': {
        target: ai,
        changeOrigin: true,
      },
      // internal-ops(#377) — 로컬 개발 편의용 Spring InternalOpsController 프록시.
      // 운영 nginx는 public ingress에서 /internal/ops/**를 의도적으로 노출하지 않는다.
      '^/internal/ops(?:[/?]|$)': {
        target: backend,
        changeOrigin: true,
      },
      '/api': {
        target: backend,
        changeOrigin: true,
      },
    },
  },
})
