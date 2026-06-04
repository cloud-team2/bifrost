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
  }
}

function cdcToNodeStatus(status: string | null): NodeStatus {
  if (status === 'BLOCKED') return 'error'
  if (status === 'WARNING') return 'warning'
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
    status: cdcToNodeStatus(db.cdcReadinessStatus),
    x: 120,
    y: 120 + index * 130,
  }
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
    alias: p.name,
    pattern: p.pattern,
    source: p.sourceDbId,
    sink: p.sinkDbId,
    table: { schema: p.schema, name: p.table },
    topic: p.topic ?? '',
    status: p.status,
    partitions: 3,
    metrics: { produce_rate: 0, consume_rate: 0, lag: 0, error_pct: 0 },
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
