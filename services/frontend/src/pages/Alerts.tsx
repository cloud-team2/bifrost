import { useEffect, useMemo, useRef, useState } from 'react'
import { Icon } from '../components/Icon'
import { PageHead, StatusBadge } from '../components/blocks'
import { useApp } from '../store/AppStore'
import { pipelineLabel } from '../data/helpers'
import {
  api,
  type ActionRunCandidateInput,
  type ActionRunRisk,
  type ActionRunType,
  ApiError,
  type EventResponse,
  type IncidentReportResponse,
  type IncidentResponse,
  type ResourceEventResponse,
} from '../lib/api'
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
  incidentId?: string | null
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
    incidentId: e.incidentId,
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
      if (event.incidentId === incident.id) return true
      if (event.pipelineId && event.pipelineId === sourceId) return true
      if (pipeline && event.pipelineId === pipeline.id) return true
      if (event.source !== 'resource') return false

      return event.resourceKey === sourceId || (!!pipelineId && event.resourceKey === pipelineId) ||
        isPipelineTopicResource(event.resourceKey, pipeline)
    })
    .sort(eventSortAsc)
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function pickString(value: unknown, keys: string[]): string | null {
  if (!isRecord(value)) return null
  for (const key of keys) {
    const candidate = value[key]
    if (typeof candidate === 'string' && candidate.trim()) return candidate
  }
  return null
}

function nestedRecord(value: unknown, keys: string[]): Record<string, unknown> | null {
  if (!isRecord(value)) return null
  for (const key of keys) {
    const candidate = value[key]
    if (isRecord(candidate)) return candidate
  }
  return null
}

function reportBodyText(body: unknown): string {
  if (typeof body === 'string') return body
  const direct = pickString(body, ['answer', 'markdown', 'content', 'summary'])
  if (direct) return direct
  const final = nestedRecord(body, ['final_response', 'finalResponse'])
  const finalText = pickString(final, ['summary', 'answer', 'content'])
  if (finalText) return finalText
  return ''
}

function reportTitle(report: IncidentReportResponse): string {
  if (report.rootCauseId) return report.rootCauseId
  const text = reportBodyText(report.body).split('\n').find((line) => line.trim())
  return text ? text.slice(0, 72) : `Report ${report.id.slice(0, 8)}`
}

function reportSummary(report: IncidentReportResponse): string {
  const text = reportBodyText(report.body).replace(/\s+/g, ' ').trim()
  return text ? text.slice(0, 140) : '본문이 비어 있습니다'
}

interface ReportAction {
  key: string
  actionId: string
  label: string
  status: string | null
  risk: string | null
  estimatedTime: string | null
  detail: string | null
  reportId: string
  actionType: string | null
  actionName: string | null
  rootCauseId: string | null
  reason: string | null
  expectedEffect: string | null
  rollbackPlan: string | null
  toolName: string | null
  toolParams: Record<string, unknown> | null
}

interface ReportActionLog {
  key: string
  actionId: string
  status: string
  toolName: string | null
  summary: string | null
  reportId: string
}

function actionArrays(body: unknown): unknown[] {
  if (!isRecord(body)) return []
  const out: unknown[] = []
  for (const key of ['action_candidates', 'actionCandidates']) {
    const value = body[key]
    if (Array.isArray(value)) out.push(...value)
  }
  const final = nestedRecord(body, ['final_response', 'finalResponse'])
  if (final) out.push(...actionArrays(final))
  const remediation = nestedRecord(body, ['remediation'])
  if (remediation) out.push(...actionArrays(remediation))
  return out
}

function reportMode(body: unknown): string | null {
  const mode = isRecord(body) ? pickString(body, ['mode']) : null
  return mode?.toLowerCase() ?? null
}

function pickRecordValue(value: unknown, keys: string[]): Record<string, unknown> | null {
  if (!isRecord(value)) return null
  for (const key of keys) {
    const candidate = value[key]
    if (isRecord(candidate)) return candidate
  }
  return null
}

export function reportActions(reports: IncidentReportResponse[]): ReportAction[] {
  const seen = new Set<string>()
  const actions: ReportAction[] = []
  for (const report of reports) {
    const mode = reportMode(report.body)
    if (mode === 'action_execution' || mode === 'approval_decision') continue
    for (const raw of actionArrays(report.body)) {
      if (!isRecord(raw)) continue
      const id = pickString(raw, ['action_id', 'actionId', 'id'])
      const label = pickString(raw, ['action_name', 'actionName', 'name', 'label']) ?? id
      if (!label) continue
      const key = `${report.id}:${id ?? label}`
      if (seen.has(key)) continue
      seen.add(key)
      const expectedEffect = pickString(raw, ['expected_effect', 'expectedEffect', 'effect'])
      const reason = pickString(raw, ['reason', 'detail', 'description'])
      actions.push({
        key,
        actionId: id ?? label,
        label,
        status: pickString(raw, ['status', 'state']),
        risk: pickString(raw, ['risk']),
        estimatedTime: pickString(raw, ['estimated_duration', 'estimatedDuration', 'estimated_time', 'estimatedTime']),
        detail: expectedEffect ?? reason,
        reportId: report.id,
        actionType: pickString(raw, ['action_type', 'actionType', 'type']),
        actionName: pickString(raw, ['action_name', 'actionName', 'name']),
        rootCauseId: pickString(raw, ['root_cause_id', 'rootCauseId']),
        reason,
        expectedEffect,
        rollbackPlan: pickString(raw, ['rollback_plan', 'rollbackPlan']),
        toolName: pickString(raw, ['tool_name', 'toolName']),
        toolParams: pickRecordValue(raw, ['tool_params', 'toolParams', 'params']),
      })
    }
  }
  return actions
}

function executionArrays(body: unknown): unknown[] {
  if (!isRecord(body)) return []
  const out: unknown[] = []
  for (const key of ['execution_results', 'executionResults']) {
    const value = body[key]
    if (Array.isArray(value)) out.push(...value)
  }
  const final = nestedRecord(body, ['final_response', 'finalResponse'])
  if (final) out.push(...executionArrays(final))
  return out
}

export function reportActionLogs(reports: IncidentReportResponse[]): ReportActionLog[] {
  const logs: ReportActionLog[] = []
  for (const report of reports) {
    for (const raw of executionArrays(report.body)) {
      if (!isRecord(raw)) continue
      const actionId = pickString(raw, ['action_id', 'actionId', 'id'])
      const status = pickString(raw, ['status', 'state'])
      if (!actionId || !status) continue
      logs.push({
        key: `${report.id}:${actionId}:${logs.length}`,
        actionId,
        status,
        toolName: pickString(raw, ['tool_name', 'toolName']),
        summary: pickString(raw, ['summary', 'message', 'detail']),
        reportId: report.id,
      })
    }
  }
  return logs
}

const CONNECTOR_TOOL_ALIASES: Record<string, 'restart_connector' | 'pause_connector' | 'resume_connector'> = {
  restart_connector: 'restart_connector',
  restart_connector_task: 'restart_connector',
  pause_connector: 'pause_connector',
  resume_connector: 'resume_connector',
}
const ACTION_EXECUTION_TOOLS = new Set([
  'pause_connector',
  'resume_connector',
])

function normalizeRisk(value: string | null): ActionRunRisk | null {
  const normalized = value?.toLowerCase()
  if (
    normalized === 'read_only' ||
    normalized === 'low' ||
    normalized === 'medium' ||
    normalized === 'high' ||
    normalized === 'forbidden'
  ) {
    return normalized
  }
  return null
}

function normalizeActionType(value: string | null): ActionRunType | null {
  const normalized = value?.toLowerCase()
  if (
    normalized === 'runtime_tool' ||
    normalized === 'workflow_action' ||
    normalized === 'composite_action' ||
    normalized === 'notification' ||
    normalized === 'escalation'
  ) {
    return normalized
  }
  return null
}

function stringParam(params: Record<string, unknown> | null, key: string): string | null {
  const value = params?.[key]
  return typeof value === 'string' && value.trim() ? value : null
}

function connectorTargetFor(action: ReportAction, pipelines: Edge[]): string | null {
  const explicit = stringParam(action.toolParams, 'connector_name')
  if (explicit) return explicit
  void pipelines
  return null
}

function consumerGroupTargetFor(action: ReportAction, pipelines: Edge[]): string | null {
  const explicitParam = stringParam(action.toolParams, 'consumer_group')
  if (explicitParam) return explicitParam

  if (action.toolName !== 'restart_consumer_group') return null
  void pipelines
  return null
}

export function buildRunCandidate(action: ReportAction, pipelines: Edge[]): ActionRunCandidateInput | null {
  const risk = normalizeRisk(action.risk)
  const actionType = normalizeActionType(action.actionType)
  if (!risk || actionType !== 'runtime_tool' || !action.toolName) return null

  const connectorTool = CONNECTOR_TOOL_ALIASES[action.toolName]
  if (connectorTool) {
    if (!ACTION_EXECUTION_TOOLS.has(connectorTool)) return null
    const connectorName = connectorTargetFor(action, pipelines)
    if (!connectorName) return null
    return {
      action_id: action.actionId,
      action_type: actionType,
      action_name: action.actionName ?? action.label,
      root_cause_id: action.rootCauseId,
      risk,
      reason: action.reason ?? action.detail ?? action.label,
      expected_effect: action.expectedEffect,
      rollback_plan: action.rollbackPlan,
      estimated_duration: action.estimatedTime,
      tool_name: connectorTool,
      tool_params: { connector_name: connectorName },
    }
  }

  if (action.toolName === 'restart_consumer_group') {
    if (!ACTION_EXECUTION_TOOLS.has(action.toolName)) return null
    const consumerGroup = consumerGroupTargetFor(action, pipelines)
    if (!consumerGroup) return null
    return {
      action_id: action.actionId,
      action_type: actionType,
      action_name: action.actionName ?? action.label,
      root_cause_id: action.rootCauseId,
      risk,
      reason: action.reason ?? action.detail ?? action.label,
      expected_effect: action.expectedEffect,
      rollback_plan: action.rollbackPlan,
      estimated_duration: action.estimatedTime,
      tool_name: 'restart_consumer_group',
      tool_params: { consumer_group: consumerGroup },
    }
  }

  return null
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
        <div className="mt-4 flex min-h-0 flex-col gap-4 xl:flex-row">
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
            <div className="w-full shrink-0 self-start overflow-hidden rounded-xl border border-gray-200 bg-white xl:w-[380px]">
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
  const wsId = app.currentProject?.id
  const [detailIncident, setDetailIncident] = useState<IncidentResponse | null>(null)
  const [detailLoaded, setDetailLoaded] = useState(false)
  const [detailImpactPipelineIds, setDetailImpactPipelineIds] = useState<string[]>([])
  const [eventRows, setEventRows] = useState<EventResponse[]>([])
  const [eventsLoading, setEventsLoading] = useState(false)
  const [eventsError, setEventsError] = useState<string | null>(null)
  const [reports, setReports] = useState<IncidentReportResponse[]>([])
  const [reportsLoading, setReportsLoading] = useState(false)
  const [reportsError, setReportsError] = useState<string | null>(null)
  const [rawOpen, setRawOpen] = useState(false)
  const [selectedReport, setSelectedReport] = useState<IncidentReportResponse | null>(null)
  const [reportLoadingId, setReportLoadingId] = useState<string | null>(null)
  const [reportError, setReportError] = useState<string | null>(null)
  const completedRunMarker = app.agentRunState.status === 'completed' ? app.agentRunState.updatedAt : null

  useEffect(() => {
    setEventRows([])
    setReports([])
    setDetailIncident(null)
    setDetailLoaded(false)
    setDetailImpactPipelineIds([])
    setEventsError(null)
    setReportsError(null)
    setReportError(null)
    setSelectedReport(null)
    setRawOpen(false)
    if (!wsId) return

    let alive = true
    setEventsLoading(true)
    setReportsLoading(true)
    api
      .getIncidentDetail(wsId, incident.id)
      .then((detail) => {
        if (!alive) return
        setDetailIncident(detail.incident)
        setEventRows(detail.events)
        setDetailImpactPipelineIds(detail.impactPipelineIds)
        setReports(detail.reports)
        setDetailLoaded(true)
      })
      .catch((e) => {
        if (!alive) return
        const message = e instanceof ApiError ? e.message : '인시던트 상세를 불러오지 못했습니다'
        setEventsError(message)
        setReportsError(message)
      })
      .finally(() => {
        if (!alive) return
        setEventsLoading(false)
        setReportsLoading(false)
      })

    return () => {
      alive = false
    }
  }, [wsId, incident.id, completedRunMarker])

  const panelIncident = detailIncident ?? incident
  const timelineEvents = useMemo(
    () => (detailLoaded ? buildEvents(eventRows, []).sort(eventSortAsc) : relatedEvents),
    [detailLoaded, eventRows, relatedEvents],
  )

  const impactPipelineIds = useMemo(() => {
    const ids = new Set<string>()
    detailImpactPipelineIds.forEach((id) => ids.add(id))
    if (isPipelineSource(panelIncident) && panelIncident.sourceId) ids.add(panelIncident.sourceId)
    eventRows.forEach((event) => {
      if (event.pipelineId) ids.add(event.pipelineId)
    })
    return Array.from(ids)
  }, [detailImpactPipelineIds, eventRows, panelIncident])

  const impactPipelines = impactPipelineIds
    .map((id) => app.edges.find((edge) => edge.id === id) ?? null)
    .filter((edge): edge is Edge => edge !== null)
  const missingImpactIds = impactPipelineIds.filter((id) => !app.edges.some((edge) => edge.id === id))
  const actions = useMemo(() => reportActions(reports), [reports])
  const actionLogs = useMemo(() => reportActionLogs(reports), [reports])

  async function openReport(report: IncidentReportResponse) {
    if (!wsId) return
    setReportLoadingId(report.id)
    setReportError(null)
    try {
      setSelectedReport(await api.getIncidentReport(wsId, panelIncident.id, report.id))
    } catch (e) {
      setSelectedReport(report)
      setReportError(e instanceof ApiError ? e.message : '리포트 본문을 불러오지 못했습니다')
    } finally {
      setReportLoadingId(null)
    }
  }

  function runAction(action: ReportAction) {
    const actionCandidate = buildRunCandidate(action, impactPipelines)
    if (!actionCandidate) return
    app.dispatchAgentTask({
      incidentId: panelIncident.id,
      actionId: action.actionId,
      incidentTitle: panelIncident.title,
      label: action.label,
      detail: action.detail ?? action.reason ?? action.label,
      risk: actionCandidate.risk,
      estimatedTime: action.estimatedTime ?? '미정',
      actionCandidate,
    })
  }

  return (
    <>
    <div className="flex max-h-[calc(100vh-140px)] flex-col overflow-hidden">
      <div className="flex shrink-0 items-start gap-2.5 border-b border-gray-100 px-4 py-3">
        <span className={cn('mt-1 h-2 w-2 shrink-0 rounded-full', severityDot(panelIncident.severity))} />
        <span className="flex-1 text-[13px] font-semibold leading-snug text-gray-900">{panelIncident.title}</span>
        <button onClick={onClose} className="shrink-0 text-gray-400 hover:text-gray-600">
          <Icon name="x" size={15} />
        </button>
      </div>

      <div className="flex-1 overflow-auto divide-y divide-gray-100">
        <div className="space-y-3 px-4 py-3">
          <div className="grid grid-cols-2 gap-2">
            <DetailMetric label="상태">
              <StatusBadge status={panelIncident.status} />
            </DetailMetric>
            <DetailMetric label="심각도">
              <StatusBadge status={panelIncident.severity} />
            </DetailMetric>
            <DetailMetric label="Opened">
              <span className="break-all font-mono">{fmtDateTime(panelIncident.openedAt)}</span>
            </DetailMetric>
            <DetailMetric label="Resolved">
              <span className="break-all font-mono">{fmtDateTime(panelIncident.resolvedAt)}</span>
            </DetailMetric>
          </div>

          <div>
            <div className="mb-1 text-[10.5px] font-bold uppercase tracking-wide text-gray-400">영향 Pipeline</div>
            {impactPipelines.length > 0 || missingImpactIds.length > 0 ? (
              <div className="space-y-1">
                {impactPipelines.map((pipeline) => (
                  <div key={pipeline.id} className="text-[12px] text-gray-500">
                    <button
                      onClick={() => app.openPipeline(pipeline.id)}
                      className="inline-flex items-center gap-1 font-medium text-brand-600 hover:underline"
                    >
                      {pipelineLabel(pipeline)}
                      <Icon name="arrow-right" size={11} />
                    </button>
                  </div>
                ))}
                {missingImpactIds.map((id) => (
                  <div key={id} className="font-mono text-[11px] text-gray-400">{id}</div>
                ))}
              </div>
            ) : (
              <div className="text-[12px] text-gray-400">저장된 영향 pipeline 정보가 없습니다</div>
            )}
          </div>

          <div>
            <button
              onClick={() => setRawOpen((v) => !v)}
              className="inline-flex items-center gap-1 text-[11.5px] font-medium text-gray-500 hover:text-gray-700"
            >
              <Icon name={rawOpen ? 'chevron-up' : 'chevron-down'} size={12} />
              기술 필드
            </button>
            {rawOpen && (
              <dl className="mt-2 space-y-1 rounded-lg bg-gray-50 px-3 py-2 text-[11px]">
                <RawField label="tenantId" value={panelIncident.tenantId} />
                <RawField label="groupingKey" value={panelIncident.groupingKey} />
                <RawField label="sourceId" value={panelIncident.sourceId ?? '—'} />
              </dl>
            )}
          </div>
        </div>

        <div className="px-4 py-3">
          <div className="mb-1.5 text-[10.5px] font-bold uppercase tracking-wide text-gray-400">RCA</div>
          <p className="whitespace-pre-wrap text-[12.5px] leading-relaxed text-gray-600">
            {panelIncident.rca?.trim() || '아직 RCA가 기록되지 않았습니다.'}
          </p>
        </div>

        <div className="px-4 py-3">
          <div className="mb-2 text-[10.5px] font-bold uppercase tracking-wide text-gray-400">
            관련 이벤트 {timelineEvents.length}건
          </div>
          {eventsLoading ? (
            <div className="rounded-lg border border-dashed border-gray-200 py-6 text-center text-[12px] text-gray-400">
              관련 이벤트를 불러오는 중…
            </div>
          ) : timelineEvents.length > 0 ? (
            <div>
              {eventsError && (
                <div className="mb-2 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-[12px] text-amber-700">
                  {eventsError} · 기존 로드 데이터로 표시합니다
                </div>
              )}
              {timelineEvents.map((event, i) => (
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
                    {i < timelineEvents.length - 1 && <span className="my-0.5 w-px flex-1 bg-gray-100" />}
                  </div>
                  <div className="-mt-0.5 min-w-0 flex-1">
                    <div className="break-words text-[12px] text-gray-700">{event.message}</div>
                    <div className="mt-0.5 flex flex-wrap items-center gap-2 font-mono text-[10.5px] text-gray-400">
                      <span>{fmtDateTime(event.occurredAt)}</span>
                      <span>{event.label}</span>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <div className="rounded-lg border border-dashed border-gray-200 py-6 text-center text-[12px] text-gray-400">
              {eventsError ?? '상관된 이벤트가 없습니다'}
            </div>
          )}
        </div>

        <div className="px-4 py-3">
          <div className="mb-2 flex items-center justify-between">
            <div className="text-[10.5px] font-bold uppercase tracking-wide text-gray-400">리포트</div>
            {reports.length > 0 && <span className="text-[10.5px] text-gray-400">{reports.length}건</span>}
          </div>
          {reportsLoading ? (
            <div className="rounded-lg border border-dashed border-gray-200 py-6 text-center text-[12px] text-gray-400">
              리포트를 불러오는 중…
            </div>
          ) : reportsError ? (
            <div className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-[12px] text-rose-700">
              {reportsError}
            </div>
          ) : reports.length > 0 ? (
            <div className="space-y-2">
              {reports.map((report) => (
                <button
                  key={report.id}
                  onClick={() => openReport(report)}
                  disabled={reportLoadingId === report.id}
                  className="w-full rounded-lg border border-gray-200 px-3 py-2 text-left hover:border-gray-300 disabled:opacity-60"
                >
                  <div className="flex items-center gap-2">
                    <Icon name="log" size={13} className="text-gray-400" />
                    <span className="min-w-0 flex-1 truncate text-[12.5px] font-semibold text-gray-800">
                      {reportTitle(report)}
                    </span>
                    {report.verified && <StatusBadge status="STABLE" label="verified" />}
                  </div>
                  <div className="mt-1 line-clamp-2 text-[11.5px] leading-relaxed text-gray-500">
                    {reportSummary(report)}
                  </div>
                  <div className="mt-1 flex flex-wrap items-center gap-2 break-all font-mono text-[10.5px] text-gray-400">
                    <span>{fmtDateTime(report.createdAt)}</span>
                    {report.confidence !== null && <span>{Math.round(report.confidence * 100)}%</span>}
                  </div>
                </button>
              ))}
            </div>
          ) : (
            <div className="rounded-lg border border-dashed border-gray-200 py-6 text-center text-[12px] text-gray-400">
              저장된 리포트가 없습니다
            </div>
          )}
        </div>

        <div className="px-4 py-3">
          <div className="mb-2 text-[10.5px] font-bold uppercase tracking-wide text-gray-400">권장 조치</div>
          {reportsLoading ? (
            <div className="rounded-lg border border-dashed border-gray-200 py-6 text-center text-[12px] text-gray-400">
              리포트 기반 조치를 불러오는 중…
            </div>
          ) : reportsError ? (
            <div className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-[12px] text-rose-700">
              {reportsError}
            </div>
          ) : actions.length > 0 ? (
            <div className="space-y-2">
              {actions.map((action) => {
                const runCandidate = buildRunCandidate(action, impactPipelines)
                return (
                <div key={action.key} className="rounded-lg border border-gray-200 px-3 py-2">
                  <div className="flex items-center gap-2">
                    <span className="min-w-0 flex-1 truncate text-[12.5px] font-semibold text-gray-800">
                      {action.label}
                    </span>
                    {action.status && <StatusBadge status={action.status} />}
                  </div>
                  {action.detail && (
                    <div className="mt-1 line-clamp-2 text-[11.5px] leading-relaxed text-gray-500">
                      {action.detail}
                    </div>
                  )}
                  <div className="mt-2 flex flex-wrap items-center gap-2 text-[11px] text-gray-500">
                    {action.risk && <span>risk {action.risk}</span>}
                    {action.estimatedTime && <span>{action.estimatedTime}</span>}
                    {action.toolName && <span className="font-mono">{action.toolName}</span>}
                    <button
                      onClick={() => {
                        const report = reports.find((r) => r.id === action.reportId)
                        if (report) openReport(report)
                      }}
                      className="font-medium text-brand-600 hover:underline"
                    >
                      근거 리포트
                    </button>
                    <button
                      onClick={() => runAction(action)}
                      disabled={!runCandidate}
                      title={runCandidate ? 'AI 채팅에서 실제 조치를 실행합니다' : '지원되는 실행 tool 또는 실제 target을 확인하지 못했습니다'}
                      className="ml-auto inline-flex items-center gap-1 rounded-md bg-gray-900 px-2 py-1 text-[11px] font-semibold text-white hover:bg-gray-800 disabled:bg-gray-200 disabled:text-gray-400"
                    >
                      <Icon name="play" size={11} />
                      Run
                    </button>
                  </div>
                </div>
                )
              })}
            </div>
          ) : (
            <div className="rounded-lg border border-dashed border-gray-200 py-6 text-center text-[12px] text-gray-400">
              report body에 기록된 권장 조치가 없습니다
            </div>
          )}
        </div>

        <div className="px-4 py-3">
          <div className="mb-2 flex items-center justify-between">
            <div className="text-[10.5px] font-bold uppercase tracking-wide text-gray-400">조치 이력</div>
            {actionLogs.length > 0 && <span className="text-[10.5px] text-gray-400">{actionLogs.length}건</span>}
          </div>
          {actionLogs.length > 0 ? (
            <div className="space-y-2">
              {actionLogs.map((log) => (
                <div key={log.key} className="rounded-lg border border-gray-200 px-3 py-2">
                  <div className="flex items-center gap-2">
                    <span className="min-w-0 flex-1 font-mono text-[11.5px] text-gray-700">{log.actionId}</span>
                    <StatusBadge status={log.status} />
                  </div>
                  <div className="mt-1 flex flex-wrap items-center gap-2 text-[11px] text-gray-500">
                    {log.toolName && <span className="font-mono">{log.toolName}</span>}
                    {log.summary && <span className="min-w-0 flex-1 break-words">{log.summary}</span>}
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <div className="rounded-lg border border-dashed border-gray-200 py-6 text-center text-[12px] text-gray-400">
              실행된 조치 이력이 없습니다
            </div>
          )}
        </div>
      </div>
    </div>
    {selectedReport && (
      <ReportDrawer
        report={selectedReport}
        error={reportError}
        onClose={() => {
          setSelectedReport(null)
          setReportError(null)
        }}
      />
    )}
    </>
  )
}

function DetailMetric({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="rounded-lg bg-gray-50 px-3 py-2">
      <div className="mb-1 text-[10px] font-bold uppercase tracking-wide text-gray-400">{label}</div>
      <div className="text-[11.5px] text-gray-700">{children}</div>
    </div>
  )
}

function RawField({ label, value }: { label: string; value: string }) {
  return (
    <div className="grid grid-cols-[80px_1fr] gap-2">
      <dt className="font-semibold text-gray-500">{label}</dt>
      <dd className="break-all font-mono text-gray-600">{value}</dd>
    </div>
  )
}

function ReportDrawer({
  report,
  error,
  onClose,
}: {
  report: IncidentReportResponse
  error: string | null
  onClose: () => void
}) {
  const body = reportBodyText(report.body)
  const drawerRef = useRef<HTMLElement | null>(null)
  useEffect(() => {
    drawerRef.current?.focus()
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [onClose])

  return (
    <div
      className="fixed inset-0 z-[80] flex justify-end bg-gray-900/30"
      role="dialog"
      aria-modal="true"
      aria-labelledby="incident-report-title"
    >
      <button className="flex-1 cursor-default" aria-label="close report drawer" onClick={onClose} />
      <aside ref={drawerRef} tabIndex={-1} className="flex h-full w-full max-w-[560px] flex-col bg-white shadow-xl">
        <div className="flex items-start gap-3 border-b border-gray-100 px-5 py-4">
          <Icon name="log" size={16} className="mt-0.5 text-gray-400" />
          <div className="min-w-0 flex-1">
            <div id="incident-report-title" className="truncate text-[14px] font-semibold text-gray-900">
              {reportTitle(report)}
            </div>
            <div className="mt-1 flex flex-wrap items-center gap-2 font-mono text-[10.5px] text-gray-400">
              <span className="break-all">{report.id}</span>
              <span>{fmtDateTime(report.createdAt)}</span>
              {report.confidence !== null && <span>{Math.round(report.confidence * 100)}%</span>}
            </div>
          </div>
          <button onClick={onClose} className="shrink-0 text-gray-400 hover:text-gray-600">
            <Icon name="x" size={16} />
          </button>
        </div>
        {error && (
          <div className="border-b border-rose-100 bg-rose-50 px-5 py-2 text-[12px] text-rose-700">
            {error}
          </div>
        )}
        <div className="flex-1 overflow-auto px-5 py-4">
          {body ? (
            <pre className="whitespace-pre-wrap break-words rounded-lg bg-gray-50 p-3 font-sans text-[12.5px] leading-relaxed text-gray-700">
              {body}
            </pre>
          ) : (
            <div className="rounded-lg border border-dashed border-gray-200 py-10 text-center text-[13px] text-gray-400">
              표시 가능한 리포트 본문 필드가 없습니다
            </div>
          )}
        </div>
      </aside>
    </div>
  )
}
