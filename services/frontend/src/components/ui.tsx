import {
  useEffect,
  useRef,
  useState,
  type ButtonHTMLAttributes,
  type InputHTMLAttributes,
  type ReactNode,
} from 'react'
import { Icon, type IconName } from './Icon'
import { cn } from '../lib/format'

/* ------------------------------------------------------------------ Button */

type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'dark'

export function Button({
  variant = 'primary',
  size = 'md',
  icon,
  iconRight,
  className,
  children,
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: ButtonVariant
  size?: 'sm' | 'md'
  icon?: IconName
  iconRight?: IconName
}) {
  const variants: Record<ButtonVariant, string> = {
    primary: 'bg-brand-600 text-white hover:bg-brand-700 disabled:bg-brand-300 shadow-sm',
    secondary: 'bg-white border border-gray-300 text-gray-700 hover:bg-gray-50 disabled:opacity-50',
    ghost: 'text-gray-600 hover:bg-gray-100 disabled:opacity-40',
    danger: 'bg-white border border-[#c0392b] text-[#c0392b] hover:bg-[#fcf3f2]',
    dark: 'bg-[#1b1e24] text-white hover:bg-[#2a2e36]',
  }
  return (
    <button
      className={cn(
        'inline-flex items-center justify-center gap-2 rounded-md font-medium transition-colors disabled:cursor-not-allowed',
        size === 'sm' ? 'px-3 py-1.5 text-[13px]' : 'px-4 py-2.5 text-sm',
        variants[variant],
        className,
      )}
      {...rest}
    >
      {icon && <Icon name={icon} size={size === 'sm' ? 14 : 16} />}
      {children}
      {iconRight && <Icon name={iconRight} size={size === 'sm' ? 14 : 16} />}
    </button>
  )
}

export function IconButton({
  icon,
  size = 16,
  className,
  active,
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & { icon: IconName; size?: number; active?: boolean }) {
  return (
    <button
      className={cn(
        'inline-flex items-center justify-center rounded-md border p-2 transition-colors',
        active
          ? 'border-brand-200 bg-brand-50 text-brand-600'
          : 'border-gray-200 bg-white text-gray-500 hover:bg-gray-50 hover:text-gray-700',
        className,
      )}
      {...rest}
    >
      <Icon name={icon} size={size} />
    </button>
  )
}

/* ------------------------------------------------------------------- Badge */

export function Badge({
  children,
  tone = 'gray',
  className,
}: {
  children: ReactNode
  tone?: 'gray' | 'green' | 'amber' | 'blue' | 'rose' | 'purple'
  className?: string
}) {
  // 모노크롬: 오류(rose)만 색, 그 외는 중립 회색 틴트.
  const tones = {
    gray: 'bg-gray-100 text-gray-600',
    green: 'bg-[#ededed] text-[#6b6b73]',
    amber: 'bg-[#ededed] text-[#6b6b73]',
    blue: 'bg-[#ededed] text-[#6b6b73]',
    rose: 'bg-[#fcf3f2] text-[#c0392b]',
    purple: 'bg-[#ededed] text-[#6b6b73]',
  }
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded px-2 py-0.5 text-[11px] font-semibold tracking-wide',
        tones[tone],
        className,
      )}
    >
      {children}
    </span>
  )
}

export function StatusPill({ status }: { status: string }) {
  const map: Record<string, 'green' | 'amber' | 'blue' | 'gray'> = {
    ACTIVE: 'green',
    PAUSED: 'amber',
    SCHEDULED: 'blue',
    DRAFT: 'gray',
  }
  return <Badge tone={map[status] ?? 'gray'}>{status}</Badge>
}

/* ------------------------------------------------------------------- Card */

export function Card({ className, children }: { className?: string; children: ReactNode }) {
  return (
    <div className={cn('rounded-xl border border-gray-200 bg-white shadow-card', className)}>{children}</div>
  )
}

/* ------------------------------------------------------------------ Toggle */

export function Switch({
  checked,
  onChange,
  size = 'md',
}: {
  checked: boolean
  onChange: (v: boolean) => void
  size?: 'sm' | 'md'
}) {
  const w = size === 'sm' ? 'h-[18px] w-8' : 'h-5 w-9'
  const knob = size === 'sm' ? 'h-3.5 w-3.5' : 'h-4 w-4'
  return (
    <button
      type="button"
      onClick={() => onChange(!checked)}
      className={cn(
        'relative inline-flex shrink-0 items-center rounded-full transition-colors',
        w,
        checked ? 'bg-brand-600' : 'bg-gray-300',
      )}
    >
      <span
        className={cn(
          'inline-block transform rounded-full bg-white shadow transition-transform',
          knob,
          checked ? (size === 'sm' ? 'translate-x-[18px]' : 'translate-x-[18px]') : 'translate-x-[3px]',
        )}
      />
    </button>
  )
}

export function Checkbox({
  checked,
  indeterminate,
  onChange,
}: {
  checked: boolean
  indeterminate?: boolean
  onChange?: (v: boolean) => void
}) {
  return (
    <button
      type="button"
      onClick={(e) => {
        e.stopPropagation()
        onChange?.(!checked)
      }}
      className={cn(
        'flex h-[18px] w-[18px] shrink-0 items-center justify-center rounded transition-colors',
        checked || indeterminate
          ? 'bg-brand-600 text-white'
          : 'border border-gray-300 bg-white hover:border-brand-400',
      )}
    >
      {indeterminate ? (
        <Icon name="minus" size={13} strokeWidth={3} />
      ) : checked ? (
        <Icon name="check" size={13} strokeWidth={3} />
      ) : null}
    </button>
  )
}

/* ---------------------------------------------------------------- Spinner */

export function Spinner({ size = 18, className }: { size?: number; className?: string }) {
  return (
    <div
      className={cn('bifrost-spin rounded-full border-2 border-brand-200 border-t-brand-600', className)}
      style={{ width: size, height: size }}
    />
  )
}

export function LoadingTile({ label, sub }: { label: string; sub?: string }) {
  return (
    <div className="flex flex-col items-center justify-center gap-4 py-20 text-center">
      <div className="flex h-20 w-20 items-center justify-center rounded-2xl bg-[#ededed]">
        <Spinner size={34} />
      </div>
      <div>
        <div className="text-lg font-semibold text-gray-800">{label}</div>
        {sub && <div className="mt-1 text-sm text-gray-500">{sub}</div>}
      </div>
      <div className="h-1 w-44 overflow-hidden rounded-full bg-gray-200">
        <div className="h-full w-1/2 animate-pulse rounded-full bg-brand-500" />
      </div>
    </div>
  )
}

/* ------------------------------------------------------------- ProgressBar */

export function ProgressBar({ value }: { value: number }) {
  return (
    <div className="h-1.5 w-full overflow-hidden rounded-full bg-gray-200">
      <div className="h-full rounded-full bg-[#3a47c2]" style={{ width: `${value}%` }} />
    </div>
  )
}

/* ----------------------------------------------------------------- Spark */

export function Spark({
  data,
  width = 120,
  height = 30,
  color = '#3a47c2',
}: {
  data: number[]
  width?: number
  height?: number
  color?: string
}) {
  const max = Math.max(1, ...data)
  const n = data.length
  const gap = n > 30 ? 2 : 3
  const bw = Math.max(1.5, (width - gap * (n - 1)) / n)
  return (
    <svg width={width} height={height} className="overflow-visible">
      <line
        x1="0"
        y1={height - 1.5}
        x2={width}
        y2={height - 1.5}
        stroke="#cfd4de"
        strokeWidth="1.5"
        strokeDasharray="3 3"
      />
      {data.map((v, i) => {
        const h = v <= 0 ? 0 : Math.max(3, (v / max) * (height - 4))
        return (
          <rect
            key={i}
            x={i * (bw + gap)}
            y={height - 1.5 - h}
            width={bw}
            height={h}
            rx={Math.min(bw / 2, 1.5)}
            fill={color}
          />
        )
      })}
    </svg>
  )
}

/* ------------------------------------------------------------- TextField */

export function TextField({
  label,
  hint,
  required,
  rightSlot,
  className,
  ...rest
}: InputHTMLAttributes<HTMLInputElement> & {
  label: string
  hint?: ReactNode
  required?: boolean
  rightSlot?: ReactNode
}) {
  return (
    <div className={className}>
      <div className="relative">
        <input
          {...rest}
          placeholder=" "
          className="peer h-14 w-full rounded-md border border-gray-300 bg-white px-3 pt-5 pb-1.5 text-sm text-gray-800 outline-none transition-colors focus:border-brand-600 focus:ring-1 focus:ring-brand-600"
        />
        <label className="pointer-events-none absolute left-3 top-2 text-[11px] text-gray-500 transition-all peer-placeholder-shown:top-1/2 peer-placeholder-shown:-translate-y-1/2 peer-placeholder-shown:text-sm peer-placeholder-shown:text-gray-400 peer-focus:top-2 peer-focus:translate-y-0 peer-focus:text-[11px] peer-focus:text-brand-600">
          {label} {required && <span className="text-[#c0392b]">*</span>}
        </label>
        {rightSlot && <div className="absolute right-3 top-1/2 -translate-y-1/2">{rightSlot}</div>}
      </div>
      {hint && <div className="mt-1 pl-1 text-xs text-gray-400">{hint}</div>}
    </div>
  )
}

/* ----------------------------------------------------------------- Menu */

export function useClickOutside<T extends HTMLElement>(onClose: () => void) {
  const ref = useRef<T>(null)
  useEffect(() => {
    function handler(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) onClose()
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [onClose])
  return ref
}

export interface MenuItem {
  label: string
  icon?: IconName
  danger?: boolean
  meta?: ReactNode
  onClick?: () => void
}

export function Menu({
  trigger,
  items,
  align = 'right',
  width = 200,
}: {
  trigger: (props: { open: boolean; toggle: () => void }) => ReactNode
  items: MenuItem[]
  align?: 'left' | 'right'
  width?: number
}) {
  const [open, setOpen] = useState(false)
  const ref = useClickOutside<HTMLDivElement>(() => setOpen(false))
  return (
    <div className="relative" ref={ref}>
      {trigger({ open, toggle: () => setOpen((o) => !o) })}
      {open && (
        <div
          className={cn(
            'bifrost-fade absolute z-40 mt-1 overflow-hidden rounded-lg border border-gray-200 bg-white py-1 shadow-lg',
            align === 'right' ? 'right-0' : 'left-0',
          )}
          style={{ width }}
        >
          {items.map((it) => (
            <button
              key={it.label}
              onClick={() => {
                setOpen(false)
                it.onClick?.()
              }}
              className={cn(
                'flex w-full items-center gap-2.5 px-3.5 py-2 text-left text-[13px] transition-colors hover:bg-gray-50',
                it.danger ? 'text-[#c0392b]' : 'text-gray-700',
              )}
            >
              {it.icon && <Icon name={it.icon} size={15} className={it.danger ? 'text-[#c0392b]' : 'text-gray-400'} />}
              <span className="flex-1">{it.label}</span>
              {it.meta}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

/* ----------------------------------------------------------------- Tabs */

export function Tabs({
  tabs,
  value,
  onChange,
}: {
  tabs: string[]
  value: string
  onChange: (t: string) => void
}) {
  return (
    <div className="flex gap-6 border-b border-gray-200">
      {tabs.map((t) => (
        <button
          key={t}
          onClick={() => onChange(t)}
          className={cn(
            '-mb-px border-b-2 pb-2.5 text-sm font-medium transition-colors',
            value === t
              ? 'border-brand-600 text-brand-700'
              : 'border-transparent text-gray-500 hover:text-gray-700',
          )}
        >
          {t}
        </button>
      ))}
    </div>
  )
}

/* ------------------------------------------------------------- EmptyState */

export function EmptyState({
  icon = 'table',
  title,
  sub,
}: {
  icon?: IconName
  title: string
  sub?: string
}) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 py-24 text-center">
      <div className="flex h-14 w-14 items-center justify-center rounded-full bg-[#ededed] text-[#6b6b73]">
        <Icon name={icon} size={24} />
      </div>
      <div className="text-[15px] font-semibold text-gray-700">{title}</div>
      {sub && <div className="max-w-sm text-sm text-gray-400">{sub}</div>}
    </div>
  )
}

/* ----------------------------------------------------------------- Avatar */

export function Avatar({ name, size = 32 }: { name: string; size?: number }) {
  return (
    <div
      className="flex shrink-0 items-center justify-center overflow-hidden rounded-full bg-[#0d0d0d] font-semibold text-white"
      style={{ width: size, height: size, fontSize: size * 0.4 }}
    >
      {name.slice(0, 1)}
    </div>
  )
}
