import { Icon, type IconName } from './Icon'
import type { Node } from '../data/types'

export type TechKind = 'postgres' | 'mariadb' | 'kafka' | 'service'

// 모노크롬(#719): 기술 브랜드 색 제거 → 중립 회색 칩. 종류는 아이콘 글리프로 구분.
const MAP: Record<TechKind, { bg: string; fg: string; icon: IconName }> = {
  postgres: { bg: '#f2f2f2', fg: '#6b6b73', icon: 'database' },
  mariadb: { bg: '#f2f2f2', fg: '#6b6b73', icon: 'database' },
  kafka: { bg: '#f2f2f2', fg: '#6b6b73', icon: 'server' },
  service: { bg: '#f2f2f2', fg: '#6b6b73', icon: 'branch' },
}

export function nodeKind(n: Node): TechKind {
  if (n.type === 'service') return 'service'
  return n.tech === 'mariadb' ? 'mariadb' : 'postgres'
}

export function TechIcon({
  kind,
  size = 40,
  shape = 'rounded',
}: {
  kind: TechKind
  size?: number
  shape?: 'rounded' | 'circle'
}) {
  const m = MAP[kind]
  return (
    <div
      className="flex shrink-0 items-center justify-center"
      style={{
        width: size,
        height: size,
        background: m.bg,
        borderRadius: shape === 'circle' ? 999 : size * 0.26,
        color: m.fg,
      }}
    >
      <Icon name={m.icon} size={Math.round(size * 0.52)} strokeWidth={1.8} />
    </div>
  )
}
