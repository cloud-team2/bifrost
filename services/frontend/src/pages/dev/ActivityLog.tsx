import { useState } from 'react'
import { Icon } from '../../components/Icon'
import { PageHead } from '../../components/blocks'
import { useApp } from '../../store/AppStore'
import { ACTIVITY_EVENTS } from '../../data/mock'
import { pipelineLabel } from '../../data/helpers'
import type { LogLevel } from '../../data/types'
import { cn } from '../../lib/format'

const LEVEL_META: Record<LogLevel, { tone: string; bar: string; icon: 'alert' | 'info' }> = {
  error: { tone: 'bg-rose-50 text-rose-700', bar: 'border-rose-400', icon: 'alert' },
  warning: { tone: 'bg-amber-50 text-amber-700', bar: 'border-amber-400', icon: 'alert' },
  info: { tone: 'bg-sky-50 text-sky-700', bar: 'border-sky-400', icon: 'info' },
}

export function ActivityLog() {
  const app = useApp()
  const [filter, setFilter] = useState<'all' | LogLevel>('all')
  const rows = ACTIVITY_EVENTS.filter((e) => filter === 'all' || e.level === filter)

  return (
    <div className="px-6 py-5">
      <PageHead title="이벤트 로그" />

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
        {rows.map((e) => {
          const meta = LEVEL_META[e.level]
          const pipe = e.pipelineId ? app.edges.find((x) => x.id === e.pipelineId) : null
          return (
            <div
              key={e.id}
              className={cn('flex items-center gap-3 rounded-lg border border-gray-200 border-l-4 bg-white px-4 py-3', meta.bar)}
            >
              <span className="font-mono text-[12px] text-gray-400">{e.time}</span>
              <span className={cn('rounded px-1.5 py-0.5 text-[10px] font-bold uppercase', meta.tone)}>
                {e.level}
              </span>
              <span className="flex-1 text-[13px] text-gray-700">{e.message}</span>
              {pipe && (
                <button
                  onClick={() => app.openPipeline(pipe.id)}
                  className="flex items-center gap-1 rounded-md border border-gray-200 px-2 py-1 text-[11.5px] font-medium text-gray-600 hover:bg-gray-50"
                >
                  {pipelineLabel(pipe)}
                  <Icon name="arrow-right" size={12} />
                </button>
              )}
            </div>
          )
        })}
        {rows.length === 0 && (
          <div className="rounded-xl border border-dashed border-gray-200 py-14 text-center text-sm text-gray-400">
            해당 레벨의 이벤트가 없습니다.
          </div>
        )}
      </div>
    </div>
  )
}
