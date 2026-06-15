import type { ReactNode } from 'react'
import { Icon, type IconName } from './Icon'
import { cn } from '../lib/format'

/* status → colour mapping shared across views */
const TONE: Record<string, { dot: string; text: string; bg: string }> = {
  healthy: { dot: 'bg-emerald-500', text: 'text-emerald-700', bg: 'bg-emerald-50' },
  active: { dot: 'bg-emerald-500', text: 'text-emerald-700', bg: 'bg-emerald-50' },
  RUNNING: { dot: 'bg-emerald-500', text: 'text-emerald-700', bg: 'bg-emerald-50' },
  STABLE: { dot: 'bg-emerald-500', text: 'text-emerald-700', bg: 'bg-emerald-50' },
  CONNECTED: { dot: 'bg-emerald-500', text: 'text-emerald-700', bg: 'bg-emerald-50' },
  resolved: { dot: 'bg-emerald-500', text: 'text-emerald-700', bg: 'bg-emerald-50' },
  RESOLVED: { dot: 'bg-emerald-500', text: 'text-emerald-700', bg: 'bg-emerald-50' },
  warning: { dot: 'bg-amber-500', text: 'text-amber-700', bg: 'bg-amber-50' },
  lag: { dot: 'bg-amber-500', text: 'text-amber-700', bg: 'bg-amber-50' },
  WARN: { dot: 'bg-amber-500', text: 'text-amber-700', bg: 'bg-amber-50' },
  WARNING: { dot: 'bg-amber-500', text: 'text-amber-700', bg: 'bg-amber-50' },
  REBALANCING: { dot: 'bg-amber-500', text: 'text-amber-700', bg: 'bg-amber-50' },
  PARTIALLY_FAILED: { dot: 'bg-amber-500', text: 'text-amber-700', bg: 'bg-amber-50' },
  investigating: { dot: 'bg-amber-500', text: 'text-amber-700', bg: 'bg-amber-50' },
  INVESTIGATING: { dot: 'bg-amber-500', text: 'text-amber-700', bg: 'bg-amber-50' },
  error: { dot: 'bg-rose-500', text: 'text-rose-700', bg: 'bg-rose-50' },
  ERROR: { dot: 'bg-rose-500', text: 'text-rose-700', bg: 'bg-rose-50' },
  DEAD: { dot: 'bg-rose-500', text: 'text-rose-700', bg: 'bg-rose-50' },
  FAILED: { dot: 'bg-rose-500', text: 'text-rose-700', bg: 'bg-rose-50' },
  critical: { dot: 'bg-rose-500', text: 'text-rose-700', bg: 'bg-rose-50' },
  CRITICAL: { dot: 'bg-rose-500', text: 'text-rose-700', bg: 'bg-rose-50' },
  open: { dot: 'bg-rose-500', text: 'text-rose-700', bg: 'bg-rose-50' },
  OPEN: { dot: 'bg-rose-500', text: 'text-rose-700', bg: 'bg-rose-50' },
  revoked: { dot: 'bg-rose-500', text: 'text-rose-700', bg: 'bg-rose-50' },
  REVOKED: { dot: 'bg-rose-500', text: 'text-rose-700', bg: 'bg-rose-50' },
  paused: { dot: 'bg-zinc-400', text: 'text-zinc-600', bg: 'bg-zinc-100' },
  PAUSED: { dot: 'bg-zinc-400', text: 'text-zinc-600', bg: 'bg-zinc-100' },
  EMPTY: { dot: 'bg-zinc-400', text: 'text-zinc-600', bg: 'bg-zinc-100' },
  inactive: { dot: 'bg-zinc-400', text: 'text-zinc-600', bg: 'bg-zinc-100' },
  INACTIVE: { dot: 'bg-zinc-400', text: 'text-zinc-600', bg: 'bg-zinc-100' },
  creating: { dot: 'bg-brand-500', text: 'text-brand-700', bg: 'bg-brand-50' },
  UNASSIGNED: { dot: 'bg-brand-500', text: 'text-brand-700', bg: 'bg-brand-50' },
  info: { dot: 'bg-sky-500', text: 'text-sky-700', bg: 'bg-sky-50' },
  ACTIVE: { dot: 'bg-emerald-500', text: 'text-emerald-700', bg: 'bg-emerald-50' },
}

export function statusTone(status: string) {
  return TONE[status] ?? TONE.paused
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
      <span className={cn('h-1.5 w-1.5 rounded-full', t.dot)} />
      {label ?? status}
    </span>
  )
}

export function StatusDot({ status }: { status: string }) {
  return <span className={cn('h-2 w-2 rounded-full', statusTone(status).dot)} />
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
  const valueColor =
    tone === 'good'
      ? 'text-emerald-600'
      : tone === 'warn'
        ? 'text-amber-600'
        : tone === 'bad'
          ? 'text-rose-600'
          : 'text-gray-900'
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
  const tone = pct > 80 ? 'bg-rose-500' : pct > 60 ? 'bg-amber-500' : 'bg-emerald-500'
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
