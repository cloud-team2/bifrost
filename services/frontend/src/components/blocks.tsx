import type { ReactNode } from 'react'
import { Icon, type IconName } from './Icon'
import { cn } from '../lib/format'

/* status → 색 (#780): 솔리드 배경 + 흰 글자.
 * 정상=초록 · 경고/지연=주황 · 일시정지=슬레이트 · 대기/info=회색 · 오류=빨강. */
const GREEN = '#157f4a'
const ORANGE = '#d97316'
const GRAY = '#6b6b73'
const RED = '#c0392b'
const SLATE = '#475569' // 일시정지(PAUSED)
const TONE: Record<string, string> = {
  healthy: GREEN, active: GREEN, RUNNING: GREEN, STABLE: GREEN, CONNECTED: GREEN,
  resolved: GREEN, RESOLVED: GREEN, ACTIVE: GREEN,
  warning: ORANGE, lag: ORANGE, WARN: ORANGE, WARNING: ORANGE, REBALANCING: ORANGE,
  PARTIALLY_FAILED: ORANGE, investigating: ORANGE, INVESTIGATING: ORANGE,
  paused: SLATE, PAUSED: SLATE,
  EMPTY: GRAY, inactive: GRAY, INACTIVE: GRAY, info: GRAY,
  creating: GRAY, UNASSIGNED: GRAY,
  error: RED, ERROR: RED, DEAD: RED, FAILED: RED, STOPPED: RED, DOWN: RED,
  critical: RED, CRITICAL: RED, open: RED, OPEN: RED, revoked: RED, REVOKED: RED,
}

export function statusTone(status: string): string {
  return TONE[status] ?? TONE[String(status).toUpperCase()] ?? GRAY
}

export function StatusBadge({ status, label }: { status: string; label?: string }) {
  return (
    <span
      className="inline-flex items-center rounded-full px-2 py-0.5 text-[10.5px] font-semibold uppercase tracking-wide text-white"
      style={{ background: statusTone(status) }}
    >
      {label ?? status}
    </span>
  )
}

export function StatusDot({ status }: { status: string }) {
  return <span className="h-2 w-2 rounded-full" style={{ background: statusTone(status) }} />
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
    <div className="min-w-0 rounded-xl border border-gray-200 bg-white p-4 shadow-card">
      <div className="flex min-w-0 items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wide text-gray-400">
        {icon && <Icon name={icon} size={12} className="shrink-0" />}
        <span className="min-w-0 break-keep">{label}</span>
      </div>
      <div className={cn('mt-1.5 truncate text-[24px] font-bold leading-none', valueColor)}>{value}</div>
      {sub && <div className="mt-1.5 break-words text-[12px] text-gray-400">{sub}</div>}
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
  // 데이터 색(잉크블루) 기본, 임계 초과(>80%)만 빨강으로 위험 신호.
  const tone = pct > 80 ? 'bg-[#c0392b]' : 'bg-[#3a47c2]'
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
    <div className={cn('rounded-xl border border-gray-200 bg-white shadow-card', className)}>
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
