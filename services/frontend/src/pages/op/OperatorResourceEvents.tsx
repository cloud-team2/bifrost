import { useMemo, useState } from 'react'
import { Icon } from '../../components/Icon'
import { PageHead } from '../../components/blocks'
import { useApp } from '../../store/AppStore'
import { cn } from '../../lib/format'

const TYPE_ICON: Record<string, 'server' | 'cpu' | 'users' | 'branch'> = {
  PARTITION_REASSIGNMENT: 'branch',
  LEADER_ELECTION: 'cpu',
  REBALANCE: 'users',
}

function fmtDateTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('ko-KR')
}

export function OperatorResourceEvents() {
  const app = useApp()
  const [filter, setFilter] = useState('all')
  const eventTypes = useMemo(
    () => ['all', ...Array.from(new Set(app.resourceEvents.map((e) => e.eventType))).sort()],
    [app.resourceEvents],
  )
  const rows = app.resourceEvents.filter((e) => filter === 'all' || e.eventType === filter)

  return (
    <div className="px-6 py-5">
      <PageHead
        title="Resource Events"
        actions={
          <button
            onClick={() => app.reloadMonitoring()}
            disabled={app.monitoringLoading}
            className="rounded-md border border-gray-300 px-3 py-1.5 text-[12.5px] font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
          >
            Refresh
          </button>
        }
      />

      {app.monitoringError ? (
        <div className="mt-4 rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-[13px] text-rose-700">
          {app.monitoringError}
        </div>
      ) : app.monitoringLoading && rows.length === 0 ? (
        <div className="mt-4 rounded-xl border border-gray-200 bg-white py-12 text-center text-[13px] text-gray-400">
          리소스 이벤트를 불러오는 중…
        </div>
      ) : (
        <>
          <div className="mt-4 flex gap-1 border-b border-gray-200">
            {eventTypes.map((t) => (
              <button
                key={t}
                onClick={() => setFilter(t)}
                className={cn(
                  '-mb-px border-b-2 px-3 pb-2 text-[13px] font-medium transition-colors',
                  filter === t ? 'border-brand-600 text-brand-700' : 'border-transparent text-gray-500 hover:text-gray-700',
                )}
              >
                {t === 'all' ? '전체' : t}
              </button>
            ))}
          </div>

          <div className="mt-4 space-y-2">
            {rows.length === 0 ? (
              <div className="rounded-xl border border-gray-200 bg-white py-12 text-center text-[13px] text-gray-400">
                리소스 이벤트가 없습니다
              </div>
            ) : (
              rows.map((e) => (
                <div
                  key={`${e.eventType}:${e.resource}:${e.occurredAt}`}
                  className="flex items-center gap-3 rounded-lg border border-gray-200 border-l-4 border-l-gray-300 bg-white px-4 py-3"
                >
                  <span className="w-[118px] shrink-0 font-mono text-[12px] text-gray-400">{fmtDateTime(e.occurredAt)}</span>
                  <Icon name={TYPE_ICON[e.eventType] ?? 'server'} size={15} className="text-gray-400" />
                  <span className="rounded bg-gray-100 px-1.5 py-0.5 text-[10px] font-bold uppercase text-gray-600">
                    {e.eventType}
                  </span>
                  <span className="font-mono text-[12.5px] font-medium text-gray-800">{e.resource}</span>
                  <span className="flex-1 text-[13px] text-gray-600">{e.detail}</span>
                </div>
              ))
            )}
          </div>
        </>
      )}
    </div>
  )
}
