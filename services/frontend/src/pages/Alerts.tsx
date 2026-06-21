import { useEffect, useMemo, useRef, useState } from 'react'
import { Icon } from '../components/Icon'
import { Markdown } from '../components/Markdown'
import { PageHead, StatusBadge } from '../components/blocks'
import { useApp } from '../store/AppStore'
import { pipelineLabel } from '../data/helpers'
import {
  api,
  type ActionRunCandidateInput,
  type ActionRunRisk,
  type ActionRunType,
  type AgentRunSummary,
  ApiError,
  type EventResponse,
  type IncidentReportResponse,
  type IncidentResponse,
  type ResourceEventResponse,
} from '../lib/api'
import type { Edge } from '../data/types'
import type { LogLevel } from '../data/types'
import { cn } from '../lib/format'
import { semanticBadgeClass, semanticToken } from './ai/AgentRunPanel'

const RISK_LABEL_KO: Record<string, string> = {
  low: '낮음',
  read_only: '낮음',
  medium: '중간',
  high: '높음',
  forbidden: '높음',
}

function riskLabelKo(risk: string) {
  return RISK_LABEL_KO[risk.toLowerCase()] ?? risk
}

/* ---------------------------------------------------------------- constants */

const SEV_DOT: Record<string, string> = {
  CRITICAL: 'bg-[#c0392b]',
  ERROR: 'bg-[#c0392b]',
  WARN: 'bg-[#c8c8c8]',
  WARNING: 'bg-[#c8c8c8]',
  INFO: 'bg-[#c8c8c8]',
}
const SEV_BORDER: Record<string, string> = {
  CRITICAL: 'border-l-[#c0392b]',
  ERROR: 'border-l-[#c0392b]',
  WARN: 'border-l-[#d9d9d9]',
  WARNING: 'border-l-[#d9d9d9]',
  INFO: 'border-l-[#d9d9d9]',
}
const LEVEL_BADGE: Record<LogLevel, string> = {
  error: 'bg-[#c0392b] text-white', // 오류 솔리드 통일(#770)
  warning: 'bg-[#d97316] text-white', // 경고 주황
  info: 'bg-[#3a47c2] text-white', // 정보 인디고
}
const LEVEL_LEFT: Record<LogLevel, string> = {
  error: 'border-l-[#c0392b]',
  warning: 'border-l-[#d9d9d9]',
  info: 'border-l-[#ececec]',
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
  return SEV_DOT[severityKey(severity)] ?? 'bg-[#c8c8c8]'
}

function severityBorder(severity: string): string {
  return SEV_BORDER[severityKey(severity)] ?? 'border-l-[#d9d9d9]'
}

// #935/#936 에이전트 진행 단계 한글 라벨(에이전트명 → 사용자 표현).
const AGENT_STAGE_KO: Record<string, string> = {
  router: '라우팅',
  correlation: '연관 분석',
  planner: '조사 계획',
  retrieval: '증거 수집',
  classifier: '분류',
  rca: '근본 원인 분석',
  remediation: '권장 조치 도출',
  policy_guard: '정책 점검',
  change_management: '변경 관리',
  change: '변경 적용',
  executor: '조치 실행',
  verifier: '검증',
  report: '리포트 작성',
}

// #949 조치 승인/실행 상태 한글 라벨.
const ACTION_STATUS_KO: Record<string, string> = {
  approved: '승인됨',
  executing: '실행 중',
  ready: '실행 대기',
  completed: '실행 완료',
  executed: '실행 완료',
  succeeded: '실행 완료',
  failed: '실행 실패',
  blocked: '차단됨',
  rejected: '거부됨',
}

function actionStatusLabel(status: string): string {
  return ACTION_STATUS_KO[status.toLowerCase()] ?? status
}

const RUN_IN_PROGRESS_STATUS = new Set(['running', 'created', 'queued', 'pending'])

export interface IncidentRunProgress {
  /** analysis=RCA 분석 파이프라인, action=승인된 조치 실행. 진행 중 run 이 없으면 null */
  phase: 'analysis' | 'action' | null
  stage: string | null
}

// #936 인시던트의 진행 중 run 을 분류한다(분석 중 vs 조치 실행 중 + 현재 단계). 순수 함수.
export function incidentRunProgress(runs: AgentRunSummary[], incidentId: string): IncidentRunProgress {
  const active = runs.find(
    (r) => r.incident_id === incidentId && RUN_IN_PROGRESS_STATUS.has((r.status ?? '').toLowerCase()),
  )
  if (!active) return { phase: null, stage: null }
  const phase = (active.mode ?? '').toLowerCase() === 'action_execution' ? 'action' : 'analysis'
  return { phase, stage: active.current_agent ?? null }
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

function resourceEventId(e: ResourceEventResponse): string {
  return `resource:${e.eventType}:${e.resource}:${e.detail}`
}

export function buildEvents(events: EventResponse[], resourceEvents: ResourceEventResponse[]): UnifiedEvent[] {
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
  const resourceCounts = new Map<string, number>()
  const resources: UnifiedEvent[] = resourceEvents.map((e) => {
    const baseId = resourceEventId(e)
    const duplicateIndex = resourceCounts.get(baseId) ?? 0
    resourceCounts.set(baseId, duplicateIndex + 1)
    return {
      id: duplicateIndex === 0 ? baseId : `${baseId}#${duplicateIndex + 1}`,
      occurredAt: e.occurredAt,
      level: null,
      message: eventMessage(e),
      source: 'resource',
      label: e.eventType,
      resourceKey: e.resource,
    }
  })
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
  // #949 조치별 승인/실행 상태: approved_actions(승인)·execution_results(실행 결과)를 action_id 로 모은다.
  // 실행 결과가 승인보다 우선(실행됨/실패 > 승인됨)이라 나중에 덮어쓴다.
  const statusByActionId = new Map<string, string>()
  for (const report of reports) {
    for (const a of approvedActionArrays(report.body)) {
      if (!isRecord(a)) continue
      const id = pickString(a, ['action_id', 'actionId', 'id'])
      if (id && !statusByActionId.has(id)) statusByActionId.set(id, 'approved')
    }
    for (const e of executionArrays(report.body)) {
      if (!isRecord(e)) continue
      const id = pickString(e, ['action_id', 'actionId', 'id'])
      const st = pickString(e, ['status', 'state'])
      if (id && st) statusByActionId.set(id, st.toLowerCase())
    }
  }
  for (const report of reports) {
    const mode = reportMode(report.body)
    if (mode === 'action_execution' || mode === 'approval_decision') continue
    for (const raw of actionArrays(report.body)) {
      if (!isRecord(raw)) continue
      const id = pickString(raw, ['action_id', 'actionId', 'id'])
      const label = pickString(raw, ['action_name', 'actionName', 'name', 'label']) ?? id
      if (!label) continue
      const expectedEffect = pickString(raw, ['expected_effect', 'expectedEffect', 'effect'])
      const reason = pickString(raw, ['reason', 'detail', 'description'])
      const toolName = pickString(raw, ['tool_name', 'toolName'])
      const toolParams = pickRecordValue(raw, ['tool_params', 'toolParams', 'params'])
      // #937: 같은 조치가 여러 리포트/후보로 중복 노출되지 않도록 기능 정체성으로 dedup한다.
      // (report.id·인스턴스 action_id 가 달라도 이름·도구·파라미터·효과가 같으면 한 번만 표시)
      const key = [label, toolName ?? '', JSON.stringify(toolParams ?? {}), expectedEffect ?? reason ?? ''].join('|')
      if (seen.has(key)) continue
      seen.add(key)
      actions.push({
        key,
        actionId: id ?? label,
        label,
        status: (id ? statusByActionId.get(id) : undefined) ?? pickString(raw, ['status', 'state']),
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
        toolName,
        toolParams,
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

// #949 리포트 본문의 approved_actions(승인된 조치) 배열을 모은다(final_response 중첩 포함).
function approvedActionArrays(body: unknown): unknown[] {
  if (!isRecord(body)) return []
  const out: unknown[] = []
  for (const key of ['approved_actions', 'approvedActions']) {
    const value = body[key]
    if (Array.isArray(value)) out.push(...value)
  }
  const final = nestedRecord(body, ['final_response', 'finalResponse'])
  if (final) out.push(...approvedActionArrays(final))
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
  'restart_connector',
  'restart_consumer_group',
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
  // tool_params에 connector_name이 없으면 영향 파이프라인의 소스 커넥터로 폴백
  for (const pipeline of pipelines) {
    if (pipeline.sourceConnector) return pipeline.sourceConnector
  }
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
  const [selectedEventId, setSelectedEventId] = useState<string | null>(null)
  const [sourceFilter, setSourceFilter] = useState<SourceFilter>('all')
  const [levelFilter, setLevelFilter] = useState<'all' | LogLevel>('all')

  const incidents = app.incidents
  const activeIncidents = incidents.filter(isOpenIncident)
  const resolvedIncidents = incidents.filter((i) => !isOpenIncident(i))
  const selectedIncident = incidents.find((i) => i.id === selectedIncidentId) ?? null
  const allEvents = useMemo(() => buildEvents(app.events, app.resourceEvents), [app.events, app.resourceEvents])
  const selectedEvent = allEvents.find((event) => event.id === selectedEventId) ?? null
  const selectedRelatedEvents = useMemo(
    () => (selectedIncident ? relatedEventsForIncident(selectedIncident, allEvents, app.edges) : []),
    [selectedIncident, allEvents, app.edges],
  )
  const selectedRelatedEventIds = useMemo(
    () => new Set(selectedRelatedEvents.map((event) => event.id)),
    [selectedRelatedEvents],
  )
  // #864 ERROR 이벤트는 곧 인시던트 — 이벤트에 incidentId가 있으면 인시던트 상세로 바로 연결(드릴다운 제거).
  const eventIncident =
    selectedEvent?.incidentId ? incidents.find((i) => i.id === selectedEvent.incidentId) ?? null : null
  const detailIncident = selectedIncident ?? eventIncident
  const detailEvent = detailIncident ? null : selectedEvent
  const detailRelatedEvents = useMemo(
    () => (detailIncident ? relatedEventsForIncident(detailIncident, allEvents, app.edges) : []),
    [detailIncident, allEvents, app.edges],
  )
  const hasMonitoringData = incidents.length > 0 || allEvents.length > 0
  const initialLoading = app.monitoringLoading && !hasMonitoringData

  useEffect(() => {
    if (!app.opSelectedIncidentId) return
    setSelectedIncidentId(app.opSelectedIncidentId)
    setSelectedEventId(null)
    app.clearOpSelectedIncident()
  }, [app.opSelectedIncidentId])

  useEffect(() => {
    if (!selectedEventId) return
    if (!allEvents.some((event) => event.id === selectedEventId)) setSelectedEventId(null)
  }, [allEvents, selectedEventId])

  // 인시던트/이벤트 상세 모달(#780): Esc로 닫기.
  const closeDetail = () => {
    setSelectedIncidentId(null)
    setSelectedEventId(null)
  }
  useEffect(() => {
    if (!selectedIncidentId && !selectedEventId) return
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') closeDetail()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [selectedIncidentId, selectedEventId])

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
    setSelectedIncidentId((prev) => {
      const next = prev === id ? null : id
      if (next) setSelectedEventId(null)
      return next
    })
  }

  function toggleEvent(id: string) {
    setSelectedEventId((prev) => {
      const next = prev === id ? null : id
      if (next) setSelectedIncidentId(null)
      return next
    })
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

      {app.monitoringError && !hasMonitoringData ? (
        <div className="mt-4 rounded-lg border border-[#c0392b] bg-[#fcf3f2] px-4 py-3 text-[13px] text-[#c0392b]">
          {app.monitoringError}
        </div>
      ) : initialLoading ? (
        <div className="mt-4 rounded-xl border border-gray-200 bg-white py-12 text-center text-[13px] text-gray-400">
          모니터링 데이터를 불러오는 중…
        </div>
      ) : detailIncident ? (
        <IncidentDetailScreen
          incident={detailIncident}
          relatedEvents={detailRelatedEvents}
          onBack={closeDetail}
        />
      ) : detailEvent ? (
        <EventDetailScreen
          event={detailEvent}
          pipeline={detailEvent.pipelineId ? app.edges.find((e) => e.id === detailEvent.pipelineId) ?? null : null}
          onBack={closeDetail}
          onOpenPipeline={(id) => app.openPipeline(id)}
        />
      ) : (
        <div className="mt-4 flex min-h-0 flex-col gap-4 xl:flex-row">
          <div className="min-w-0 flex-1 space-y-3">
            {app.monitoringError && (
              <div className="rounded-lg border border-[#ececec] bg-[#ededed] px-4 py-3 text-[13px] text-[#6b6b73]">
                {app.monitoringError} · 기존 데이터로 표시합니다
              </div>
            )}
            {activeIncidents.length > 0 ? (
              <div className="space-y-2">
                <div className="flex items-center gap-2">
                  <span className="text-[11px] font-semibold uppercase tracking-wide text-gray-400">
                    활성 인시던트
                  </span>
                  <span className="flex h-4 min-w-4 items-center justify-center rounded-full bg-[#c0392b] px-1 text-[10px] font-bold text-white">
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
              <div className="rounded-xl border border-gray-200 bg-white py-8 text-center text-[13px] text-[#6b6b73]">
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
                      selected={selectedEventId === event.id}
                      isLast={i === filteredEvents.length - 1}
                      onClick={() => toggleEvent(event.id)}
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
  selected,
  isLast,
  onClick,
}: {
  event: UnifiedEvent
  relatedEventIds: Set<string>
  selected: boolean
  isLast: boolean
  onClick: () => void
}) {
  const left = event.level ? LEVEL_LEFT[event.level] : 'border-l-gray-300'
  const hasSelection = relatedEventIds.size > 0
  const isHighlighted = hasSelection && relatedEventIds.has(event.id)
  const isDimmed = hasSelection && !isHighlighted
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        'flex w-full items-start gap-3 border-l-2 px-4 py-2.5 text-left transition-colors hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-inset focus:ring-brand-200',
        !isLast && 'border-b border-gray-50',
        left,
        (isHighlighted || selected) && 'bg-brand-50/25',
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
      <Icon name="chevron-right" size={13} className="mt-0.5 shrink-0 text-gray-300" />
    </button>
  )
}

/* ---------------------------------------------------------------- 상세 화면 공통 (#864) */

// 상세는 모달이 아니라 목록 자리를 채우는 전용 화면. ← 목록으로 복귀.
function DetailShell({
  onBack,
  accent,
  children,
}: {
  onBack: () => void
  accent?: string
  children: React.ReactNode
}) {
  return (
    <div
      className="mt-4 overflow-hidden rounded-xl border border-gray-200 bg-white"
      style={accent ? { borderLeftWidth: 4, borderLeftColor: accent } : undefined}
    >
      <div className="border-b border-gray-100 px-5 py-2.5">
        <button
          onClick={onBack}
          className="inline-flex items-center gap-1.5 text-[12px] font-medium text-gray-500 hover:text-gray-800"
        >
          <Icon name="arrow-left" size={13} /> 이벤트 목록
        </button>
      </div>
      {children}
    </div>
  )
}

function SideGroup({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="mb-3.5 last:mb-0">
      <div className="mb-1 text-[10px] font-bold uppercase tracking-wide text-gray-400">{label}</div>
      {children}
    </div>
  )
}

function levelDotClass(level: LogLevel | null): string {
  return level === 'error'
    ? 'bg-[#c0392b]'
    : level === 'warning'
      ? 'bg-[#d97316]'
      : level === 'info'
        ? 'bg-[#3a47c2]'
        : 'bg-gray-300'
}
function levelLabel(level: LogLevel | null): string {
  return level === 'error' ? 'Error' : level === 'warning' ? 'Warning' : level === 'info' ? 'Info' : 'Event'
}

/* ---------------------------------------------------------------- EventDetailScreen (INFO/WARNING) */

export function EventDetailScreen({
  event,
  pipeline,
  onBack,
  onOpenPipeline,
}: {
  event: UnifiedEvent
  pipeline: Edge | null
  onBack: () => void
  onOpenPipeline: (id: string) => void
}) {
  return (
    <DetailShell onBack={onBack}>
      <div className="grid grid-cols-1 lg:grid-cols-[1fr_248px]">
        <div className="min-w-0 lg:border-r lg:border-gray-100">
          <div className="flex items-start gap-3 border-b border-gray-100 px-5 py-4">
            <div className="min-w-0 flex-1">
              <div className="text-[16px] font-bold leading-snug text-gray-900">{event.label}</div>
              <div className="mt-1.5 font-mono text-[10.5px] text-gray-400">
                {event.source === 'pipeline' ? 'pipeline' : 'resource'} · {fmtDateTime(event.occurredAt)}
              </div>
            </div>
            {event.level && (
              <span className={cn('shrink-0 rounded px-2 py-0.5 text-[10px] font-bold uppercase', LEVEL_BADGE[event.level])}>
                {event.level}
              </span>
            )}
          </div>

          {event.message && (
            <div className="border-b border-gray-100 px-5 py-4">
              <div className="mb-2 text-[10.5px] font-bold uppercase tracking-wide text-gray-400">내용</div>
              <p className="whitespace-pre-wrap break-words text-[13px] leading-relaxed text-gray-700">{event.message}</p>
            </div>
          )}

          <div className="px-5 py-4">
            <div className="mb-3 text-[10.5px] font-bold uppercase tracking-wide text-gray-400">경위 · Activity</div>
            <div className="grid grid-cols-[56px_14px_1fr] items-start">
              <div className="pr-3 pt-0.5 text-right font-mono text-[10.5px] text-gray-400">{fmtTime(event.occurredAt)}</div>
              <div className="flex justify-center">
                <span className={cn('mt-0.5 h-2.5 w-2.5 rounded-full ring-2 ring-white', levelDotClass(event.level))} />
              </div>
              <div className="min-w-0 pl-3">
                <div className="break-words text-[12.5px] leading-snug text-gray-700">{event.message || event.label}</div>
                <div className="mt-0.5 font-mono text-[10.5px] text-gray-400">{event.label}</div>
              </div>
            </div>
          </div>
        </div>

        <div className="px-5 py-4">
          <SideGroup label="레벨">
            <span className="inline-flex items-center gap-1.5 text-[12.5px] font-semibold text-gray-700">
              <span className={cn('h-2 w-2 rounded-full', levelDotClass(event.level))} />
              {levelLabel(event.level)}
            </span>
          </SideGroup>
          <SideGroup label="발생">
            <span className="font-mono text-[11px] text-gray-600">{fmtDateTime(event.occurredAt)}</span>
          </SideGroup>
          {event.pipelineId && (
            <SideGroup label="영향 Pipeline">
              {pipeline ? (
                <button
                  onClick={() => onOpenPipeline(pipeline.id)}
                  className="inline-flex items-center gap-1 text-[12.5px] font-medium text-brand-600 hover:underline"
                >
                  {pipelineLabel(pipeline)} <Icon name="arrow-right" size={11} />
                </button>
              ) : (
                <span className="break-all font-mono text-[11px] text-gray-400">{event.pipelineId}</span>
              )}
            </SideGroup>
          )}
          {event.resourceKey && (
            <SideGroup label="리소스">
              <span className="break-all font-mono text-[11px] text-gray-600">{event.resourceKey}</span>
            </SideGroup>
          )}
          <SideGroup label="이벤트 ID">
            <span className="break-all font-mono text-[11px] text-gray-500">{event.id}</span>
          </SideGroup>
        </div>
      </div>
    </DetailShell>
  )
}

/* ---------------------------------------------------------------- IncidentPanel */

// 인시던트 지속시간: opened → (resolved | 마지막 복구 이벤트 | 지금).
function fmtDuration(startIso: string, endIso: string | null): string {
  const start = new Date(startIso).getTime()
  const end = endIso ? new Date(endIso).getTime() : Date.now()
  if (Number.isNaN(start) || Number.isNaN(end) || end < start) return '—'
  const mins = Math.round((end - start) / 60000)
  if (mins < 1) return '1분 미만'
  if (mins < 60) return `${mins}분`
  const h = Math.floor(mins / 60)
  const m = mins % 60
  return m ? `${h}시간 ${m}분` : `${h}시간`
}
function isRecoveryEvent(label: string): boolean {
  return /recover|resolved|정상|복구|normal/i.test(label)
}

// #865 인시던트 종류 → 주 신호. 지표성(차트)·상태성(상태+타임라인)으로 나눈다.
type IncidentSignal =
  | { kind: 'metric'; metric: string; label: string }
  | { kind: 'state'; label: string }
  | null

function sevRank(level: LogLevel | null): number {
  return level === 'error' ? 3 : level === 'warning' ? 2 : level === 'info' ? 1 : 0
}

function incidentSignal(events: UnifiedEvent[]): IncidentSignal {
  const ranked = [...events].sort((a, b) => sevRank(b.level) - sevRank(a.level))
  for (const e of ranked) {
    const t = (e.label || '').toUpperCase()
    if (/LAG/.test(t)) return { kind: 'metric', metric: 'consumer_lag_p95', label: '컨슈머 지연' }
    if (/FRESHNESS|WATERMARK|SOURCE.*DELAY/.test(t))
      return { kind: 'metric', metric: 'source_freshness_delay_ms', label: '소스 지연(ms)' }
    if (/THROUGHPUT|INGRESS/.test(t))
      return { kind: 'metric', metric: 'topic_ingress_messages_per_sec', label: '토픽 유입(msg/s)' }
    if (/CONNECTOR|TASK_FAILED|FAILED|PARTIALLY/.test(t)) return { kind: 'state', label: '커넥터 상태' }
    if (/UNREACHABLE|CONNECTION/.test(t)) return { kind: 'state', label: '연결 상태' }
    if (/PARTITION|REASSIGN|REBALANCE/.test(t)) return { kind: 'state', label: '파티션·리밸런스' }
  }
  return null
}

function fmtNum(v: number): string {
  if (!Number.isFinite(v)) return '—'
  return Math.abs(v) >= 1000 ? Math.round(v).toLocaleString() : Math.round(v * 10) / 10 + ''
}

// 지표성 인시던트의 주 지표 시계열(범용 /metrics/series). 실패·빈 데이터는 조용히 처리.
function IncidentChart({ wsId, metric, label }: { wsId: string; metric: string; label: string }) {
  const [points, setPoints] = useState<{ timestamp: string; value: number }[] | null>(null)
  const [failed, setFailed] = useState(false)
  useEffect(() => {
    let alive = true
    setPoints(null)
    setFailed(false)
    api
      .metricSeries(wsId, metric, 30)
      .then((res) => alive && setPoints(res.dataPoints ?? []))
      .catch(() => alive && setFailed(true))
    return () => {
      alive = false
    }
  }, [wsId, metric])

  if (failed) return null
  const vals = (points ?? []).map((p) => p.value)
  const n = vals.length
  const max = Math.max(1, ...vals)
  const linePath =
    n > 1 ? 'M ' + vals.map((v, i) => `${(i / (n - 1)) * 560},${66 - (v / max) * 58}`).join(' L ') : ''
  return (
    <div className="border-b border-gray-100 px-5 py-4">
      <div className="mb-2 flex items-center justify-between">
        <div className="text-[10.5px] font-bold uppercase tracking-wide text-gray-400">{label} · 추이</div>
        <span className="font-mono text-[10.5px] text-gray-400">최근 30분</span>
      </div>
      {points === null ? (
        <div className="flex h-[70px] items-center justify-center rounded-lg border border-dashed border-gray-200 text-[12px] text-gray-400">
          시계열을 불러오는 중…
        </div>
      ) : n === 0 ? (
        <div className="flex h-[70px] items-center justify-center rounded-lg border border-dashed border-gray-200 text-[12px] text-gray-400">
          시계열 데이터가 없습니다
        </div>
      ) : (
        <>
          <div className="rounded-lg border border-gray-200 bg-white p-1.5">
            <svg viewBox="0 0 560 70" width="100%" height="70" preserveAspectRatio="none">
              <path d={linePath} fill="none" stroke="#c0392b" strokeWidth="2" />
            </svg>
          </div>
          <div className="mt-1 font-mono text-[10.5px] text-gray-400">
            현재 {fmtNum(vals[n - 1])} · 최고 {fmtNum(max)}
          </div>
        </>
      )}
    </div>
  )
}

function IncidentDetailScreen({
  incident,
  relatedEvents,
  onBack,
}: {
  incident: IncidentResponse
  relatedEvents: UnifiedEvent[]
  onBack: () => void
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
  // #935 RCA 진행 표시 / #936 조치 실행 진행 표시: 이 인시던트의 진행 중 run(분석/조치) + 현재 단계.
  const [analysisRunning, setAnalysisRunning] = useState(false)
  const [analysisStage, setAnalysisStage] = useState<string | null>(null)
  const [actionRunning, setActionRunning] = useState(false)
  const [actionStage, setActionStage] = useState<string | null>(null)
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
  const incidentOpen = isOpenIncident(panelIncident)
  // #935/#936: 인시던트가 열려 있는 동안 이 인시던트의 진행 중 run 과 상세를 폴링한다.
  //  - 분석 run(incident_analysis) → RCA 섹션에 '분석 중'(+단계)
  //  - 조치 run(action_execution) → 권장 조치 섹션에 '조치 실행 중'(+단계)
  //  - 매 틱마다 상세를 재조회해 RCA·권장조치·조치이력을 수동 새로고침 없이 실시간 반영한다.
  useEffect(() => {
    if (!wsId || !incidentOpen) {
      setAnalysisRunning(false)
      setAnalysisStage(null)
      setActionRunning(false)
      setActionStage(null)
      return
    }
    let alive = true
    let timer: ReturnType<typeof setTimeout> | undefined
    const tick = async () => {
      try {
        const [detail, runsResp] = await Promise.all([
          api.getIncidentDetail(wsId, incident.id).catch(() => null),
          api.agentRuns(wsId, 20).catch(() => null),
        ])
        if (!alive) return
        if (detail) {
          setDetailIncident(detail.incident)
          setReports(detail.reports)
          setEventRows(detail.events)
          setDetailImpactPipelineIds(detail.impactPipelineIds)
        }
        const { phase, stage } = incidentRunProgress(runsResp?.runs ?? [], incident.id)
        setAnalysisRunning(phase === 'analysis')
        setAnalysisStage(phase === 'analysis' ? stage : null)
        setActionRunning(phase === 'action')
        setActionStage(phase === 'action' ? stage : null)
      } catch {
        /* 폴링 실패는 무시 */
      }
      if (alive) timer = setTimeout(tick, 5000)
    }
    tick()
    return () => {
      alive = false
      if (timer) clearTimeout(timer)
    }
  }, [wsId, incident.id, incidentOpen])
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
    // 권장 조치 실행 시 AI 패널을 펼쳐 런이 챗봇에 바로 보이게 한다.
    app.setAIPanel(true)
  }

  const isOpen = isOpenIncident(panelIncident)
  const recoveredEvent = timelineEvents.find((event) => isRecoveryEvent(event.label))
  const durationEnd = panelIncident.resolvedAt ?? recoveredEvent?.occurredAt ?? null
  const signal = incidentSignal(timelineEvents)
  const accent = isOpen ? '#c0392b' : '#157f4a'

  return (
    <>
      <DetailShell onBack={onBack} accent={accent}>
        <div className="grid grid-cols-1 lg:grid-cols-[1fr_260px]">
          {/* MAIN */}
          <div className="min-w-0 lg:border-r lg:border-gray-100">
            {/* header */}
            <div className="border-b border-gray-100 px-5 py-4">
              <div className="flex items-start gap-3">
                <div className="min-w-0 flex-1">
                  <div className="text-[17px] font-bold leading-snug text-gray-900">{panelIncident.title}</div>
                  {panelIncident.groupingKey && (
                    <div className="mt-1.5 break-all font-mono text-[10.5px] text-gray-400">{panelIncident.groupingKey}</div>
                  )}
                </div>
                <div className="flex shrink-0 items-center gap-1.5">
                  <StatusBadge status={panelIncident.severity} />
                  <StatusBadge status={panelIncident.status} />
                </div>
              </div>
            </div>

            {/* 상태 reconcile: 지표는 복구됐는데 인시던트가 아직 열려 있음(파생) */}
            {isOpen && recoveredEvent && (
              <div className="flex items-start gap-2.5 border-b border-[#f0e4d3] bg-[#fdf6ee] px-5 py-2.5 text-[12px] leading-relaxed text-[#7a5a2e]">
                <Icon name="alert" size={14} className="mt-0.5 shrink-0 text-[#c98a2e]" />
                <span>
                  관련 지표는 <b className="font-semibold text-[#5d4420]">{fmtDateTime(recoveredEvent.occurredAt)} 정상화</b>됐지만
                  인시던트는 아직 <b className="font-semibold text-[#5d4420]">열려 있습니다</b> — 확인 후 해소하세요.
                </span>
              </div>
            )}

            {/* 해결됨: 조치 완료/회복으로 RESOLVED */}
            {!isOpen && (
              <div className="flex items-center gap-2.5 border-b border-[#d4e8dc] bg-[#eef6f1] px-5 py-2.5 text-[12px] text-[#1c6b44]">
                <Icon name="info" size={14} className="shrink-0 text-[#157f4a]" />
                <span>
                  이 인시던트는 <b className="font-semibold">해결됨</b>입니다
                  {panelIncident.resolvedAt ? ` · ${fmtDateTime(panelIncident.resolvedAt)}` : ''}.
                </span>
              </div>
            )}

            {/* 주 신호: 지표성=시계열 차트 / 상태성=경위 타임라인 참고 */}
            {signal?.kind === 'metric' && wsId ? (
              <IncidentChart wsId={wsId} metric={signal.metric} label={signal.label} />
            ) : signal?.kind === 'state' ? (
              <div className="border-b border-gray-100 px-5 py-3 text-[12px] text-gray-500">
                주 신호 · <b className="text-gray-700">{signal.label}</b> — 시간 흐름은 아래 경위(타임라인)를 참고하세요.
              </div>
            ) : null}

            {/* RCA (자동 분석) */}
            <div className="border-b border-gray-100 px-5 py-4">
              <div className="mb-2 text-[10.5px] font-bold uppercase tracking-wide text-gray-400">근본 원인 · RCA</div>
              {panelIncident.rca?.trim() ? (
                <div className="text-[13px] leading-relaxed text-gray-700">
                  <Markdown>{panelIncident.rca}</Markdown>
                </div>
              ) : analysisRunning ? (
                <div className="flex items-center gap-3 rounded-lg bg-gray-50 px-3.5 py-3">
                  <span className="bifrost-spin h-3.5 w-3.5 shrink-0 rounded-full border-2 border-gray-300 border-t-gray-600" />
                  <span className="flex-1 text-[12.5px] leading-relaxed text-gray-600">
                    AI가 근본 원인을 분석하고 있습니다…
                    {analysisStage ? <span className="text-gray-400"> · {AGENT_STAGE_KO[analysisStage] ?? analysisStage}</span> : null}
                  </span>
                </div>
              ) : (
                <div className="flex items-center gap-3 rounded-lg bg-gray-50 px-3.5 py-3">
                  <span className="flex-1 text-[12.5px] leading-relaxed text-gray-500">
                    원인 분석이 아직 없습니다. 인시던트 발생 시 <b className="text-gray-700">자동 분석</b>이 실행됩니다 —
                    실패했거나 미완료면 다시 실행하세요. (채팅에서 진행)
                  </span>
                  <button
                    onClick={() => app.dispatchIncidentAnalysis(panelIncident.id, panelIncident.title)}
                    className="inline-flex shrink-0 items-center gap-1.5 rounded-md bg-[#0d0d0d] px-2.5 py-1.5 text-[11.5px] font-semibold text-white hover:bg-black"
                  >
                    <Icon name="play" size={11} /> 다시 분석
                  </button>
                </div>
              )}
            </div>

            {/* 경위 · Activity */}
            <div className="border-b border-gray-100 px-5 py-4">
              <div className="mb-3 flex items-center justify-between">
                <div className="text-[10.5px] font-bold uppercase tracking-wide text-gray-400">경위 · Activity</div>
                <span className="text-[10.5px] text-gray-400">{timelineEvents.length}건</span>
              </div>
              {eventsLoading ? (
                <div className="rounded-lg border border-dashed border-gray-200 py-6 text-center text-[12px] text-gray-400">
                  관련 이벤트를 불러오는 중…
                </div>
              ) : timelineEvents.length > 0 ? (
                <div>
                  {eventsError && (
                    <div className="mb-2 rounded-lg border border-[#ececec] bg-[#ededed] px-3 py-2 text-[12px] text-[#6b6b73]">
                      {eventsError} · 기존 로드 데이터로 표시합니다
                    </div>
                  )}
                  {timelineEvents.map((event, i) => (
                    <div key={event.id} className="grid grid-cols-[56px_14px_1fr] items-start">
                      <div className="pr-3 pt-0.5 text-right font-mono text-[10.5px] text-gray-400">{fmtTime(event.occurredAt)}</div>
                      <div className="relative flex justify-center">
                        <span className={cn('mt-0.5 h-2.5 w-2.5 rounded-full ring-2 ring-white', levelDotClass(event.level))} />
                        {i < timelineEvents.length - 1 && <span className="absolute bottom-[-12px] top-2 w-px bg-gray-100" />}
                      </div>
                      <div className="min-w-0 pb-3 pl-3">
                        <div className="break-words text-[12.5px] leading-snug text-gray-700">{event.message}</div>
                        <div className="mt-0.5 font-mono text-[10.5px] text-gray-400">{event.label}</div>
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

            {/* 권장 조치 */}
            <div className="border-b border-gray-100 px-5 py-4">
              <div className="mb-2 text-[10.5px] font-bold uppercase tracking-wide text-gray-400">권장 조치</div>
              {actionRunning && (
                <div className="mb-2 flex items-center gap-3 rounded-lg bg-gray-50 px-3.5 py-3">
                  <span className="bifrost-spin h-3.5 w-3.5 shrink-0 rounded-full border-2 border-gray-300 border-t-gray-600" />
                  <span className="flex-1 text-[12.5px] leading-relaxed text-gray-600">
                    AI가 조치를 실행하고 있습니다…
                    {actionStage ? <span className="text-gray-400"> · {AGENT_STAGE_KO[actionStage] ?? actionStage}</span> : null}
                  </span>
                </div>
              )}
              {reportsLoading ? (
                <div className="rounded-lg border border-dashed border-gray-200 py-6 text-center text-[12px] text-gray-400">
                  자동 분석 기반 조치를 불러오는 중…
                </div>
              ) : reportsError ? (
                <div className="rounded-lg border border-[#c0392b] bg-[#fcf3f2] px-3 py-2 text-[12px] text-[#c0392b]">{reportsError}</div>
              ) : actions.length > 0 ? (
                <div className="space-y-2">
                  {actions.map((action) => {
                    const runCandidate = buildRunCandidate(action, impactPipelines)
                    return (
                      <div key={action.key} className="rounded-lg border border-gray-200 px-3 py-2.5">
                        <div className="flex items-center gap-2">
                          <span className="min-w-0 flex-1 text-[12.5px] font-semibold text-gray-800">{action.label}</span>
                          {action.status && <StatusBadge status={action.status} label={actionStatusLabel(action.status)} />}
                        </div>
                        {action.detail && (
                          <div className="mt-1 line-clamp-2 text-[11.5px] leading-relaxed text-gray-500">{action.detail}</div>
                        )}
                        <div className="mt-2 flex flex-wrap items-center gap-2 text-[11px] text-gray-500">
                          {action.risk && (
                            <span className={cn('rounded px-1.5 py-0.5 text-[10px] font-semibold', semanticBadgeClass(semanticToken(action.risk)))}>
                              위험 {riskLabelKo(action.risk)}
                            </span>
                          )}
                          {action.estimatedTime && <span>{action.estimatedTime}</span>}
                          <button
                            onClick={() => {
                              const report = reports.find((r) => r.id === action.reportId)
                              if (report) openReport(report)
                            }}
                            className="font-medium text-gray-600 hover:underline"
                          >
                            근거 리포트
                          </button>
                          <button
                            onClick={() => runAction(action)}
                            disabled={!runCandidate}
                            title={runCandidate ? 'AI 채팅에서 실제 조치를 실행합니다' : '지원되는 실행 도구 또는 대상을 확인하지 못했습니다'}
                            className="ml-auto inline-flex items-center gap-1 rounded-md bg-[#0d0d0d] px-2.5 py-1 text-[11px] font-semibold text-white hover:bg-black disabled:bg-gray-200 disabled:text-gray-400"
                          >
                            <Icon name="play" size={11} />
                            실행
                          </button>
                        </div>
                      </div>
                    )
                  })}
                </div>
              ) : (
                <div className="rounded-lg border border-dashed border-gray-200 py-6 text-center text-[12px] text-gray-400">
                  기록된 권장 조치가 없습니다
                </div>
              )}
            </div>

            {/* 조치 이력 */}
            <div className="border-b border-gray-100 px-5 py-4">
              <div className="mb-2 flex items-center justify-between">
                <div className="text-[10.5px] font-bold uppercase tracking-wide text-gray-400">조치 이력</div>
                {actionLogs.length > 0 && <span className="text-[10.5px] text-gray-400">{actionLogs.length}건</span>}
              </div>
              {actionLogs.length > 0 ? (
                <div className="space-y-2">
                  {actionLogs.map((log) => (
                    <div key={log.key} className="rounded-lg border border-gray-200 px-3 py-2.5">
                      <div className="flex items-center gap-2">
                        <span className="min-w-0 flex-1 font-mono text-[11.5px] text-gray-700">{log.actionId}</span>
                        <StatusBadge status={log.status} label={actionStatusLabel(log.status)} />
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

            {/* 리포트 */}
            <div className="px-5 py-4">
              <div className="mb-2 flex items-center justify-between">
                <div className="text-[10.5px] font-bold uppercase tracking-wide text-gray-400">리포트</div>
                {reports.length > 0 && <span className="text-[10.5px] text-gray-400">{reports.length}건</span>}
              </div>
              {reportsLoading ? (
                <div className="rounded-lg border border-dashed border-gray-200 py-6 text-center text-[12px] text-gray-400">
                  리포트를 불러오는 중…
                </div>
              ) : reportsError ? (
                <div className="rounded-lg border border-[#c0392b] bg-[#fcf3f2] px-3 py-2 text-[12px] text-[#c0392b]">{reportsError}</div>
              ) : reports.length > 0 ? (
                <div className="space-y-2">
                  {reports.map((report) => (
                    <button
                      key={report.id}
                      onClick={() => openReport(report)}
                      disabled={reportLoadingId === report.id}
                      className="w-full rounded-lg border border-gray-200 px-3 py-2.5 text-left hover:border-gray-300 disabled:opacity-60"
                    >
                      <div className="flex items-center gap-2">
                        <Icon name="log" size={13} className="text-gray-400" />
                        <span className="min-w-0 flex-1 truncate text-[12.5px] font-semibold text-gray-800">{reportTitle(report)}</span>
                        {report.verified && <StatusBadge status="STABLE" label="verified" />}
                      </div>
                      <div className="mt-1 line-clamp-2 text-[11.5px] leading-relaxed text-gray-500">{reportSummary(report)}</div>
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
          </div>

          {/* SIDEBAR */}
          <div className="px-5 py-4">
            <SideGroup label="상태"><StatusBadge status={panelIncident.status} /></SideGroup>
            <SideGroup label="심각도"><StatusBadge status={panelIncident.severity} /></SideGroup>
            <SideGroup label="지속">
              <span className="text-[12.5px] text-gray-700">
                {fmtDuration(panelIncident.openedAt, durationEnd)}
                {!durationEnd && <span className="text-gray-400"> · 진행 중</span>}
              </span>
            </SideGroup>
            <SideGroup label="Opened"><span className="font-mono text-[11px] text-gray-600">{fmtDateTime(panelIncident.openedAt)}</span></SideGroup>
            <SideGroup label="Resolved"><span className="font-mono text-[11px] text-gray-600">{fmtDateTime(panelIncident.resolvedAt)}</span></SideGroup>
            <SideGroup label="영향 Pipeline">
              {impactPipelines.length > 0 || missingImpactIds.length > 0 ? (
                <div className="space-y-1">
                  {impactPipelines.map((pipeline) => (
                    <button
                      key={pipeline.id}
                      onClick={() => app.openPipeline(pipeline.id)}
                      className="flex items-center gap-1 text-[12.5px] font-medium text-brand-600 hover:underline"
                    >
                      {pipelineLabel(pipeline)} <Icon name="arrow-right" size={11} />
                    </button>
                  ))}
                  {missingImpactIds.map((id) => (
                    <div key={id} className="break-all font-mono text-[11px] text-gray-400">{id}</div>
                  ))}
                </div>
              ) : (
                <span className="text-[12px] text-gray-400">정보 없음</span>
              )}
            </SideGroup>
            <SideGroup label="인시던트 ID"><span className="break-all font-mono text-[11px] text-gray-500">{panelIncident.id}</span></SideGroup>
            <div>
              <button
                onClick={() => setRawOpen((v) => !v)}
                className="inline-flex items-center gap-1 text-[11px] font-medium text-gray-500 hover:text-gray-700"
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
        </div>
      </DetailShell>
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
          <div className="border-b border-[#c0392b] bg-[#fcf3f2] px-5 py-2 text-[12px] text-[#c0392b]">
            {error}
          </div>
        )}
        <div className="flex-1 overflow-auto px-5 py-4">
          {body ? (
            <div className="break-words rounded-lg bg-gray-50 p-3 text-[12.5px] leading-relaxed text-gray-700">
              <Markdown>{body}</Markdown>
            </div>
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
