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
  const data = text ? JSON.parse(text) : null

  if (!res.ok) {
    const code = data?.code ?? String(res.status)
    const message = data?.message ?? res.statusText
    throw new ApiError(res.status, code, message, data?.details ?? [])
  }
  return data as T
}

/* ---------------------------------------------------------------- 응답 타입 */

export interface AuthTokens {
  accessToken: string
  tokenType: string
  expiresInSeconds: number
  userId: string
  workspaceId: string
}
export interface MeResponse {
  userId: string
  email: string
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
  op: 'c' | 'u' | 'd' | 'r' | null
  before: Record<string, unknown> | null
  after: Record<string, unknown> | null
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
}
export interface EventResponse {
  id: string
  pipelineId: string | null
  level: 'INFO' | 'WARN' | 'ERROR'
  type: string
  message: string
  createdAt: string
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

  // pipelines (FR-003~005)
  listPipelines: (wsId: string, status?: string) =>
    request<PipelineResponse[]>('GET',
      `/api/v1/workspaces/${wsId}/pipelines${status ? `?status=${status}` : ''}`),
  createPipeline: (wsId: string, body: PipelineCreateInput) =>
    request<PipelineResponse>('POST', `/api/v1/workspaces/${wsId}/pipelines`, body),
  getPipeline: (wsId: string, id: string) =>
    request<PipelineResponse>('GET', `/api/v1/workspaces/${wsId}/pipelines/${id}`),
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
  pausePipeline: (wsId: string, id: string) =>
    request<PipelineResponse>('POST', `/api/v1/workspaces/${wsId}/pipelines/${id}/pause`),
  resumePipeline: (wsId: string, id: string) =>
    request<PipelineResponse>('POST', `/api/v1/workspaces/${wsId}/pipelines/${id}/resume`),
  deletePipeline: (wsId: string, id: string) =>
    request<void>('DELETE', `/api/v1/workspaces/${wsId}/pipelines/${id}`),

  // events (FR-019)
  listEvents: (wsId: string, level?: string, pipelineId?: string) => {
    const q = new URLSearchParams()
    if (level) q.set('level', level)
    if (pipelineId) q.set('pipelineId', pipelineId)
    const qs = q.toString()
    return request<EventResponse[]>('GET', `/api/v1/workspaces/${wsId}/events${qs ? `?${qs}` : ''}`)
  },

  // SSE 스트림 URL(EventSource는 헤더 불가 → access_token 쿼리)
  eventStreamUrl: (wsId: string) => {
    const token = getToken() ?? ''
    return `${BASE}/api/v1/workspaces/${wsId}/events/stream?access_token=${encodeURIComponent(token)}`
  },
}

export interface ConnectionTestInput {
  engine: string
  host: string
  port: number
  dbName: string
  user: string
  password: string
}
