/**
 * 백엔드 DTO ↔ 프론트 도메인 타입 매핑(#74/#73).
 *
 * 백엔드(operations-backend)는 engine `postgresql`/`mariadb`, 상태 소문자(creating/active...)를 쓰고,
 * 프론트는 기존 mock 타입(Node/Edge/Project, tech `postgres`)을 쓴다. 변환을 한 곳에 모은다.
 */
import type {
  CdcReadinessResponse,
  DatabaseResponse,
  EventResponse,
  PipelineResponse,
  WorkspaceResponse,
} from './api'
import type {
  ActivityEvent,
  CapabilityCheck,
  DbTech,
  Edge,
  LogLevel,
  Node,
  NodeStatus,
  Project,
} from '../data/types'

export function engineToTech(engine: string): DbTech {
  return engine.toLowerCase().startsWith('postgres') ? 'postgres' : 'mariadb'
}
export function techToEngine(tech: DbTech): string {
  return tech === 'postgres' ? 'postgresql' : 'mariadb'
}

export function workspaceToProject(
  ws: WorkspaceResponse,
  opts: { pipelineIds?: string[]; dbIds?: string[]; ownerId?: string } = {},
): Project {
  return {
    id: ws.id,
    name: ws.name,
    slug: ws.projectKey,
    ownerId: opts.ownerId ?? '',
    pipelineIds: opts.pipelineIds ?? [],
    dbIds: opts.dbIds ?? [],
    memberCount: 1,
    createdAt: (ws.createdAt ?? '').slice(0, 10),
    pipelineCount: ws.pipelineCount ?? 0,
    activeCount: ws.activePipelineCount ?? 0,
  }
}

/**
 * DB 표시 상태 단일 규칙(#807): 모든 화면(목록·상세·파이프라인)이 이 함수 하나로 통일한다.
 *  - error  : 연결이 끊겼을 때만 (connectionStatus === 'UNREACHABLE')
 *  - warning: source/sink readiness 중 WARNING 또는 BLOCKED가 하나라도 있을 때
 *  - healthy: 그 외 (전부 OK, 또는 아직 미점검 null — 알려진 문제 없음)
 * (#547·#734의 화면별 BLOCKED 분기 처리를 대체 — readiness 문제는 error가 아니라 warning.)
 */
export function dbDisplayStatus(
  connectionStatus?: string | null,
  cdcReadinessStatus?: string | null,
  sinkReadinessStatus?: string | null,
): NodeStatus {
  if (connectionStatus === 'UNREACHABLE') return 'error'
  const hasIssue = (s?: string | null) => s === 'WARNING' || s === 'BLOCKED'
  if (hasIssue(cdcReadinessStatus) || hasIssue(sinkReadinessStatus)) return 'warning'
  return 'healthy'
}

export function datasourceToNode(db: DatabaseResponse, index = 0): Node {
  const tech = engineToTech(db.engine)
  return {
    id: db.id,
    type: 'database',
    label: db.name,
    alias: db.name,
    tech,
    techLabel: db.engine,
    host: `${db.host}:${db.port}`,
    status: dbDisplayStatus(db.connectionStatus, db.cdcReadinessStatus, db.sinkReadinessStatus),
    connectionStatus: db.connectionStatus,
    cdcReadinessStatus: db.cdcReadinessStatus,
    sinkReadinessStatus: db.sinkReadinessStatus,
    x: 120,
    y: 120 + index * 130,
  }
}

/**
 * sink 노드 표시 상태(#807). 통일 규칙({@link dbDisplayStatus})에 위임한다 — error는 연결 끊김만,
 * source/sink readiness 문제(WARNING·BLOCKED)는 warning.
 */
export function sinkDisplayStatus(node: Node): NodeStatus {
  return dbDisplayStatus(node.connectionStatus, node.cdcReadinessStatus, node.sinkReadinessStatus)
}

const CDC_STATE: Record<string, CapabilityCheck['state']> = {
  OK: 'pass',
  WARNING: 'warn',
  BLOCKED: 'fail',
}

export function cdcChecksToCapability(resp: CdcReadinessResponse): CapabilityCheck[] {
  return resp.checks.map((c) => ({
    label: c.name,
    state: CDC_STATE[c.status] ?? 'warn',
    detail: c.hint ?? `${c.actual} (expected: ${c.expected})`,
  }))
}

export function pipelineToEdge(p: PipelineResponse): Edge {
  return {
    id: p.id,
    name: p.name,
    alias: p.alias ?? undefined,
    pattern: p.pattern,
    source: p.sourceDbId,
    sink: p.sinkDbId,
    table: { schema: p.schema, name: p.table },
    topic: p.topic ?? '',
    sourceConnector: p.sourceConnector,
    sinkConnector: p.sinkConnector,
    status: p.status,
  }
}

const LEVEL: Record<string, LogLevel> = { INFO: 'info', WARN: 'warning', ERROR: 'error' }

export function eventToActivity(e: EventResponse): ActivityEvent {
  return {
    id: e.id,
    time: (e.createdAt ?? '').slice(11, 19),
    level: LEVEL[e.level] ?? 'info',
    message: e.message,
    pipelineId: e.pipelineId ?? undefined,
  }
}
