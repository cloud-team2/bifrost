/**
 * Bifrost 플랫폼 API 클라이언트(#72/#74/#73).
 *
 * Spring Boot operations-backend `/api/v1`를 호출한다. JWT는 localStorage에 보관하고
 * 모든 요청에 `Authorization: Bearer`로 붙인다. 실패 응답은 `{code,message,details}` 봉투를
 * `ApiError`로 변환한다(error-codes.md). 개발 시 vite proxy, 배포 시 nginx가 `/api`를 백엔드로 전달한다.
 */

const BASE = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')
const TOKEN_KEY = 'bifrost.token'

export class ApiError extends Error {
  code: string
  details: { field: string; reason: string }[]
  status: number
  constructor(status: number, code: string, message: string, details: { field: string; reason: string }[] = []) {
    super(message)
    this.status = status
    this.code = code
    this.details = details
  }
}

export function extractApiErrorPayload(data: unknown, status: number, statusText: string) {
  const record = asApiRecord(data)
  const error = asApiRecord(record?.error)
  return {
    code: apiRecordString(record, 'code') ?? apiRecordString(error, 'code') ?? String(status),
    message: firstApiString(
      apiRecordString(record, 'message'),
      apiRecordString(error, 'message'),
      apiRecordString(record, 'detail'),
      statusText,
    ) ?? String(status),
    details: apiRecordDetails(record, 'details') ?? apiRecordDetails(error, 'details') ?? [],
  }
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}
export function setToken(token: string | null) {
  if (token) localStorage.setItem(TOKEN_KEY, token)
  else localStorage.removeItem(TOKEN_KEY)
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const token = getToken()
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })

  if (res.status === 204) return undefined as T

  const text = await res.text()
  const data = text ? parseResponseJson(text, res.ok) : null

  if (!res.ok) {
    const error = extractApiErrorPayload(data, res.status, res.statusText)
    throw new ApiError(res.status, error.code, error.message, error.details)
  }
  return data as T
}

function asApiRecord(value: unknown): Record<string, unknown> | null {
  return value != null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null
}

function apiRecordString(record: Record<string, unknown> | null, key: string): string | null {
  const value = record?.[key]
  return typeof value === 'string' ? value : null
}

function firstApiString(...values: (string | null)[]): string | null {
  for (const value of values) {
    const trimmed = value?.trim()
    if (trimmed) return trimmed
  }
  return null
}

function parseResponseJson(text: string, ok: boolean): unknown {
  try {
    return JSON.parse(text)
  } catch (error) {
    if (ok) throw error
    return null
  }
}

function apiRecordDetails(record: Record<string, unknown> | null, key: string): { field: string; reason: string }[] | null {
  const value = record?.[key]
  if (!Array.isArray(value)) return null
  const details = value.flatMap((item) => {
    const detail = asApiRecord(item)
    const field = apiRecordString(detail, 'field')
    const reason = apiRecordString(detail, 'reason')
    return field && reason ? [{ field, reason }] : []
  })
  return details.length > 0 ? details : null
}

/* ---------------------------------------------------------------- 응답 타입 */

export interface AuthTokens {
  accessToken: string
  tokenType: string
  expiresInSeconds: number
  userId: string
  workspaceId: string
}
export interface RegisterInput {
  email: string
  name: string | null
  password: string
  workspaceName: string
  namespace: string
}
export interface MeResponse {
  userId: string
  email: string
  name: string | null
  role: 'OWNER' | 'ADMIN' | 'MEMBER'
  joinedAt: string | null
  lastLoginAt: string | null
  workspaceId: string
  workspaceName: string
  namespace: string
  workspaceStatus: string
}
export interface WorkspaceResponse {
  id: string
  name: string
  projectKey: string
  /** 일반 설정 timezone(#145). 미설정 시 null. */
  timezone: string | null
  status: string
  createdAt: string
  pipelineCount: number
  activePipelineCount: number
}

/** 워크스페이스 멤버 역할(#145). OWNER는 단일·강등/제거 불가. */
export type WorkspaceMemberRole = 'OWNER' | 'ADMIN' | 'MEMBER'
/** 프로젝트 멤버(#145). */
export interface ProjectMemberResponse {
  workspaceId: string
  userId: string
  email: string | null
  role: WorkspaceMemberRole
  joinedAt: string
}
export type FailureReason =
  | 'CONNECTION_REFUSED' | 'AUTH_FAILED' | 'DB_NOT_FOUND' | 'TIMEOUT' | 'UNKNOWN' | null
export interface ConnectionTestResponse {
  success: boolean
  reason: FailureReason
  message: string
  latencyMs: number
}
export interface DatabaseResponse {
  id: string
  name: string
  engine: string
  host: string
  port: number
  dbName: string
  username: string
  password: string
  cdcReadinessStatus: 'OK' | 'WARNING' | 'BLOCKED' | null
  /** sink(JDBC 쓰기) 준비도(#547). 소스용 cdcReadinessStatus와 별개. null=미점검. */
  sinkReadinessStatus: 'OK' | 'WARNING' | 'BLOCKED' | null
  /** 연결 헬스 주기 프로브(#179). null=아직 미프로브. */
  connectionStatus: 'HEALTHY' | 'UNREACHABLE' | null
  connectionError: string | null
  connectionCheckedAt: string | null
  roles: string[]
  createdAt: string
}
export type CdcStatus = 'OK' | 'WARNING' | 'BLOCKED'
export interface CdcCheck {
  name: string
  status: CdcStatus
  actual: string
  expected: string
  hint: string | null
}
export interface CdcReadinessResponse {
  overallStatus: CdcStatus
  checks: CdcCheck[]
}
export interface SchemaColumn {
  name: string
  type: string
  nullable: boolean
  primaryKey: boolean
  indexed: boolean
}
export interface SchemaTable {
  schema: string
  name: string
  columns: SchemaColumn[]
}
export interface DatabaseSchemaResponse {
  tables: SchemaTable[]
}
export interface DatabaseMetricsResponse {
  tps: number
  queryResponseMs: number
  activeConnections: number
  stub: boolean
}
export interface PipelineResponse {
  id: string
  name: string
  pattern: 'fan-out' | 'direct'
  status: 'creating' | 'active' | 'lag' | 'error' | 'paused'
  statusMessage: string | null
  sourceDbId: string
  sinkDbId: string | null
  schema: string
  table: string
  topic: string | null
  sourceConnector: string | null
  sinkConnector: string | null
  createdAt: string
}
export interface SecretReference {
  namespace: string
  secretName: string
  keyRefs: Record<string, string>
  availableKeys: string[]
}
export interface AuthTemplate {
  type: string
  securityProtocol: string
  properties: Record<string, string>
  credentialReference: SecretReference
}
export interface TopicRef {
  name: string
  sourceTable: string | null
  role: string
}
export interface ConnectionGuideResponse {
  pipelineId: string
  pipelineName: string
  bootstrapServers: string
  recommendedGroupId: string
  authenticationMethod: string
  credentialReference: SecretReference
  authenticationTemplates: AuthTemplate[]
  topics: TopicRef[]
}
export interface TableMappingEntry {
  sourceTable: string
  kafkaTopic: string
  sinkTable: string
}
export interface TableMappingResponse {
  pipelineId: string
  sourceConnector: string
  sinkConnector: string
  mappings: TableMappingEntry[]
}
/** 토픽 파티션 정보(#126). */
export interface TopicInfoResponse {
  name: string
  isrPct: number
  retentionMs: number
  partitions: { id: number; leader: string; beginOffset: number; endOffset: number }[]
}
/** Consumer group 상세(#126). */
export interface ConsumerGroupInfo {
  name: string
  state: string
  members: number
  totalLag: number
  lastCommit: number
  partitionOffsets: { partition: number; member: string | null; committed: number; endOffset: number }[]
}
/** Kafka 메시지 레코드(#126). */
export interface KafkaMessageRecord {
  partition: number
  offset: number
  tsMs: number
  key: string | null
  sizeBytes: number | null
  op: 'c' | 'u' | 'd' | 'r' | null
  before: Record<string, unknown> | null
  after: Record<string, unknown> | null
}
/** 메시지 브라우저 페이지(#509). 단일 파티션 오프셋 윈도우 + 페이징 메타. */
export interface MessagePageResponse {
  records: KafkaMessageRecord[]
  partition: number
  startOffset: number
  beginOffset: number
  endOffset: number
  hasOlder: boolean
  hasNewer: boolean
}
/** 파이프라인 메트릭(#126). */
export interface PipelineMetricsResponse {
  produceRate: number
  consumeRate: number
  lagMessages: number
  errorPct: number
}
/** 처리량 추이 한 점(#126, Prometheus range query). */
export interface ThroughputPoint {
  timestamp: number
  produceRate: number
  consumeRate: number
}
/** 단일 값 시계열 한 점(#126). 소스 지연(ms)·미동기화 row 추이 공용. */
export interface MetricPoint {
  timestamp: number
  value: number
}
/** 이벤트 타입 분포 시계열 한 점(#126). */
export interface EventDistPoint {
  timestamp: number
  insert: number
  update: number
  delete: number
}

/* ── Cluster 화면(#213) ───────────────────────────────────────────── */
export interface BrokerInfo {
  id: number
  host: string
  port: number
  controller: boolean
  leaderPartitions: number
  logDirBytes: number
  cpuPct: number | null
  heapUsedBytes: number | null
  heapMaxBytes: number | null
  diskUsedPct: number | null
  netInBytesPerSec: number | null
  netOutBytesPerSec: number | null
  status: 'healthy' | 'warning' | 'error'
}
export interface KafkaClusterResponse {
  controllerId: number
  brokerCount: number
  totalPartitions: number
  underReplicated: number
  offlinePartitions: number
  brokers: BrokerInfo[]
  status?: 'healthy' | 'warning' | 'error'
  message?: string | null
}
export interface ConnectWorker {
  name: string
  host: string | null
  state: string
  heapUsedBytes: number | null
  heapMaxBytes: number | null
  cpuPct: number | null
  gcSeconds: number | null
  version: string | null
}
export interface ConnectConnectorRow {
  name: string
  kind: string
  status: string
  pipeline: string
  tasks: number
}
export interface ConnectPlugin {
  className: string
  type: string
  version: string
}
export interface ConnectClusterResponse {
  workers: ConnectWorker[]
  connectors: ConnectConnectorRow[]
  plugins: ConnectPlugin[]
  config: Record<string, string>
}
/** 파이프라인 커넥터(#107). state/lastError/updatedAt는 watcher가 갱신(미반영 시 null). */
export interface ConnectorInfo {
  name: string
  kind: 'source' | 'sink'
  connectorClass: string
  state: string | null
  tasksMax: number
  lastError: string | null
  updatedAt: string | null
}
/** 파이프라인 동기화 상태(#107). source/sink 실제 행수. 미존재/실패 시 -1. */
export interface SyncStatusResponse {
  sourceRows: number
  sinkRows: number
  delta: number
  checkedAt: string
  // #501: 완료 판정용 — sink consumer lag(-1=미소비), 토픽 end offset, sink 커넥터 FAILED 여부
  lag: number
  endOffset: number
  sinkFailed: boolean
}
export interface EventResponse {
  id: string
  pipelineId: string | null
  incidentId: string | null
  level: 'INFO' | 'WARN' | 'ERROR'
  type: string
  message: string
  createdAt: string
}

export interface TraceSpanResponse {
  name: string
  service: string
  durationMs: number
  status: string          // 'ok' | 'error'
  error: string | null
}
export interface TraceSummaryResponse {
  traceId: string | null
  pipelineId: string | null
  status: string          // 'ok' | 'error' | 'unknown'
  durationMs: number
  spans: TraceSpanResponse[]
  note: string | null
}

/** incident 목록/상세(S5). operations-backend IncidentResponse record와 동일 필드. */
export interface IncidentResponse {
  id: string
  tenantId: string
  groupingKey: string
  severity: string
  status: string
  title: string
  rca: string | null
  sourceType: string | null
  sourceId: string | null
  openedAt: string
  resolvedAt: string | null
}
export interface IncidentReportResponse {
  id: string
  runId: string
  incidentId: string | null
  rootCauseId: string | null
  confidence: number | null
  verified: boolean
  body: unknown
  createdAt: string | null
}
export interface IncidentDetailResponse {
  incident: IncidentResponse
  events: EventResponse[]
  impactPipelineIds: string[]
  reports: IncidentReportResponse[]
}
/** KRaft/리소스 이벤트(S5). operations-backend ResourceEventResponse record와 동일 필드. */
export interface ResourceEventResponse {
  eventType: string
  resource: string
  detail: string
  occurredAt: string
}

/* ── Workspace Settings(#253) ─────────────────────────────────────── */
export type NotificationSeverityPolicy = 'all' | 'warning' | 'error'
export interface NotificationSettingsResponse {
  slackEnabled: boolean
  slackWebhookUrl: string | null
  emailRecipients: string[]
  severity: NotificationSeverityPolicy
}
export interface NotificationSettingsRequest {
  slackEnabled?: boolean
  slackWebhookUrl?: string | null
  emailRecipients?: string[]
  severity?: NotificationSeverityPolicy
}
export interface ThresholdSettingsResponse {
  warning: number
  critical: number
}
export interface ThresholdSettingsRequest {
  warning?: number
  critical?: number
}
export interface AiPolicySettingsResponse {
  autonomous: boolean
  approvalWaitMinutes: number
  prodLock: boolean
}
export interface AiPolicySettingsRequest {
  autonomous?: boolean
  approvalWaitMinutes?: number
  prodLock?: boolean
}
export type KafkaPrincipalStatus = 'ACTIVE' | 'INACTIVE' | 'REVOKED'
export interface KafkaPrincipalResponse {
  id: string
  workspaceId: string
  username: string
  secretRef: string | null
  status: KafkaPrincipalStatus
  createdAt: string
  deactivatedAt: string | null
  revokedAt: string | null
}
export interface KafkaPrincipalCreateRequest {
  username: string
  secretRef?: string | null
}


/* ── Agent Run API(#252) ───────────────────────────────────────────── */
export type AgentRunMode = 'simple_query' | 'incident_analysis' | 'action_execution' | 'approval_decision'
export type AgentRunStatus = 'running' | 'waiting_for_approval' | 'completed' | 'failed' | 'cancelled'
export type ActionRunRisk = 'read_only' | 'low' | 'medium' | 'high' | 'forbidden'
export type ActionRunType = 'runtime_tool' | 'workflow_action' | 'composite_action' | 'notification' | 'escalation'
export type AgentStreamingEventType =
  | 'run_started'
  | 'agent_started'
  | 'agent_completed'
  | 'tool_call_started'
  | 'tool_call_completed'
  | 'tool_call_failed'
  | 'evidence_collected'
  | 'report_preview_available'
  | 'report_preview'
  | 'partial_result'
  | 'approval_required'
  | 'change_management_required'
  | 'execution_started'
  | 'execution_completed'
  | 'verification_completed'
  | 'run_completed'
  | 'debug_trace'

export interface AgentRunCreateInput {
  project_id: string
  mode?: AgentRunMode | null
  message?: string | null
  incident_id?: string | null
  remediation_requested?: boolean
  stream?: boolean
  action_candidate?: ActionRunCandidateInput | null
}
export interface ActionRunCandidateInput {
  action_id: string
  action_type: ActionRunType
  action_name: string
  root_cause_id?: string | null
  risk: ActionRunRisk
  reason: string
  expected_effect?: string | null
  rollback_plan?: string | null
  estimated_duration?: string | null
  tool_name?: string | null
  tool_params?: Record<string, unknown> | null
}
export interface AgentRunCreateResponse {
  run_id: string
  event_stream_url: string
  status: AgentRunStatus
}
export interface AgentRunEvent {
  event_id: string
  run_id: string
  timestamp: string
  type: AgentStreamingEventType
  agent: string | null
  message: string
  payload: Record<string, unknown>
}
export interface AgentRunApproval {
  approval_id: string
  action_id: string
  params_hash: string
}
export interface AgentRunApprovalsResponse {
  run_id: string
  pending: AgentRunApproval[]
}
export type ApprovalDecisionValue = 'approved' | 'rejected'
export interface ApprovalDecisionInput {
  decision: ApprovalDecisionValue
  comment?: string | null
}
export interface ApprovalDecisionResponse {
  approval_id: string
  status: ApprovalDecisionValue | 'pending'
}
export interface AgentToolCatalogItem {
  name: string
  operation: string
  risk: string
  method: string
  path_template: string
}
export interface AgentToolDetail extends AgentToolCatalogItem {
  params_schema: {
    required?: string[]
    properties?: Record<string, unknown>
  }
  result_schema: Record<string, unknown>
}
export interface AgentToolCatalogResponse {
  tools: AgentToolCatalogItem[]
}
export interface AgentToolExecuteResponse {
  tool_result: Record<string, unknown>
  result: unknown
}

interface FastApiEnvelope<T> {
  ok: boolean
  request_id?: string
  data?: T | null
  error?: { code?: string; message?: string; details?: { field: string; reason: string }[] } | null
}

async function agentRequest<T>(method: string, path: string, body?: unknown): Promise<T> {
  const envelope = await request<FastApiEnvelope<T> | T>(method, path, body)
  if (envelope && typeof envelope === 'object' && 'ok' in envelope) {
    const wrapped = envelope as FastApiEnvelope<T>
    if (!wrapped.ok) {
      const error = extractApiErrorPayload(wrapped, 400, 'Agent API request failed')
      throw new ApiError(400, error.code, error.message, error.details)
    }
    return wrapped.data as T
  }
  return envelope as T
}

export interface DbRegisterInput {
  name: string
  engine: string
  host: string
  port: number
  dbName: string
  username: string
  password: string
}
export interface PipelineCreateInput {
  name: string
  pattern: 'fan-out' | 'direct'
  sourceDbId: string
  sinkDbId?: string | null
  schema: string
  table: string
}

/* ------------------------------------------------------------------- 엔드포인트 */

export const api = {
  // auth (FR-001)
  register: (body: RegisterInput) =>
    request<AuthTokens>('POST', '/api/v1/auth/register', body),
  login: (email: string, password: string) =>
    request<AuthTokens>('POST', '/api/v1/auth/login', { email, password }),
  me: () => request<MeResponse>('GET', '/api/v1/auth/me'),
  refresh: () => request<AuthTokens>('POST', '/api/v1/auth/refresh'),

  // workspaces (FR-002)
  listWorkspaces: () => request<WorkspaceResponse[]>('GET', '/api/v1/workspaces'),
  createWorkspace: (name: string) =>
    request<WorkspaceResponse>('POST', '/api/v1/workspaces', { name }),
  getWorkspace: (wsId: string) => request<WorkspaceResponse>('GET', `/api/v1/workspaces/${wsId}`),
  // 일반 설정(#145): name/timezone PATCH (OWNER/ADMIN)
  updateWorkspace: (wsId: string, body: { name?: string; timezone?: string | null }) =>
    request<WorkspaceResponse>('PATCH', `/api/v1/workspaces/${wsId}`, body),

  // members (#145) — 멤버 작업은 OWNER/ADMIN만
  listMembers: (wsId: string) =>
    request<ProjectMemberResponse[]>('GET', `/api/v1/workspaces/${wsId}/members`),
  addMember: (wsId: string, email: string, role: WorkspaceMemberRole) =>
    request<ProjectMemberResponse>('POST', `/api/v1/workspaces/${wsId}/members`, { email, role }),
  updateMemberRole: (wsId: string, userId: string, role: WorkspaceMemberRole) =>
    request<ProjectMemberResponse>('PATCH', `/api/v1/workspaces/${wsId}/members/${userId}`, { role }),
  removeMember: (wsId: string, userId: string) =>
    request<void>('DELETE', `/api/v1/workspaces/${wsId}/members/${userId}`),

  // databases (FR-013~016)
  listDatabases: (wsId: string) =>
    request<DatabaseResponse[]>('GET', `/api/v1/workspaces/${wsId}/databases`),
  testConnection: (wsId: string, body: ConnectionTestInput) =>
    request<ConnectionTestResponse>('POST', `/api/v1/workspaces/${wsId}/databases/connection-test`, body),
  registerDatabase: (wsId: string, body: DbRegisterInput) =>
    request<DatabaseResponse>('POST', `/api/v1/workspaces/${wsId}/databases`, body),
  getDatabase: (wsId: string, dbId: string) =>
    request<DatabaseResponse>('GET', `/api/v1/workspaces/${wsId}/databases/${dbId}`),
  deleteDatabase: (wsId: string, dbId: string) =>
    request<void>('DELETE', `/api/v1/workspaces/${wsId}/databases/${dbId}`),
  cdcReadiness: (wsId: string, dbId: string) =>
    request<CdcReadinessResponse>('GET', `/api/v1/workspaces/${wsId}/databases/${dbId}/cdc-readiness`),
  sinkReadiness: (wsId: string, dbId: string) =>
    request<CdcReadinessResponse>('GET', `/api/v1/workspaces/${wsId}/databases/${dbId}/sink-readiness`),
  databaseSchema: (wsId: string, dbId: string) =>
    request<DatabaseSchemaResponse>('GET', `/api/v1/workspaces/${wsId}/databases/${dbId}/schema`),
  databaseMetrics: (wsId: string, dbId: string) =>
    request<DatabaseMetricsResponse>('GET', `/api/v1/workspaces/${wsId}/databases/${dbId}/metrics`),

  // pipelines (FR-003~005)
  listPipelines: (wsId: string, status?: string) =>
    request<PipelineResponse[]>('GET',
      `/api/v1/workspaces/${wsId}/pipelines${status ? `?status=${status}` : ''}`),
  createPipeline: (wsId: string, body: PipelineCreateInput) =>
    request<PipelineResponse>('POST', `/api/v1/workspaces/${wsId}/pipelines`, body),
  getPipeline: (wsId: string, id: string) =>
    request<PipelineResponse>('GET', `/api/v1/workspaces/${wsId}/pipelines/${id}`),
  getConnectionGuide: (wsId: string, id: string) =>
    request<ConnectionGuideResponse>('GET', `/api/v1/workspaces/${wsId}/pipelines/${id}/connection-guide`),
  getTableMapping: (wsId: string, id: string) =>
    request<TableMappingResponse>('GET', `/api/v1/workspaces/${wsId}/pipelines/${id}/table-mapping`),
  listPipelineConnectors: (wsId: string, id: string) =>
    request<ConnectorInfo[]>('GET', `/api/v1/workspaces/${wsId}/pipelines/${id}/connectors`),
  pipelineSyncStatus: (wsId: string, id: string) =>
    request<SyncStatusResponse>('GET', `/api/v1/workspaces/${wsId}/pipelines/${id}/sync-status`),
  pipelineTopicInfo: (wsId: string, id: string) =>
    request<TopicInfoResponse>('GET', `/api/v1/workspaces/${wsId}/pipelines/${id}/topic-info`),
  pipelineConsumerGroups: (wsId: string, id: string) =>
    request<ConsumerGroupInfo[]>('GET', `/api/v1/workspaces/${wsId}/pipelines/${id}/consumer-groups`),
  pipelineMessages: (wsId: string, id: string, limit = 20) =>
    request<KafkaMessageRecord[]>('GET', `/api/v1/workspaces/${wsId}/pipelines/${id}/messages?limit=${limit}`),
  // (#509) 단일 파티션 오프셋 페이징. startOffset 미지정 → 해당 파티션 최신 N.
  pipelineMessagePage: (wsId: string, id: string, partition: number, startOffset: number | null, limit = 50) =>
    request<MessagePageResponse>('GET', `/api/v1/workspaces/${wsId}/pipelines/${id}/messages/page?partition=${partition}`
      + (startOffset != null ? `&startOffset=${startOffset}` : '') + `&limit=${limit}`),
  pipelineMetrics: (wsId: string, id: string) =>
    request<PipelineMetricsResponse>('GET', `/api/v1/workspaces/${wsId}/pipelines/${id}/metrics`),
  pipelineThroughput: (wsId: string, id: string, minutes = 30) =>
    request<ThroughputPoint[]>('GET', `/api/v1/workspaces/${wsId}/pipelines/${id}/metrics/throughput?minutes=${minutes}`),
  pipelineSourceDelay: (wsId: string, id: string, minutes = 120) =>
    request<MetricPoint[]>('GET', `/api/v1/workspaces/${wsId}/pipelines/${id}/metrics/source-delay?minutes=${minutes}`),
  pipelineUnsynced: (wsId: string, id: string, minutes = 120) =>
    request<MetricPoint[]>('GET', `/api/v1/workspaces/${wsId}/pipelines/${id}/metrics/unsynced?minutes=${minutes}`),
  pipelineEventDist: (wsId: string, id: string, minutes = 60) =>
    request<EventDistPoint[]>('GET', `/api/v1/workspaces/${wsId}/pipelines/${id}/metrics/event-distribution?minutes=${minutes}`),
  pipelineTrace: (wsId: string, id: string, traceId?: string) =>
    request<TraceSummaryResponse>(
      'GET',
      `/api/v1/workspaces/${wsId}/pipelines/${id}/trace${traceId ? `?traceId=${encodeURIComponent(traceId)}` : ''}`,
    ),
  // 데이터플레인 추적 토글(#438/#565) — 현재 상태 조회 / on·off
  pipelineDataplaneTracing: (wsId: string, id: string) =>
    request<{ enabled: boolean }>(
      'GET',
      `/api/v1/workspaces/${wsId}/pipelines/${id}/dataplane-tracing`,
    ),
  setPipelineDataplaneTracing: (wsId: string, id: string, enabled: boolean) =>
    request<void>(
      'POST',
      `/api/v1/workspaces/${wsId}/pipelines/${id}/dataplane-tracing?enabled=${enabled}`,
    ),
  // cluster (#213) — 워크스페이스 공유 인프라, 스코프 없음
  clusterKafka: () => request<KafkaClusterResponse>('GET', `/api/v1/clusters/kafka`),
  clusterThroughput: (minutes = 30) =>
    request<ThroughputPoint[]>('GET', `/api/v1/clusters/kafka/throughput?minutes=${minutes}`),
  clusterConnect: () => request<ConnectClusterResponse>('GET', `/api/v1/clusters/connect`),

  // monitoring incidents/resource-events (#253)
  listIncidents: (wsId: string, status?: string) => {
    const q = status ? `?status=${encodeURIComponent(status)}` : ''
    return request<IncidentResponse[]>('GET', `/api/v1/workspaces/${wsId}/monitoring/incidents${q}`)
  },
  getIncident: (wsId: string, incidentId: string) =>
    request<IncidentResponse>('GET', `/api/v1/workspaces/${wsId}/monitoring/incidents/${incidentId}`),
  getIncidentDetail: (wsId: string, incidentId: string) =>
    request<IncidentDetailResponse>('GET', `/api/v1/workspaces/${wsId}/monitoring/incidents/${incidentId}/detail`),
  listIncidentReports: (wsId: string, incidentId: string) =>
    request<IncidentReportResponse[]>('GET', `/api/v1/workspaces/${wsId}/monitoring/incidents/${incidentId}/reports`),
  getIncidentReport: (wsId: string, incidentId: string, reportId: string) =>
    request<IncidentReportResponse>('GET', `/api/v1/workspaces/${wsId}/monitoring/incidents/${incidentId}/reports/${reportId}`),
  listResourceEvents: (wsId: string) =>
    request<ResourceEventResponse[]>('GET', `/api/v1/workspaces/${wsId}/monitoring/resource-events`),

  pausePipeline: (wsId: string, id: string) =>
    request<PipelineResponse>('POST', `/api/v1/workspaces/${wsId}/pipelines/${id}/pause`),
  resumePipeline: (wsId: string, id: string) =>
    request<PipelineResponse>('POST', `/api/v1/workspaces/${wsId}/pipelines/${id}/resume`),
  deletePipeline: (wsId: string, id: string) =>
    request<void>('DELETE', `/api/v1/workspaces/${wsId}/pipelines/${id}`),

  // agent runs (#252) — FastAPI Agent facade
  createAgentRun: (body: AgentRunCreateInput) =>
    agentRequest<AgentRunCreateResponse>('POST', '/api/v1/agent/runs', { ...body, stream: body.stream ?? true }),
  agentRunEventUrl: (runId: string) => {
    const token = getToken() ?? ''
    const q = token ? `?access_token=${encodeURIComponent(token)}` : ''
    return `${BASE}/api/v1/agent/runs/${runId}/events${q}`
  },
  listAgentRunApprovals: (runId: string) =>
    agentRequest<AgentRunApprovalsResponse>('GET', `/api/v1/agent/runs/${runId}/approvals`),
  approvalDecision: (approvalId: string, body: ApprovalDecisionInput) =>
    agentRequest<ApprovalDecisionResponse>('POST', `/api/v1/approvals/${approvalId}/decision`, body),
  listAgentTools: () =>
    agentRequest<AgentToolCatalogResponse>('GET', '/api/v1/tools'),
  getAgentTool: (toolName: string) =>
    agentRequest<AgentToolDetail>('GET', `/api/v1/tools/${toolName}`),
  executeAgentTool: (toolName: string, body: { project_id: string; params?: Record<string, unknown> }) =>
    agentRequest<AgentToolExecuteResponse>('POST', `/api/v1/tools/${toolName}/execute`, body),

  // events (FR-019)
  listEvents: (wsId: string, level?: string, pipelineId?: string, incidentId?: string) => {
    const q = new URLSearchParams()
    if (level) q.set('level', level)
    if (pipelineId) q.set('pipelineId', pipelineId)
    if (incidentId) q.set('incidentId', incidentId)
    const qs = q.toString()
    return request<EventResponse[]>('GET', `/api/v1/workspaces/${wsId}/events${qs ? `?${qs}` : ''}`)
  },

  // SSE 스트림 URL(EventSource는 헤더 불가 → access_token 쿼리)
  eventStreamUrl: (wsId: string) => {
    const token = getToken() ?? ''
    return `${BASE}/api/v1/workspaces/${wsId}/events/stream?access_token=${encodeURIComponent(token)}`
  },

  // workspace settings (#253)
  getNotificationSettings: (wsId: string) =>
    request<NotificationSettingsResponse>('GET', `/api/v1/workspaces/${wsId}/settings/notifications`),
  updateNotificationSettings: (wsId: string, body: NotificationSettingsRequest) =>
    request<NotificationSettingsResponse>('PUT', `/api/v1/workspaces/${wsId}/settings/notifications`, body),
  getThresholdSettings: (wsId: string) =>
    request<ThresholdSettingsResponse>('GET', `/api/v1/workspaces/${wsId}/settings/thresholds`),
  updateThresholdSettings: (wsId: string, body: ThresholdSettingsRequest) =>
    request<ThresholdSettingsResponse>('PUT', `/api/v1/workspaces/${wsId}/settings/thresholds`, body),
  getAiPolicySettings: (wsId: string) =>
    request<AiPolicySettingsResponse>('GET', `/api/v1/workspaces/${wsId}/settings/ai-policy`),
  updateAiPolicySettings: (wsId: string, body: AiPolicySettingsRequest) =>
    request<AiPolicySettingsResponse>('PUT', `/api/v1/workspaces/${wsId}/settings/ai-policy`, body),
  listKafkaPrincipals: (wsId: string) =>
    request<KafkaPrincipalResponse[]>('GET', `/api/v1/workspaces/${wsId}/kafka/principals`),
  createKafkaPrincipal: (wsId: string, body: KafkaPrincipalCreateRequest) =>
    request<KafkaPrincipalResponse>('POST', `/api/v1/workspaces/${wsId}/kafka/principals`, body),
  deactivateKafkaPrincipal: (wsId: string, id: string) =>
    request<KafkaPrincipalResponse>('POST', `/api/v1/workspaces/${wsId}/kafka/principals/${id}/deactivate`),
  revokeKafkaPrincipal: (wsId: string, id: string) =>
    request<KafkaPrincipalResponse>('POST', `/api/v1/workspaces/${wsId}/kafka/principals/${id}/revoke`),
  rotateKafkaPrincipal: (wsId: string, id: string) =>
    request<KafkaPrincipalResponse>('POST', `/api/v1/workspaces/${wsId}/kafka/principals/${id}/rotate`),
}

export interface ConnectionTestInput {
  engine: string
  host: string
  port: number
  dbName: string
  user: string
  password: string
}
