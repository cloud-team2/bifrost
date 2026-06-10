/* ------------------------------------------------------------------ core */

export type DbTech = 'postgres' | 'mariadb'
export type NodeStatus = 'healthy' | 'warning' | 'error'
export type GroupState = 'STABLE' | 'REBALANCING' | 'DEAD' | 'EMPTY'

export interface Node {
  id: string
  type: 'database' | 'service'
  label: string
  alias?: string
  tech?: DbTech
  techLabel: string
  host: string
  status: NodeStatus
  x: number
  y: number
  /* database only */
  schema?: { tables: number; rows: string; size: string }
  cdc?: { wal_level: string; replication: string; slots: string; wal_senders: string }
  metrics?: { tps: number; lag_ms: number }
  checks?: CapabilityCheck[]
  /* service (consumer) only */
  lang?: string
  consumerGroup?: string
  subscribedTopic?: string
  lag?: number
  groupState?: GroupState
}

export interface CapabilityCheck {
  label: string
  state: 'pass' | 'warn' | 'fail'
  detail: string
}

export type EdgePattern = 'fan-out' | 'direct'
export type EdgeStatus = 'active' | 'lag' | 'error' | 'paused' | 'creating'

export interface Edge {
  id: string
  name: string
  alias?: string
  pattern: EdgePattern
  source: string
  sink: string | null
  consumers?: string[]
  table?: { schema: string; name: string }
  topic: string
  status: EdgeStatus
  partitions: number
  metrics?: { produce_rate: number; consume_rate: number; lag: number; error_pct: number }
  syncStatus?: { sourceRows: number; sinkRows: number; delta: number; lastSynced: string }
}

export interface Project {
  id: string
  name: string
  slug: string
  ownerId: string
  pipelineIds: string[]
  dbIds: string[]
  memberCount: number
  createdAt: string
  /** 프로젝트 목록 카드 요약(백엔드 workspace 응답 기준, #105). 상세 진입 시 실제 edges로 재동기화. */
  pipelineCount: number
  activeCount: number
}

export type Role = 'OWNER' | 'ADMIN' | 'MEMBER'

export interface User {
  name: string
  email: string
  role: Role
  initial: string
  joinedAt: string | null
  lastLoginAt: string | null
}

/* ---------------------------------------------------------------- events */

export type LogLevel = 'error' | 'warning' | 'info'

export interface ActivityEvent {
  id: string
  time: string
  level: LogLevel
  message: string
  pipelineId?: string
  incidentId?: string
}


/* --------------------------------------------------------------- cluster */

export interface Broker {
  id: number
  name: string
  status: NodeStatus
  leaderPartitions: number
  cpu: number
  disk: number
  netIn: number
  netOut: number
}

export interface ClusterTopic {
  name: string
  type: 'EDA' | 'CDC'
  project: string
  status: 'active' | 'lag' | 'error'
  partitions: number
  produceRate: number
  consumeRate: number
  replicaPct: number
}

export interface ConsumerGroupRow {
  name: string
  state: GroupState
  members: number
  totalLag: number
  lastCommit: string
  partitionStrategy: string
  instances: { id: string; host: string; partitions: number; lag: number }[]
}

export interface ClusterConnector {
  name: string
  kind: 'Source' | 'Sink'
  status: 'RUNNING' | 'PARTIALLY_FAILED' | 'FAILED' | 'PAUSED' | 'UNASSIGNED'
  project: string
  pipeline: string
  tasks: number
  recordsPerSec: number
}

/* --------------------------------------------------------------- settings */

export interface Member {
  name: string
  email: string
  role: Role
  joinedAt: string
}

export interface Account {
  name: string
  email: string
  role: Role
  joinedAt: string
  lastLogin: string
}

/* ----------------------------------------------------------------- chart */

export interface Point {
  // t는 카테고리 라벨(string) 또는 epoch ms(number, timeAxis 차트).
  t: number | string
  // null은 측정값 없음(그래프 끊김) — recharts가 gap으로 렌더(connectNulls=false).
  [k: string]: number | string | null
}
