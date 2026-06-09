import { useMemo, useState } from 'react'
import { Icon } from '../components/Icon'
import { PageHead, StatusBadge } from '../components/blocks'
import { useApp } from '../store/AppStore'
import type { IncidentResponse, ResourceEventResponse, EventResponse } from '../lib/api'
import type { LogLevel } from '../data/types'
import { cn } from '../lib/format'

/* ---------------------------------------------------------------- constants */

const SEV_DOT: Record<string, string> = {
  CRITICAL: 'bg-rose-500',
  ERROR: 'bg-rose-500',
  WARN: 'bg-amber-500',
  WARNING: 'bg-amber-500',
  INFO: 'bg-sky-500',
}
const SEV_BORDER: Record<string, string> = {
  CRITICAL: 'border-l-rose-500',
  ERROR: 'border-l-rose-500',
  WARN: 'border-l-amber-500',
  WARNING: 'border-l-amber-500',
  INFO: 'border-l-sky-500',
}
const LEVEL_BADGE: Record<LogLevel, string> = {
  error: 'bg-rose-50 text-rose-700',
  warning: 'bg-amber-50 text-amber-700',
  info: 'bg-sky-50 text-sky-700',
}
const LEVEL_LEFT: Record<LogLevel, string> = {
  error: 'border-l-rose-400',
  warning: 'border-l-amber-400',
  info: 'border-l-sky-300',
}
const LEVEL: Record<EventResponse['level'], LogLevel> = { ERROR: 'error', WARN: 'warning', INFO: 'info' }

/* ---------------------------------------------------------------- unified event */

interface UnifiedEvent {
  id: string
  occurredAt: string
  level: LogLevel | null
  message: string
  source: 'pipeline' | 'resource'
  label: string
  pipelineId?: string | null
}

type SourceFilter = 'all' | UnifiedEvent['source']
const FILTER_LABELS: { key: SourceFilter; label: string }[] = [
  { key: 'all', label: '전체' },
  { key: 'pipeline', label: 'Pipeline' },
  { key: 'resource', label: 'Resource' },
]

function severityKey(severity: string): string {
  return severity.toUpperCase()
}

function severityDot(severity: string): string {
  return SEV_DOT[severityKey(severity)] ?? 'bg-sky-500'
}

function severityBorder(severity: string): string {
  return SEV_BORDER[severityKey(severity)] ?? 'border-l-sky-500'
}

function isOpenIncident(incident: IncidentResponse): boolean {
  return incident.status.toUpperCase() !== 'RESOLVED'
}

function fmtDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('ko-KR')
}

function eventMessage(e: ResourceEventResponse): string {
  return `[${e.resource}] ${e.detail}`
}

function buildEvents(events: EventResponse[], resourceEvents: ResourceEventResponse[]): UnifiedEvent[] {
  const pipeline: UnifiedEvent[] = events.map((e) => ({
    id: e.id,
    occurredAt: e.createdAt,
    level: LEVEL[e.level] ?? 'info',
    message: e.message,
    source: 'pipeline',
    label: e.type,
    pipelineId: e.pipelineId,
  }))
  const resources: UnifiedEvent[] = resourceEvents.map((e) => ({
    id: `${e.eventType}:${e.resource}:${e.occurredAt}`,
    occurredAt: e.occurredAt,
    level: null,
    message: eventMessage(e),
    source: 'resource',
    label: e.eventType,
  }))
  return [...pipeline, ...resources].sort((a, b) => b.occurredAt.localeCompare(a.occurredAt))
}

/* ================================================================ main view */

export function Alerts() {
  const app = useApp()
  const [selectedIncidentId, setSelectedIncidentId] = useState<string | null>(null)
  const [sourceFilter, setSourceFilter] = useState<SourceFilter>('all')
  const [levelFilter, setLevelFilter] = useState<'all' | LogLevel>('all')

  const incidents = app.incidents
  const activeIncidents = incidents.filter(isOpenIncident)
  const resolvedIncidents = incidents.filter((i) => !isOpenIncident(i))
  const selectedIncident = incidents.find((i) => i.id === selectedIncidentId) ?? null
  const allEvents = useMemo(() => buildEvents(app.events, app.resourceEvents), [app.events, app.resourceEvents])
  const initialLoading = app.monitoringLoading && incidents.length === 0 && allEvents.length === 0

  const filteredEvents = allEvents.filter(
    (e) =>
      (sourceFilter === 'all' || e.source === sourceFilter) &&
      (levelFilter === 'all' || e.level === levelFilter),
  )

  function toggleIncident(id: string) {
    setSelectedIncidentId((prev) => (prev === id ? null : id))
  }

  return (
    <div className="px-6 py-5">
      <PageHead
        title="알람"
        sub="백엔드 incidents/resource-events API 기준으로 표시합니다"
        actions={
          <button
            onClick={() => app.reloadMonitoring()}
            disabled={app.monitoringLoading}
            className="rounded-md border border-gray-300 px-3 py-1.5 text-[12.5px] font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
          >
            새로고침
          </button>
        }
      />

      {app.monitoringError ? (
        <div className="mt-4 rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-[13px] text-rose-700">
          {app.monitoringError}
        </div>
      ) : initialLoading ? (
        <div className="mt-4 rounded-xl border border-gray-200 bg-white py-12 text-center text-[13px] text-gray-400">
          모니터링 데이터를 불러오는 중…
        </div>
      ) : (
        <div className="mt-4 flex min-h-0 gap-4">
          <div className="min-w-0 flex-1 space-y-3">
            {activeIncidents.length > 0 ? (
              <div className="space-y-2">
                <div className="flex items-center gap-2">
                  <span className="text-[11px] font-semibold uppercase tracking-wide text-gray-400">
                    활성 인시던트
                  </span>
                  <span className="flex h-4 min-w-4 items-center justify-center rounded-full bg-rose-500 px-1 text-[10px] font-bold text-white">
                    {activeIncidents.length}
                  </span>
                </div>
                {activeIncidents.map((inc) => (
                  <IncidentBanner
                    key={inc.id}
                    incident={inc}
                    selected={selectedIncidentId === inc.id}
                    onClick={() => toggleIncident(inc.id)}
                  />
                ))}
              </div>
            ) : (
              <div className="rounded-xl border border-gray-200 bg-white py-8 text-center text-[13px] text-emerald-600">
                활성 인시던트가 없습니다
              </div>
            )}

            <div>
              <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
                <div className="flex gap-1">
                  {FILTER_LABELS.map(({ key, label }) => (
                    <button
                      key={key}
                      onClick={() => setSourceFilter(key)}
                      className={cn(
                        'rounded-full px-2.5 py-1 text-[12px] font-medium transition-colors',
                        sourceFilter === key
                          ? 'bg-brand-600 text-white'
                          : 'bg-gray-100 text-gray-500 hover:bg-gray-200',
                      )}
                    >
                      {label}
                    </button>
                  ))}
                </div>
                <div className="flex gap-1">
                  {(['all', 'error', 'warning', 'info'] as const).map((l) => (
                    <button
                      key={l}
                      onClick={() => setLevelFilter(l)}
                      className={cn(
                        'rounded-full px-2.5 py-1 text-[11.5px] font-medium capitalize transition-colors',
                        levelFilter === l
                          ? 'bg-gray-800 text-white'
                          : 'bg-gray-100 text-gray-500 hover:bg-gray-200',
                      )}
                    >
                      {l === 'all' ? '전체' : l}
                    </button>
                  ))}
                </div>
              </div>

              <div className="overflow-hidden rounded-xl border border-gray-200 bg-white">
                {filteredEvents.length === 0 ? (
                  <div className="py-12 text-center text-[13px] text-gray-400">이벤트가 없습니다</div>
                ) : (
                  filteredEvents.map((event, i) => (
                    <EventRow key={event.id} event={event} isLast={i === filteredEvents.length - 1} />
                  ))
                )}
              </div>

              {resolvedIncidents.length > 0 && (
                <p className="mt-2 text-[11.5px] text-gray-400">
                  해결된 인시던트 {resolvedIncidents.length}건 —{' '}
                  {resolvedIncidents.map((inc, i) => (
                    <button
                      key={inc.id}
                      onClick={() => toggleIncident(inc.id)}
                      className="font-medium text-gray-500 hover:text-gray-700 hover:underline"
                    >
                      {inc.title}{i < resolvedIncidents.length - 1 ? ', ' : ''}
                    </button>
                  ))}
                </p>
              )}
            </div>
          </div>

          {selectedIncident && (
            <div className="w-[380px] shrink-0 self-start overflow-hidden rounded-xl border border-gray-200 bg-white">
              <IncidentPanel incident={selectedIncident} onClose={() => setSelectedIncidentId(null)} />
            </div>
          )}
        </div>
      )}
    </div>
  )
}

/* ---------------------------------------------------------------- IncidentBanner */

function IncidentBanner({
  incident,
  selected,
  onClick,
}: {
  incident: IncidentResponse
  selected: boolean
  onClick: () => void
}) {
  return (
    <button
      onClick={onClick}
      className={cn(
        'w-full rounded-xl border border-gray-200 bg-white p-4 text-left transition-colors hover:border-gray-300',
        'border-l-4',
        severityBorder(incident.severity),
        selected && 'ring-1 ring-brand-200 bg-brand-50/20',
      )}
    >
      <div className="flex items-center gap-2.5">
        <span className={cn('h-2 w-2 shrink-0 rounded-full', severityDot(incident.severity))} />
        <span className="flex-1 text-[13.5px] font-semibold text-gray-900">{incident.title}</span>
        <StatusBadge status={incident.status} />
      </div>
      <div className="mt-1.5 flex items-center gap-3 text-[12px] text-gray-500">
        <span className="font-mono">{fmtDateTime(incident.openedAt)}</span>
        <span>·</span>
        <span>{incident.sourceType ?? 'source'}:{' '}{incident.groupingKey}</span>
        <span className="ml-auto text-[11px] text-gray-400">
          {selected ? '닫기 ↑' : '상세 보기 →'}
        </span>
      </div>
    </button>
  )
}

/* ---------------------------------------------------------------- EventRow */

function EventRow({ event, isLast }: { event: UnifiedEvent; isLast: boolean }) {
  const left = event.level ? LEVEL_LEFT[event.level] : 'border-l-gray-300'
  return (
    <div className={cn('flex items-start gap-3 border-l-2 px-4 py-2.5', !isLast && 'border-b border-gray-50', left)}>
      <span className="w-[118px] shrink-0 pt-px font-mono text-[11px] text-gray-400">{fmtDateTime(event.occurredAt)}</span>
      {event.level ? (
        <span className={cn('shrink-0 rounded px-1.5 py-0.5 text-[9.5px] font-bold uppercase', LEVEL_BADGE[event.level])}>
          {event.level}
        </span>
      ) : (
        <span className="shrink-0 rounded bg-gray-100 px-1.5 py-0.5 text-[9.5px] font-bold uppercase text-gray-500">
          event
        </span>
      )}
      <span className="shrink-0 rounded bg-gray-100 px-1.5 py-0.5 text-[10px] font-medium text-gray-500">
        {event.label}
      </span>
      <span className="flex-1 text-[12.5px] text-gray-700">{event.message}</span>
    </div>
  )
}

/* ---------------------------------------------------------------- IncidentPanel */

function IncidentPanel({ incident, onClose }: { incident: IncidentResponse; onClose: () => void }) {
  return (
    <div className="flex max-h-[calc(100vh-140px)] flex-col overflow-hidden">
      <div className="flex shrink-0 items-start gap-2.5 border-b border-gray-100 px-4 py-3">
        <span className={cn('mt-1 h-2 w-2 shrink-0 rounded-full', severityDot(incident.severity))} />
        <span className="flex-1 text-[13px] font-semibold leading-snug text-gray-900">{incident.title}</span>
        <button onClick={onClose} className="shrink-0 text-gray-400 hover:text-gray-600">
          <Icon name="x" size={15} />
        </button>
      </div>

      <div className="flex-1 overflow-auto divide-y divide-gray-100">
        <div className="space-y-1.5 px-4 py-3">
          <div className="flex items-center gap-2">
            <StatusBadge status={incident.status} />
            <StatusBadge status={incident.severity} />
          </div>
          <Meta label="Opened" value={fmtDateTime(incident.openedAt)} />
          <Meta label="Resolved" value={fmtDateTime(incident.resolvedAt)} />
        </div>

        <div className="space-y-2 px-4 py-3 text-[12.5px]">
          <Meta label="Tenant" value={incident.tenantId} mono />
          <Meta label="Grouping key" value={incident.groupingKey} mono />
          <Meta label="Source type" value={incident.sourceType ?? '—'} />
          <Meta label="Source id" value={incident.sourceId ?? '—'} mono />
        </div>

        <div className="px-4 py-3">
          <div className="mb-1.5 text-[10.5px] font-bold uppercase tracking-wide text-gray-400">RCA</div>
          <p className="text-[12.5px] leading-relaxed text-gray-600">
            {incident.rca?.trim() || '아직 RCA가 기록되지 않았습니다.'}
          </p>
        </div>
      </div>
    </div>
  )
}

function Meta({ label, value, mono = false }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="flex gap-2 text-[12px]">
      <span className="w-24 shrink-0 text-gray-400">{label}</span>
      <span className={cn('min-w-0 flex-1 break-all text-gray-700', mono && 'font-mono text-[11.5px]')}>{value}</span>
    </div>
  )
}
