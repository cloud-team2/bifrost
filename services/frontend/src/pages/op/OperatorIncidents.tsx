import { Icon } from '../../components/Icon'
import { PageHead, Panel, StatusBadge } from '../../components/blocks'
import { useApp } from '../../store/AppStore'
import type { IncidentResponse } from '../../lib/api'
import { cn } from '../../lib/format'

const SEV: Record<string, string> = {
  CRITICAL: 'bg-rose-500',
  ERROR: 'bg-rose-500',
  WARN: 'bg-amber-500',
  WARNING: 'bg-amber-500',
  INFO: 'bg-sky-500',
}

function severityDot(severity: string): string {
  return SEV[severity.toUpperCase()] ?? 'bg-sky-500'
}

function fmtDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('ko-KR')
}

export function OperatorIncidents() {
  const app = useApp()
  const visibleIncidents = app.incidents
  const selected = visibleIncidents.find((i) => i.id === app.opSelectedIncidentId) ?? visibleIncidents[0]

  return (
    <div className="px-6 py-5">
      <PageHead
        title="Incidents"
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
      ) : app.monitoringLoading && visibleIncidents.length === 0 ? (
        <div className="mt-4 rounded-xl border border-gray-200 bg-white py-12 text-center text-[13px] text-gray-400">
          인시던트를 불러오는 중…
        </div>
      ) : visibleIncidents.length === 0 ? (
        <div className="mt-4 rounded-xl border border-gray-200 bg-white py-12 text-center text-[13px] text-emerald-600">
          인시던트가 없습니다
        </div>
      ) : (
        <div className="mt-4 grid grid-cols-[320px_1fr] gap-4">
          <div className="space-y-2">
            {visibleIncidents.map((inc) => (
              <button
                key={inc.id}
                onClick={() => app.openIncident(inc.id)}
                className={cn(
                  'w-full rounded-xl border bg-white p-3.5 text-left transition-colors',
                  selected?.id === inc.id ? 'border-brand-400 ring-1 ring-brand-200' : 'border-gray-200 hover:border-gray-300',
                )}
              >
                <div className="flex items-center gap-2">
                  <span className={cn('h-2 w-2 rounded-full', severityDot(inc.severity))} />
                  <span className="flex-1 truncate text-[13px] font-semibold text-gray-900">{inc.title}</span>
                </div>
                <div className="mt-2 flex items-center justify-between">
                  <span className="font-mono text-[11px] text-gray-400">{fmtDateTime(inc.openedAt)}</span>
                  <StatusBadge status={inc.status} />
                </div>
              </button>
            ))}
          </div>

          {selected && <IncidentDetail incident={selected} />}
        </div>
      )}
    </div>
  )
}

function IncidentDetail({ incident }: { incident: IncidentResponse }) {
  return (
    <div className="space-y-4">
      <div className="rounded-xl border border-gray-200 bg-white p-5">
        <div className="flex items-center gap-2.5">
          <span className={cn('h-2.5 w-2.5 rounded-full', severityDot(incident.severity))} />
          <h2 className="text-[16px] font-semibold text-gray-900">{incident.title}</h2>
          <StatusBadge status={incident.status} />
          <span className="ml-auto font-mono text-[11px] text-gray-400">opened {fmtDateTime(incident.openedAt)}</span>
        </div>

        <div className="mt-3 grid grid-cols-2 gap-3 rounded-lg border border-gray-200 bg-gray-50 px-3.5 py-2.5 text-[12px]">
          <Info label="Severity" value={incident.severity} />
          <Info label="Resolved" value={fmtDateTime(incident.resolvedAt)} />
          <Info label="Source type" value={incident.sourceType ?? '—'} />
          <Info label="Source id" value={incident.sourceId ?? '—'} mono />
          <Info label="Grouping key" value={incident.groupingKey} mono wide />
          <Info label="Tenant" value={incident.tenantId} mono wide />
        </div>

        <div className="mt-3">
          <div className="text-[11px] font-bold uppercase tracking-wide text-gray-400">Root cause</div>
          <p className="mt-1 text-[13px] leading-relaxed text-gray-600">
            {incident.rca?.trim() || 'RCA가 아직 기록되지 않았습니다.'}
          </p>
        </div>
      </div>

      <Panel title="Action timeline">
        <div className="px-4 py-6 text-center text-[12.5px] text-gray-400">
          백엔드 IncidentResponse에는 action log 필드가 없어 이번 실데이터 화면에서는 표시하지 않습니다.
        </div>
      </Panel>
    </div>
  )
}

function Info({ label, value, mono = false, wide = false }: { label: string; value: string; mono?: boolean; wide?: boolean }) {
  return (
    <div className={wide ? 'col-span-2' : undefined}>
      <div className="text-[10.5px] font-semibold uppercase tracking-wide text-gray-400">{label}</div>
      <div className={cn('mt-0.5 break-all text-gray-700', mono && 'font-mono text-[11.5px]')}>{value}</div>
    </div>
  )
}
