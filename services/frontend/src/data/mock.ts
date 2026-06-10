import type {
  Broker,
  ClusterConnector,
  ClusterTopic,
  ConsumerGroupRow,
  User,
} from './types'

export const CURRENT_USER: User = {
  name: '김민준',
  email: 'ta@bifrost.io',
  role: 'ADMIN',
  initial: '김',
  joinedAt: null,
  lastLoginAt: null,
}

// 백엔드 dev seed 계정과 일치(DevDataSeeder: ta@bifrost.io / ta123456). 로그인 화면 자동입력용.
export const DEMO_ACCOUNTS: { user: User; password: string }[] = [
  { user: CURRENT_USER, password: 'ta123456' },
]

export const APP_VERSION = 'v0.8.2'

/* --------------------------------------------------------------- cluster */

export const BROKERS: Broker[] = [
  { id: 1, name: 'broker-1', status: 'warning', leaderPartitions: 142, cpu: 78, disk: 54, netIn: 38, netOut: 41 },
  { id: 2, name: 'broker-2', status: 'healthy', leaderPartitions: 138, cpu: 52, disk: 61, netIn: 31, netOut: 34 },
  { id: 3, name: 'broker-3', status: 'healthy', leaderPartitions: 140, cpu: 49, disk: 47, netIn: 29, netOut: 30 },
]

export const CLUSTER_TOPICS: ClusterTopic[] = [
  { name: 'eda.orders.events', type: 'EDA', project: 'T커머스 주문 파이프라인', status: 'active', partitions: 6, produceRate: 1840, consumeRate: 1835, replicaPct: 100 },
  { name: 'cdc.users.cdc', type: 'CDC', project: 'T커머스 주문 파이프라인', status: 'active', partitions: 3, produceRate: 420, consumeRate: 418, replicaPct: 100 },
  { name: 'eda.audit_log.events', type: 'EDA', project: 'T커머스 주문 파이프라인', status: 'lag', partitions: 4, produceRate: 980, consumeRate: 610, replicaPct: 100 },
  { name: 'eda.transactions.events', type: 'EDA', project: '이상거래 탐지 시스템', status: 'lag', partitions: 8, produceRate: 3120, consumeRate: 2740, replicaPct: 88 },
  { name: 'cdc.events.cdc', type: 'CDC', project: '반도체 생산 분석 허브', status: 'active', partitions: 6, produceRate: 980, consumeRate: 980, replicaPct: 100 },
  { name: '_connect-offsets', type: 'EDA', project: 'Platform', status: 'active', partitions: 25, produceRate: 12, consumeRate: 12, replicaPct: 100 },
]

export const CONSUMER_GROUPS: ConsumerGroupRow[] = [
  {
    name: 'cg-notification',
    state: 'STABLE',
    members: 3,
    totalLag: 120,
    lastCommit: '2s ago',
    partitionStrategy: 'cooperative-sticky',
    instances: [
      { id: 'notification-0', host: '10.21.4.10', partitions: 2, lag: 40 },
      { id: 'notification-1', host: '10.21.4.11', partitions: 2, lag: 38 },
      { id: 'notification-2', host: '10.21.4.12', partitions: 2, lag: 42 },
    ],
  },
  {
    name: 'cg-search',
    state: 'STABLE',
    members: 2,
    totalLag: 640,
    lastCommit: '4s ago',
    partitionStrategy: 'range',
    instances: [
      { id: 'search-0', host: '10.21.5.10', partitions: 3, lag: 320 },
      { id: 'search-1', host: '10.21.5.11', partitions: 3, lag: 320 },
    ],
  },
  {
    name: 'cg-audit',
    state: 'REBALANCING',
    members: 2,
    totalLag: 7400,
    lastCommit: '38s ago',
    partitionStrategy: 'range',
    instances: [
      { id: 'audit-0', host: '10.21.6.10', partitions: 2, lag: 3900 },
      { id: 'audit-1', host: '10.21.6.11', partitions: 2, lag: 3500 },
    ],
  },
  {
    name: 'cg-fraud-detector',
    state: 'DEAD',
    members: 0,
    totalLag: 18400,
    lastCommit: '6m ago',
    partitionStrategy: 'cooperative-sticky',
    instances: [],
  },
  {
    name: 'cg-risk-scorer',
    state: 'STABLE',
    members: 4,
    totalLag: 310,
    lastCommit: '1s ago',
    partitionStrategy: 'cooperative-sticky',
    instances: [
      { id: 'risk-0', host: '10.22.1.10', partitions: 2, lag: 80 },
      { id: 'risk-1', host: '10.22.1.11', partitions: 2, lag: 75 },
      { id: 'risk-2', host: '10.22.1.12', partitions: 2, lag: 78 },
      { id: 'risk-3', host: '10.22.1.13', partitions: 2, lag: 77 },
    ],
  },
]

export const CLUSTER_CONNECTORS: ClusterConnector[] = [
  { name: 'orders-source', kind: 'Source', status: 'RUNNING', project: 'T커머스 주문 파이프라인', pipeline: 'orders-event-stream', tasks: 2, recordsPerSec: 1840 },
  { name: 'users-source', kind: 'Source', status: 'RUNNING', project: 'T커머스 주문 파이프라인', pipeline: 'users-cdc-sync', tasks: 1, recordsPerSec: 420 },
  { name: 'users-sink', kind: 'Sink', status: 'RUNNING', project: 'T커머스 주문 파이프라인', pipeline: 'users-cdc-sync', tasks: 1, recordsPerSec: 418 },
  { name: 'audit-source', kind: 'Source', status: 'PARTIALLY_FAILED', project: 'T커머스 주문 파이프라인', pipeline: 'audit-stream', tasks: 2, recordsPerSec: 610 },
  { name: 'txn-source', kind: 'Source', status: 'RUNNING', project: '이상거래 탐지 시스템', pipeline: 'txn-fraud-events', tasks: 3, recordsPerSec: 3120 },
  { name: 'events-source', kind: 'Source', status: 'RUNNING', project: '반도체 생산 분석 허브', pipeline: 'events-warehouse-cdc', tasks: 2, recordsPerSec: 980 },
]
