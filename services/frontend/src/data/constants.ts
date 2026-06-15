import type { User } from './types'

// 앱 표시용 상수 (mock 데이터 아님). 도메인 데이터는 전부 백엔드 API에서 온다.
export const APP_VERSION = 'v0.8.2'

// 백엔드 dev seed 계정과 동일(DevDataSeeder: ta@bifrost.io / ta123456).
// 가짜 데이터가 아니라 실제 등록된 계정의 로그인 자동입력용 — 편의 기능.
const DEMO_USER: User = {
  name: '김민준',
  email: 'ta@bifrost.io',
  role: 'ADMIN',
  initial: '김',
  joinedAt: null,
  lastLoginAt: null,
}

export const DEMO_ACCOUNTS: { user: User; password: string }[] = [
  { user: DEMO_USER, password: 'ta123456' },
]
