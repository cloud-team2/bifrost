import type { ReactNode } from 'react'
import { Icon, type IconName } from './Icon'
import { cn } from '../lib/format'

/* status → colour mapping shared across views.
 * "Codex 모노크롬"(#719): 유채색은 오류뿐. 정상·실행중·지연·info 는 모두 무채색이며
 * 점(dot)·배경 없이 라벨만. 오류/실패/인시던트만 #C0392B(텍스트·점) + 옅은 빨강 배경. */
const NEUTRAL = { dot: '', text: 'text-[#8a8a8a]', bg: '' } // 정상/실행중
const MUTED = { dot: '', text: 'text-[#6b6b73]', bg: '' } // 지연/경고/대기/info
const ERROR = { dot: 'bg-[#c0392b]', text: 'text-[#c0392b]', bg: 'bg-[#fcf3f2]' }
const TONE: Record<string, { dot: string; text: string; bg: string }> = {
  healthy: NEUTRAL,
  active: NEUTRAL,
  RUNNING: NEUTRAL,
  STABLE: NEUTRAL,
  CONNECTED: NEUTRAL,
  resolved: NEUTRAL,
  RESOLVED: NEUTRAL,
  ACTIVE: NEUTRAL,
  creating: NEUTRAL,
  UNASSIGNED: NEUTRAL,
  warning: MUTED,
  lag: MUTED,
  WARN: MUTED,
  WARNING: MUTED,
  REBALANCING: MUTED,
  PARTIALLY_FAILED: MUTED,
  investigating: MUTED,
  INVESTIGATING: MUTED,
  paused: MUTED,
  PAUSED: MUTED,
  EMPTY: MUTED,
  inactive: MUTED,
  INACTIVE: MUTED,
  info: MUTED,
  error: ERROR,
  ERROR: ERROR,
  DEAD: ERROR,
  FAILED: ERROR,
  critical: ERROR,
  CRITICAL: ERROR,
  open: ERROR,
  OPEN: ERROR,
  revoked: ERROR,
  REVOKED: ERROR,
}

export function statusTone(status: string) {
  return TONE[status] ?? MUTED
}

export function StatusBadge({ status, label }: { status: string; label?: string }) {
  const t = statusTone(status)
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-[10.5px] font-semibold uppercase tracking-wide',
        t.bg,
        t.text,
      )}
    >
      {t.dot && <span className={cn('h-1.5 w-1.5 rounded-full', t.dot)} />}
      {label ?? status}
    </span>
  )
}

export function StatusDot({ status }: { status: string }) {
  // 오류는 빨강 점, 그 외는 중립 회색 점(색 제거하되 마커는 유지).
  const t = statusTone(status)
  return <span className={cn('h-2 w-2 rounded-full', t.dot || 'bg-[#c8c8c8]')} />
}

export function MetricCard({
  label,
  value,
  sub,
  icon,
  tone,
}: {
  label: string
  value: ReactNode
  sub?: ReactNode
  icon?: IconName
  tone?: 'default' | 'good' | 'warn' | 'bad'
}) {
  // 모노크롬: 정상/경고 수치는 잉크로 강조, 오류만 빨강.
  const valueColor =
    tone === 'bad' ? 'text-[#c0392b]' : 'text-gray-900'
  return (
    <div className="rounded-xl border border-gray-200 bg-white p-4">
      <div className="flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wide text-gray-400">
        {icon && <Icon name={icon} size={12} />}
        {label}
      </div>
      <div className={cn('mt-1.5 text-[24px] font-bold leading-none', valueColor)}>{value}</div>
      {sub && <div className="mt-1.5 text-[12px] text-gray-400">{sub}</div>}
    </div>
  )
}

export function Gauge({
  label,
  value,
  unit = '%',
  max = 100,
}: {
  label: string
  value: number
  unit?: string
  max?: number
}) {
  const pct = Math.min(100, (value / max) * 100)
  // 임계 초과(>80%)만 빨강으로 위험 신호, 그 외는 중립 잉크/회색.
  const tone = pct > 80 ? 'bg-[#c0392b]' : 'bg-[#8a8a8a]'
  return (
    <div>
      <div className="flex items-center justify-between text-[11.5px]">
        <span className="text-gray-500">{label}</span>
        <span className="font-medium text-gray-700">
          {value}
          {unit}
        </span>
      </div>
      <div className="mt-1 h-1.5 w-full overflow-hidden rounded-full bg-gray-100">
        <div className={cn('h-full rounded-full', tone)} style={{ width: `${pct}%` }} />
      </div>
    </div>
  )
}

export function PageHead({
  title,
  sub,
  actions,
}: {
  title: string
  sub?: string
  actions?: ReactNode
}) {
  return (
    <div className="flex items-start justify-between">
      <div>
        <h1 className="text-[20px] font-semibold text-gray-900">{title}</h1>
        {sub && <p className="mt-0.5 text-[13px] text-gray-500">{sub}</p>}
      </div>
      {actions}
    </div>
  )
}

export function Panel({
  title,
  right,
  children,
  className,
}: {
  title?: string
  right?: ReactNode
  children: ReactNode
  className?: string
}) {
  return (
    <div className={cn('rounded-xl border border-gray-200 bg-white', className)}>
      {title && (
        <div className="flex items-center justify-between border-b border-gray-100 px-4 py-2.5">
          <span className="text-[13px] font-semibold text-gray-800">{title}</span>
          {right}
        </div>
      )}
      {children}
    </div>
  )
}
