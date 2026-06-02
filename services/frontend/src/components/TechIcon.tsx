import { Icon, type IconName } from './Icon'
import type { Node } from '../data/types'

export type TechKind = 'postgres' | 'mariadb' | 'kafka' | 'service'

const MAP: Record<TechKind, { bg: string; fg: string; icon: IconName }> = {
  postgres: { bg: '#e9f1f8', fg: '#336791', icon: 'database' },
  mariadb: { bg: '#f6ede7', fg: '#a8763f', icon: 'database' },
  kafka: { bg: '#e7e7ea', fg: '#1b1e24', icon: 'server' },
  service: { bg: '#ecebfb', fg: '#5a4fcf', icon: 'branch' },
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
