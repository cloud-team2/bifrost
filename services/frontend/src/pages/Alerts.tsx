import { useState } from 'react'
import { Icon } from '../components/Icon'
import { PageHead, Panel, StatusBadge } from '../components/blocks'
import { useToast } from '../components/Toast'
import { useApp } from '../store/AppStore'
import { ACTIVITY_EVENTS, RESOURCE_EVENTS } from '../data/mock'
import { pipelineLabel } from '../data/helpers'
import type { IncidentReport, LogLevel, Severity } from '../data/types'
import { cn } from '../lib/format'

/* ---------------------------------------------------------------- constants */

const SEV_DOT: Record<Severity, string> = {
  critical: 'bg-rose-500',
  warning:  'bg-amber-500',
  info:     'bg-sky-500',
}
const SEV_BORDER: Record<Severity, string> = {
  critical: 'border-l-rose-500',
  warning:  'border-l-amber-500',
  info:     'border-l-sky-500',
}
const LEVEL_BADGE: Record<LogLevel, string> = {
  error:   'bg-rose-50 text-rose-700',
  warning: 'bg-amber-50 text-amber-700',
  info:    'bg-sky-50 text-sky-700',
}
const LEVEL_LEFT: Record<LogLevel, string> = {
  error:   'border-l-rose-400',
  warning: 'border-l-amber-400',
  info:    'border-l-sky-300',
}
const RISK: Record<string, string> = {
  low:    'bg-emerald-50 text-emerald-700',
  medium: 'bg-amber-50 text-amber-700',
  high:   'bg-rose-50 text-rose-700',
}

/* ---------------------------------------------------------------- unified event */

interface UnifiedEvent {
  id: string
  time: string
  level: LogLevel
  message: string
  source: 'pipeline' | 'connector' | 'consumer' | 'broker' | 'topic'
  label: string
  pipelineId?: string
  incidentId?: string
}

const SOURCE_LABEL: Record<string, UnifiedEvent['source']> = {
  'Consumer Group': 'consumer',
  Connector:        'connector',
  Broker:           'broker',
  Topic:            'topic',
}

function buildAllEvents(): UnifiedEvent[] {
  const pipeline: UnifiedEvent[] = ACTIVITY_EVENTS.map((e) => ({
    id:         e.id,
    time:       e.time,
    level:      e.level,
    message:    e.message,
    source:     'pipeline',
    label:      'Pipeline',
    pipelineId: e.pipelineId,
    incidentId: e.incidentId,
  }))
  const resource: UnifiedEvent[] = RESOURCE_EVENTS.map((e) => ({
    id:         e.id,
    time:       e.time,
    level:      e.level,
    message:    `[${e.resourceName}] ${e.message}`,
    source:     SOURCE_LABEL[e.resourceType] ?? 'topic',
    label:      e.resourceType,
    incidentId: e.incidentId,
  }))
  return [...pipeline, ...resource].sort((a, b) => b.time.localeCompare(a.time))
}

const ALL_EVENTS = buildAllEvents()

type SourceFilter = 'all' | UnifiedEvent['source']
const FILTER_LABELS: { key: SourceFilter; label: string }[] = [
  { key: 'all',       label: '전체' },
  { key: 'pipeline',  label: 'Pipeline' },
  { key: 'connector', label: 'Connector' },
  { key: 'consumer',  label: 'Consumer' },
  { key: 'broker',    label: 'Broker' },
  { key: 'topic',     label: 'Topic' },
]

/* ================================================================ main view */

export function Alerts() {
  const app   = useApp()
  const [selectedIncidentId, setSelectedIncidentId] = useState<string | null>(null)
  const [sourceFilter, setSourceFilter]             = useState<SourceFilter>('all')
  const [levelFilter, setLevelFilter]               = useState<'all' | LogLevel>('all')

  const pipelineIds = app.currentProject?.pipelineIds ?? []
  const incidents = app.incidents.filter(
    (i) => i.affectedPipelines.length === 0 || i.affectedPipelines.some((pid) => pipelineIds.includes(pid)),
  )
  const activeIncidents   = incidents.filter((i) => i.status !== 'resolved')
  const resolvedIncidents = incidents.filter((i) => i.status === 'resolved')
  const selectedIncident  = incidents.find((i) => i.id === selectedIncidentId) ?? null

  const filteredEvents = ALL_EVENTS.filter(
    (e) =>
      (sourceFilter === 'all' || e.source === sourceFilter) &&
      (levelFilter  === 'all' || e.level  === levelFilter),
  )

  function toggleIncident(id: string) {
    setSelectedIncidentId((prev) => (prev === id ? null : id))
  }

  return (
    <div className="px-6 py-5">
      <PageHead title="알람" />

      <div className="mt-4 flex min-h-0 gap-4">

        {/* ── left: banners + event stream ─────────────────────────── */}
        <div className="min-w-0 flex-1 space-y-3">

          {/* active incident banners */}
          {activeIncidents.length > 0 && (
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
          )}

          {/* event stream */}
          <div>
            {/* filter toolbar */}
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
                    selectedIncidentId={selectedIncidentId}
                    onSelectIncident={toggleIncident}
                    isLast={i === filteredEvents.length - 1}
                  />
                ))
              )}
            </div>

            {/* resolved incidents count */}
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

        {/* ── right: incident detail panel ─────────────────────────── */}
        {selectedIncident && (
          <div className="w-[380px] shrink-0 self-start overflow-hidden rounded-xl border border-gray-200 bg-white">
            <IncidentPanel
              incident={selectedIncident}
              allEvents={ALL_EVENTS}
              onClose={() => setSelectedIncidentId(null)}
            />
          </div>
        )}
      </div>
    </div>
  )
}

/* ---------------------------------------------------------------- IncidentBanner */

function IncidentBanner({
  incident, selected, onClick,
}: {
  incident: IncidentReport; selected: boolean; onClick: () => void
}) {
  const relatedCount = 1 + incident.relatedEventIds.length
  return (
    <button
      onClick={onClick}
      className={cn(
        'w-full rounded-xl border border-gray-200 bg-white p-4 text-left transition-colors hover:border-gray-300',
        'border-l-4',
        SEV_BORDER[incident.severity],
        selected && 'ring-1 ring-brand-200 bg-brand-50/20',
      )}
    >
      <div className="flex items-center gap-2.5">
        <span className={cn('h-2 w-2 shrink-0 rounded-full', SEV_DOT[incident.severity])} />
        <span className="flex-1 text-[13.5px] font-semibold text-gray-900">{incident.title}</span>
        <StatusBadge status={incident.status} />
      </div>
      <div className="mt-1.5 flex items-center gap-3 text-[12px] text-gray-500">
        <span className="font-mono">{incident.createdAt}</span>
        <span>·</span>
        <span>관련 이벤트 {relatedCount}건</span>
        {incident.affectedTeams.length > 0 && (
          <>
            <span>·</span>
            <span>{incident.affectedTeams[0]}</span>
          </>
        )}
        <span className="ml-auto text-[11px] text-gray-400">
          {selected ? '닫기 ↑' : '상세 보기 →'}
        </span>
      </div>
    </button>
  )
}

/* ---------------------------------------------------------------- EventRow */

function EventRow({
  event, selectedIncidentId, onSelectIncident, isLast,
}: {
  event: UnifiedEvent
  selectedIncidentId: string | null
  onSelectIncident: (id: string) => void
  isLast: boolean
}) {
  const isLinked      = !!event.incidentId
  const isHighlighted = isLinked && event.incidentId === selectedIncidentId
  const isDimmed      = !!selectedIncidentId && event.incidentId !== selectedIncidentId

  return (
    <div
      className={cn(
        'flex items-start gap-3 border-l-2 px-4 py-2.5',
        !isLast && 'border-b border-gray-50',
        isLinked ? LEVEL_LEFT[event.level] : 'border-l-transparent',
        isHighlighted && 'bg-brand-50/25',
        isDimmed && 'opacity-40',
      )}
    >
      <span className="w-[52px] shrink-0 pt-px font-mono text-[11px] text-gray-400">{event.time}</span>
      <span className={cn('shrink-0 rounded px-1.5 py-0.5 text-[9.5px] font-bold uppercase', LEVEL_BADGE[event.level])}>
        {event.level}
      </span>
      <span className="shrink-0 rounded bg-gray-100 px-1.5 py-0.5 text-[10px] font-medium text-gray-500">
        {event.label}
      </span>
      <span className="flex-1 text-[12.5px] text-gray-700">{event.message}</span>
      {event.incidentId && (
        <button
          onClick={() => onSelectIncident(event.incidentId!)}
          className={cn(
            'shrink-0 rounded px-1.5 py-0.5 font-mono text-[10.5px] font-medium transition-colors',
            isHighlighted
              ? 'bg-brand-100 text-brand-700'
              : 'bg-gray-100 text-gray-500 hover:bg-gray-200',
          )}
        >
          {event.incidentId} ↗
        </button>
      )}
    </div>
  )
}

/* ---------------------------------------------------------------- IncidentPanel */

function IncidentPanel({
  incident, allEvents, onClose,
}: {
  incident: IncidentReport; allEvents: UnifiedEvent[]; onClose: () => void
}) {
  const app   = useApp()
  const toast = useToast()

  const relatedIds    = new Set([incident.triggerEventId, ...incident.relatedEventIds])
  const relatedEvents = allEvents
    .filter((e) => relatedIds.has(e.id))
    .sort((a, b) => a.time.localeCompare(b.time))

  return (
    <div className="flex max-h-[calc(100vh-140px)] flex-col overflow-hidden">
      {/* header */}
      <div className="flex shrink-0 items-start gap-2.5 border-b border-gray-100 px-4 py-3">
        <span className={cn('mt-1 h-2 w-2 shrink-0 rounded-full', SEV_DOT[incident.severity])} />
        <span className="flex-1 text-[13px] font-semibold leading-snug text-gray-900">{incident.title}</span>
        <button onClick={onClose} className="shrink-0 text-gray-400 hover:text-gray-600">
          <Icon name="x" size={15} />
        </button>
      </div>

      <div className="flex-1 overflow-auto divide-y divide-gray-100">

        {/* status + meta */}
        <div className="px-4 py-3 space-y-1.5">
          <div className="flex items-center gap-2">
            <StatusBadge status={incident.status} />
            <span className="font-mono text-[11px] text-gray-400">{incident.createdAt}</span>
          </div>
          {incident.affectedPipelines.length > 0 && (
            <div className="text-[12px] text-gray-500">
              영향 Pipeline:{' '}
              {incident.affectedPipelines.map((pid) => {
                const p = app.edges.find((e) => e.id === pid)
                return p ? (
                  <button
                    key={pid}
                    onClick={() => app.openPipeline(p.id)}
                    className="mr-1 font-medium text-brand-600 hover:underline"
                  >
                    {pipelineLabel(p)}
                  </button>
                ) : null
              })}
            </div>
          )}
          {incident.affectedTeams.length > 0 && (
            <div className="text-[12px] text-gray-500">
              담당팀:{' '}
              <span className="font-medium text-gray-700">{incident.affectedTeams.join(', ')}</span>
            </div>
          )}
        </div>

        {/* root cause */}
        <div className="px-4 py-3">
          <div className="mb-1.5 text-[10.5px] font-bold uppercase tracking-wide text-gray-400">원인 분석</div>
          <p className="text-[12.5px] leading-relaxed text-gray-600">{incident.rootCause}</p>
        </div>

        {/* related events timeline */}
        {relatedEvents.length > 0 && (
          <div className="px-4 py-3">
            <div className="mb-2 text-[10.5px] font-bold uppercase tracking-wide text-gray-400">
              관련 이벤트 {relatedEvents.length}건
            </div>
            <div>
              {relatedEvents.map((e, i) => (
                <div key={e.id} className="flex gap-2.5 pb-2.5 last:pb-0">
                  <div className="flex flex-col items-center pt-1">
                    <span
                      className={cn(
                        'h-2 w-2 shrink-0 rounded-full',
                        e.level === 'error' ? 'bg-rose-500' : e.level === 'warning' ? 'bg-amber-400' : 'bg-sky-400',
                      )}
                    />
                    {i < relatedEvents.length - 1 && (
                      <span className="my-0.5 w-px flex-1 bg-gray-100" />
                    )}
                  </div>
                  <div className="-mt-0.5">
                    <div className="text-[12px] text-gray-700">{e.message}</div>
                    <div className="mt-0.5 flex items-center gap-1.5 text-[10.5px] text-gray-400">
                      <span className="font-mono">{e.time}</span>
                      {e.id === incident.triggerEventId && (
                        <span className="rounded bg-rose-50 px-1 py-px text-[9.5px] font-bold text-rose-600">
                          트리거
                        </span>
                      )}
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* recommended actions */}
        {incident.aiActions.length > 0 && (
          <div className="px-4 py-3">
            <div className="mb-2 text-[10.5px] font-bold uppercase tracking-wide text-gray-400">권장 조치</div>
            <div className="space-y-2">
              {incident.aiActions.map((a) => (
                <div key={a.id} className="rounded-lg border border-gray-100 bg-gray-50 p-3">
                  <div className="flex items-start gap-2">
                    <div className="flex-1">
                      <div className="flex flex-wrap items-center gap-1.5">
                        <span className="text-[12.5px] font-medium text-gray-800">{a.label}</span>
                        <span className={cn('rounded px-1 py-px text-[9.5px] font-bold uppercase', RISK[a.risk])}>
                          {a.risk}
                        </span>
                        <span className="text-[11px] text-gray-400">{a.estimatedTime}</span>
                      </div>
                      <p className="mt-0.5 text-[11.5px] text-gray-500">{a.detail}</p>
                    </div>
                    {incident.status !== 'resolved' && (
                      <button
                        onClick={() => {
                          app.dispatchAgentTask({
                            incidentId: incident.id,
                            actionId: a.id,
                            incidentTitle: incident.title,
                            label: a.label,
                            detail: a.detail,
                            risk: a.risk,
                            estimatedTime: a.estimatedTime,
                          })
                          toast(`'${a.label}' 조치를 AI 에이전트에서 실행합니다`)
                        }}
                        className="flex shrink-0 items-center gap-1 rounded bg-brand-600 px-2.5 py-1.5 text-[11.5px] font-semibold text-white hover:bg-brand-700"
                      >
                        <Icon name="play" size={10} />
                        Run
                      </button>
                    )}
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* action timeline */}
        <div className="px-4 py-3">
          <div className="mb-2 text-[10.5px] font-bold uppercase tracking-wide text-gray-400">타임라인</div>
          <div>
            {incident.actionLog.map((log, i) => (
              <div key={i} className="flex gap-2.5 pb-2.5 last:pb-0">
                <div className="flex flex-col items-center pt-1">
                  <span className="h-2 w-2 shrink-0 rounded-full bg-brand-400" />
                  {i < incident.actionLog.length - 1 && (
                    <span className="my-0.5 w-px flex-1 bg-gray-100" />
                  )}
                </div>
                <div className="-mt-0.5">
                  <div className="text-[12px] text-gray-700">{log.action}</div>
                  <div className="mt-0.5 font-mono text-[10.5px] text-gray-400">
                    {log.time} · {log.actor}
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  )
}
