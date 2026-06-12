import type { User } from './types'

export const CURRENT_USER: User = {
  name: '김민준',
  email: 'ta@bifrost.io',
  role: 'ADMIN',
  initial: '김',
  joinedAt: null,
  lastLoginAt: null,
}

// 백엔드 dev seed 계정과 일치(DevDataSeeder: ta@bifrost.io / ta123456). 로그인 화면 자동입력용.
export const DEMO_ACCOUNTS: { user: User; password: string }[] = [
  { user: CURRENT_USER, password: 'ta123456' },
]

export const APP_VERSION = 'v0.8.2'
