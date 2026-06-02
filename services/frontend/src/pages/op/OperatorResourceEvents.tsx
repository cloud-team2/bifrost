import { useState } from 'react'
import { Icon } from '../../components/Icon'
import { PageHead } from '../../components/blocks'
import { RESOURCE_EVENTS } from '../../data/mock'
import type { LogLevel } from '../../data/types'
import { cn } from '../../lib/format'

const META: Record<LogLevel, { tone: string; bar: string }> = {
  error: { tone: 'bg-rose-50 text-rose-700', bar: 'border-rose-400' },
  warning: { tone: 'bg-amber-50 text-amber-700', bar: 'border-amber-400' },
  info: { tone: 'bg-sky-50 text-sky-700', bar: 'border-sky-400' },
}
const TYPE_ICON: Record<string, 'server' | 'cpu' | 'users' | 'branch'> = {
  Broker: 'cpu',
  Topic: 'server',
  'Consumer Group': 'users',
  Connector: 'branch',
}

export function OperatorResourceEvents() {
  const [filter, setFilter] = useState<'all' | LogLevel>('all')
  const rows = RESOURCE_EVENTS.filter((e) => filter === 'all' || e.level === filter)

  return (
    <div className="px-6 py-5">
      <PageHead title="Resource Events" />

      <div className="mt-4 flex gap-1 border-b border-gray-200">
        {(['all', 'error', 'warning', 'info'] as const).map((t) => (
          <button
            key={t}
            onClick={() => setFilter(t)}
            className={cn(
              '-mb-px border-b-2 px-3 pb-2 text-[13px] font-medium capitalize transition-colors',
              filter === t ? 'border-brand-600 text-brand-700' : 'border-transparent text-gray-500 hover:text-gray-700',
            )}
          >
            {t}
          </button>
        ))}
      </div>

      <div className="mt-4 space-y-2">
        {rows.map((e) => (
          <div
            key={e.id}
            className={cn('flex items-center gap-3 rounded-lg border border-gray-200 border-l-4 bg-white px-4 py-3', META[e.level].bar)}
          >
            <span className="font-mono text-[12px] text-gray-400">{e.time}</span>
            <Icon name={TYPE_ICON[e.resourceType] ?? 'server'} size={15} className="text-gray-400" />
            <span className={cn('rounded px-1.5 py-0.5 text-[10px] font-bold uppercase', META[e.level].tone)}>
              {e.level}
            </span>
            <span className="text-[12px] text-gray-400">{e.resourceType}</span>
            <span className="font-mono text-[12.5px] font-medium text-gray-800">{e.resourceName}</span>
            <span className="flex-1 text-[13px] text-gray-600">{e.message}</span>
          </div>
        ))}
      </div>
    </div>
  )
}
