import { useEffect, useMemo, useState } from 'react'
import { Icon } from '../components/Icon'
import { PageHead, StatusBadge } from '../components/blocks'
import { useApp } from '../store/AppStore'
import { pipelineLabel } from '../data/helpers'
import type { IncidentResponse, ResourceEventResponse, EventResponse } from '../lib/api'
import type { Edge } from '../data/types'
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
  resourceKey?: string | null
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

function fmtTime(value: string | null | undefined): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false })
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
  const resources: UnifiedEvent[] = resourceEvents.map((e, index) => ({
    id: `${e.eventType}:${e.resource}:${e.occurredAt}:${index}`,
    occurredAt: e.occurredAt,
    level: null,
    message: eventMessage(e),
    source: 'resource',
    label: e.eventType,
    resourceKey: e.resource,
  }))
  return [...pipeline, ...resources].sort((a, b) => b.occurredAt.localeCompare(a.occurredAt))
}

function isPipelineSource(incident: IncidentResponse): boolean {
  return incident.sourceType?.toLowerCase() === 'pipeline' && Boolean(incident.sourceId?.trim())
}

function incidentPipeline(incident: IncidentResponse, edges: Edge[]): Edge | null {
  if (!isPipelineSource(incident) || !incident.sourceId) return null
  return edges.find((edge) => edge.id === incident.sourceId) ?? null
}

function eventSortAsc(a: UnifiedEvent, b: UnifiedEvent): number {
  return a.occurredAt.localeCompare(b.occurredAt)
}

function isPipelineTopicResource(resourceKey: string | null | undefined, pipeline: Edge | null): boolean {
  const topic = pipeline?.topic.trim()
  if (!resourceKey || !topic) return false
  if (resourceKey === topic) return true

  const partitionPrefix = `${topic}-`
  if (!resourceKey.startsWith(partitionPrefix)) return false
  return /^\d+$/.test(resourceKey.slice(partitionPrefix.length))
}

function relatedEventsForIncident(incident: IncidentResponse, allEvents: UnifiedEvent[], edges: Edge[]): UnifiedEvent[] {
  const sourceId = incident.sourceId?.trim()
  if (!sourceId) return []

  const pipeline = incidentPipeline(incident, edges)
  const pipelineId = pipeline?.id ?? (isPipelineSource(incident) ? sourceId : null)

  return allEvents
    .filter((event) => {
      if (event.pipelineId && event.pipelineId === sourceId) return true
      if (pipeline && event.pipelineId === pipeline.id) return true
      if (event.source !== 'resource') return false

      return event.resourceKey === sourceId || (!!pipelineId && event.resourceKey === pipelineId) ||
        isPipelineTopicResource(event.resourceKey, pipeline)
    })
    .sort(eventSortAsc)
}

/* ================================================================ main view */

export function Alerts() {
  const app = useApp()
  const [selectedIncidentId, setSelectedIncidentId] = useState<string | null>(() => app.opSelectedIncidentId)
  const [sourceFilter, setSourceFilter] = useState<SourceFilter>('all')
  const [levelFilter, setLevelFilter] = useState<'all' | LogLevel>('all')

  const incidents = app.incidents
  const activeIncidents = incidents.filter(isOpenIncident)
  const resolvedIncidents = incidents.filter((i) => !isOpenIncident(i))
  const selectedIncident = incidents.find((i) => i.id === selectedIncidentId) ?? null
  const allEvents = useMemo(() => buildEvents(app.events, app.resourceEvents), [app.events, app.resourceEvents])
  const selectedRelatedEvents = useMemo(
    () => (selectedIncident ? relatedEventsForIncident(selectedIncident, allEvents, app.edges) : []),
    [selectedIncident, allEvents, app.edges],
  )
  const selectedRelatedEventIds = useMemo(
    () => new Set(selectedRelatedEvents.map((event) => event.id)),
    [selectedRelatedEvents],
  )
  const initialLoading = app.monitoringLoading && incidents.length === 0 && allEvents.length === 0

  useEffect(() => {
    if (!app.opSelectedIncidentId) return
    setSelectedIncidentId(app.opSelectedIncidentId)
    app.clearOpSelectedIncident()
  }, [app.opSelectedIncidentId])

  const filteredEvents = useMemo(
    () =>
      allEvents.filter(
        (e) =>
          (sourceFilter === 'all' || e.source === sourceFilter) &&
          (levelFilter === 'all' || e.level === levelFilter),
      ),
    [allEvents, sourceFilter, levelFilter],
  )
  const visibleRelatedEventIds = useMemo(
    () => new Set(filteredEvents.filter((event) => selectedRelatedEventIds.has(event.id)).map((event) => event.id)),
    [filteredEvents, selectedRelatedEventIds],
  )

  function toggleIncident(id: string) {
    setSelectedIncidentId((prev) => (prev === id ? null : id))
  }

  return (
    <div className="px-6 py-5">
      <PageHead
        title="알람"
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
                    relatedEventCount={relatedEventsForIncident(inc, allEvents, app.edges).length}
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
                    <EventRow
                      key={event.id}
                      event={event}
                      relatedEventIds={visibleRelatedEventIds}
                      isLast={i === filteredEvents.length - 1}
                    />
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
              <IncidentPanel
                incident={selectedIncident}
                relatedEvents={selectedRelatedEvents}
                onClose={() => setSelectedIncidentId(null)}
              />
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
  relatedEventCount,
  selected,
  onClick,
}: {
  incident: IncidentResponse
  relatedEventCount: number
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
        <span>관련 이벤트 {relatedEventCount}건</span>
        <span className="ml-auto text-[11px] text-gray-400">
          {selected ? '닫기 ↑' : '상세 보기 →'}
        </span>
      </div>
    </button>
  )
}

/* ---------------------------------------------------------------- EventRow */

function EventRow({
  event,
  relatedEventIds,
  isLast,
}: {
  event: UnifiedEvent
  relatedEventIds: Set<string>
  isLast: boolean
}) {
  const left = event.level ? LEVEL_LEFT[event.level] : 'border-l-gray-300'
  const hasSelection = relatedEventIds.size > 0
  const isHighlighted = hasSelection && relatedEventIds.has(event.id)
  const isDimmed = hasSelection && !isHighlighted
  return (
    <div
      className={cn(
        'flex items-start gap-3 border-l-2 px-4 py-2.5',
        !isLast && 'border-b border-gray-50',
        left,
        isHighlighted && 'bg-brand-50/25',
        isDimmed && 'opacity-40',
      )}
    >
      <span className="w-[52px] shrink-0 pt-px font-mono text-[11px] text-gray-400">{fmtTime(event.occurredAt)}</span>
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

function IncidentPanel({
  incident,
  relatedEvents,
  onClose,
}: {
  incident: IncidentResponse
  relatedEvents: UnifiedEvent[]
  onClose: () => void
}) {
  const app = useApp()
  const pipeline = incidentPipeline(incident, app.edges)

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
            <span className="font-mono text-[11px] text-gray-400">{fmtDateTime(incident.openedAt)}</span>
          </div>
          {isPipelineSource(incident) && (
            <div className="text-[12px] text-gray-500">
              영향 Pipeline:{' '}
              {pipeline ? (
                <button
                  onClick={() => app.openPipeline(pipeline.id)}
                  className="inline-flex items-center gap-1 font-medium text-brand-600 hover:underline"
                >
                  {pipelineLabel(pipeline)}
                  <Icon name="arrow-right" size={11} />
                </button>
              ) : (
                <span className="text-gray-400">현재 프로젝트에서 찾을 수 없습니다</span>
              )}
            </div>
          )}
        </div>

        <div className="px-4 py-3">
          <div className="mb-1.5 text-[10.5px] font-bold uppercase tracking-wide text-gray-400">원인 분석</div>
          <p className="text-[12.5px] leading-relaxed text-gray-600">
            {incident.rca?.trim() || '아직 RCA가 기록되지 않았습니다.'}
          </p>
        </div>

        <div className="px-4 py-3">
          <div className="mb-2 text-[10.5px] font-bold uppercase tracking-wide text-gray-400">
            관련 이벤트 {relatedEvents.length}건
          </div>
          {relatedEvents.length > 0 ? (
            <div>
              {relatedEvents.map((event, i) => (
                <div key={event.id} className="flex gap-2.5 pb-2.5 last:pb-0">
                  <div className="flex flex-col items-center pt-1">
                    <span
                      className={cn(
                        'h-2 w-2 shrink-0 rounded-full',
                        event.level === 'error'
                          ? 'bg-rose-500'
                          : event.level === 'warning'
                            ? 'bg-amber-400'
                            : event.level === 'info'
                              ? 'bg-sky-400'
                              : 'bg-gray-300',
                      )}
                    />
                    {i < relatedEvents.length - 1 && <span className="my-0.5 w-px flex-1 bg-gray-100" />}
                  </div>
                  <div className="-mt-0.5 min-w-0 flex-1">
                    <div className="text-[12px] text-gray-700">{event.message}</div>
                    <div className="mt-0.5 font-mono text-[10.5px] text-gray-400">
                      {fmtDateTime(event.occurredAt)}
                    </div>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <div className="rounded-lg border border-dashed border-gray-200 py-6 text-center text-[12px] text-gray-400">
              상관된 이벤트가 없습니다
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
