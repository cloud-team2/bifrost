import type {
  ActivityEvent,
  Broker,
  ClusterConnector,
  ClusterTopic,
  ConsumerGroupRow,
  Edge,
  IncidentReport,
  KafkaSecret,
  KafkaUser,
  Member,
  Node,
  Project,
  ResourceEvent,
  User,
} from './types'

export const CURRENT_USER: User = {
  name: '김민준',
  email: 'ta@bifrost.io',
  role: 'admin',
  initial: '김',
}

export const DEMO_ACCOUNTS: { user: User; password: string }[] = [
  { user: CURRENT_USER, password: 'ta1234' },
]

export const APP_VERSION = 'v0.8.2'

/* ---------------------------------------------------------------- projects */

export const PROJECTS: Project[] = [
  {
    id: 'ws-commerce',
    name: 'T커머스 주문 파이프라인',
    slug: 't-commerce-order',
    ownerId: CURRENT_USER.email,
    pipelineIds: ['pl-1', 'pl-2', 'pl-3'],
    dbIds: ['db-orders', 'db-users', 'db-analytics'],
    memberCount: 6,
    createdAt: '2026-02-11',
  },
  {
    id: 'ws-fraud',
    name: '이상거래 탐지 시스템',
    slug: 'fraud-detection',
    ownerId: CURRENT_USER.email,
    pipelineIds: ['pl-4'],
    dbIds: ['db-txn', 'db-fraud-store'],
    memberCount: 4,
    createdAt: '2026-03-04',
  },
  {
    id: 'ws-analytics',
    name: '반도체 생산 분석 허브',
    slug: 'semiconductor-analytics',
    ownerId: CURRENT_USER.email,
    pipelineIds: ['pl-5'],
    dbIds: ['db-events', 'db-warehouse'],
    memberCount: 3,
    createdAt: '2026-01-22',
  },
  {
    id: 'ws-billing',
    name: '에너지 정산 자동화',
    slug: 'energy-settlement',
    ownerId: CURRENT_USER.email,
    pipelineIds: [],
    dbIds: [],
    memberCount: 2,
    createdAt: '2026-04-10',
  },
]

/* ------------------------------------------------------------------ nodes */

export const NODES: Node[] = [
  /* commerce */
  {
    id: 'db-orders',
    type: 'database',
    label: 'orders-db',
    alias: '주문 서비스 DB',
    tech: 'postgres',
    techLabel: 'postgres 15.3',
    host: '10.20.0.11:5432',
    status: 'healthy',
    x: 120,
    y: 110,
    schema: { tables: 24, rows: '8.4M', size: '12.1 GB' },
    cdc: { wal_level: 'logical', replication: 'granted', slots: '2 / 10', wal_senders: '3 / 10' },
    metrics: { tps: 1840, lag_ms: 42 },
  },
  {
    id: 'db-users',
    type: 'database',
    label: 'users-db',
    alias: '고객 Identity DB',
    tech: 'postgres',
    techLabel: 'postgres 15.1',
    host: '10.20.0.12:5432',
    status: 'healthy',
    x: 120,
    y: 320,
    schema: { tables: 11, rows: '2.1M', size: '3.6 GB' },
    cdc: { wal_level: 'logical', replication: 'granted', slots: '1 / 10', wal_senders: '1 / 10' },
    metrics: { tps: 420, lag_ms: 31 },
  },
  {
    id: 'db-analytics',
    type: 'database',
    label: 'analytics-dw',
    alias: '분석 웨어하우스',
    tech: 'postgres',
    techLabel: 'postgres 16.0',
    host: '10.20.0.40:5432',
    status: 'warning',
    x: 760,
    y: 320,
    schema: { tables: 58, rows: '210M', size: '184 GB' },
    cdc: { wal_level: 'replica', replication: 'granted', slots: '0 / 10', wal_senders: '0 / 10' },
    metrics: { tps: 90, lag_ms: 220 },
  },
  {
    id: 'svc-notification',
    type: 'service',
    label: 'notification-svc',
    techLabel: 'Node.js consumer',
    host: 'k8s/commerce/notification',
    status: 'healthy',
    x: 760,
    y: 70,
    lang: 'Node.js',
    consumerGroup: 'cg-notification',
    subscribedTopic: 'eda.orders.events',
    lag: 120,
    groupState: 'STABLE',
  },
  {
    id: 'svc-search',
    type: 'service',
    label: 'search-indexer',
    techLabel: 'Java consumer',
    host: 'k8s/commerce/search',
    status: 'healthy',
    x: 760,
    y: 190,
    lang: 'Java',
    consumerGroup: 'cg-search',
    subscribedTopic: 'eda.orders.events',
    lag: 640,
    groupState: 'STABLE',
  },
  {
    id: 'svc-audit',
    type: 'service',
    label: 'audit-logger',
    techLabel: 'Python consumer',
    host: 'k8s/commerce/audit',
    status: 'warning',
    x: 760,
    y: 440,
    lang: 'Python',
    consumerGroup: 'cg-audit',
    subscribedTopic: 'eda.audit_log.events',
    lag: 7400,
    groupState: 'REBALANCING',
  },
  /* fraud */
  {
    id: 'db-txn',
    type: 'database',
    label: 'txn-db',
    alias: '거래 트랜잭션 DB',
    tech: 'mariadb',
    techLabel: 'mariadb 11.2',
    host: '10.30.0.11:3306',
    status: 'healthy',
    x: 120,
    y: 220,
    schema: { tables: 16, rows: '54M', size: '46 GB' },
    cdc: { wal_level: 'binlog/ROW', replication: 'granted', slots: '—', wal_senders: '2' },
    metrics: { tps: 3120, lag_ms: 58 },
  },
  {
    id: 'db-fraud-store',
    type: 'database',
    label: 'fraud-store',
    alias: '이상탐지 Feature Store',
    tech: 'postgres',
    techLabel: 'postgres 15.4',
    host: '10.30.0.22:5432',
    status: 'healthy',
    x: 760,
    y: 400,
    schema: { tables: 9, rows: '1.2M', size: '2.8 GB' },
    cdc: { wal_level: 'replica', replication: 'granted', slots: '0 / 10', wal_senders: '0 / 10' },
    metrics: { tps: 60, lag_ms: 40 },
  },
  {
    id: 'svc-fraud-detector',
    type: 'service',
    label: 'fraud-detector',
    techLabel: 'Python consumer',
    host: 'k8s/fraud/detector',
    status: 'warning',
    x: 760,
    y: 140,
    lang: 'Python',
    consumerGroup: 'cg-fraud-detector',
    subscribedTopic: 'eda.transactions.events',
    lag: 18400,
    groupState: 'REBALANCING',
  },
  {
    id: 'svc-risk-scorer',
    type: 'service',
    label: 'risk-scorer',
    techLabel: 'Java consumer',
    host: 'k8s/fraud/risk',
    status: 'healthy',
    x: 760,
    y: 260,
    lang: 'Java',
    consumerGroup: 'cg-risk-scorer',
    subscribedTopic: 'eda.transactions.events',
    lag: 310,
    groupState: 'STABLE',
  },
  /* analytics */
  {
    id: 'db-events',
    type: 'database',
    label: 'events-db',
    alias: '이벤트 스토어',
    tech: 'postgres',
    techLabel: 'postgres 15.3',
    host: '10.40.0.11:5432',
    status: 'healthy',
    x: 120,
    y: 220,
    schema: { tables: 7, rows: '410M', size: '320 GB' },
    cdc: { wal_level: 'logical', replication: 'granted', slots: '1 / 10', wal_senders: '1 / 10' },
    metrics: { tps: 980, lag_ms: 64 },
  },
  {
    id: 'db-warehouse',
    type: 'database',
    label: 'warehouse-db',
    alias: 'BI 웨어하우스',
    tech: 'postgres',
    techLabel: 'postgres 16.0',
    host: '10.40.0.40:5432',
    status: 'healthy',
    x: 760,
    y: 220,
    schema: { tables: 120, rows: '1.4B', size: '2.1 TB' },
    cdc: { wal_level: 'replica', replication: 'granted', slots: '0 / 10', wal_senders: '0 / 10' },
    metrics: { tps: 40, lag_ms: 90 },
  },
]

/* ------------------------------------------------------------------ edges */

export const EDGES: Edge[] = [
  {
    id: 'pl-1',
    name: 'orders-event-stream',
    alias: '주문 이벤트 스트림',
    pattern: 'fan-out',
    source: 'db-orders',
    sink: null,
    consumers: ['svc-notification', 'svc-search'],
    table: { schema: 'public', name: 'orders' },
    topic: 'eda.orders.events',
    status: 'active',
    partitions: 6,
    metrics: { produce_rate: 1840, consume_rate: 1835, lag: 760, error_pct: 0.1 },
  },
  {
    id: 'pl-2',
    name: 'users-cdc-sync',
    alias: '고객정보 동기화',
    pattern: 'direct',
    source: 'db-users',
    sink: 'db-analytics',
    table: { schema: 'public', name: 'users' },
    topic: 'cdc.users.cdc',
    status: 'active',
    partitions: 3,
    metrics: { produce_rate: 420, consume_rate: 418, lag: 90, error_pct: 0 },
    syncStatus: { sourceRows: 2100000, sinkRows: 2099800, delta: 200, lastSynced: '09:31:04' },
  },
  {
    id: 'pl-3',
    name: 'audit-stream',
    alias: '감사 로그 스트림',
    pattern: 'fan-out',
    source: 'db-orders',
    sink: null,
    consumers: ['svc-audit'],
    table: { schema: 'public', name: 'audit_log' },
    topic: 'eda.audit_log.events',
    status: 'lag',
    partitions: 4,
    metrics: { produce_rate: 980, consume_rate: 610, lag: 7400, error_pct: 0.3 },
  },
  {
    id: 'pl-4',
    name: 'txn-fraud-events',
    alias: '거래 이상탐지 이벤트',
    pattern: 'fan-out',
    source: 'db-txn',
    sink: null,
    consumers: ['svc-fraud-detector', 'svc-risk-scorer'],
    table: { schema: 'commerce', name: 'transactions' },
    topic: 'eda.transactions.events',
    status: 'lag',
    partitions: 8,
    metrics: { produce_rate: 3120, consume_rate: 2740, lag: 18400, error_pct: 0.6 },
  },
  {
    id: 'pl-5',
    name: 'events-warehouse-cdc',
    alias: '이벤트 BI 적재',
    pattern: 'direct',
    source: 'db-events',
    sink: 'db-warehouse',
    table: { schema: 'public', name: 'events' },
    topic: 'cdc.events.cdc',
    status: 'active',
    partitions: 6,
    metrics: { produce_rate: 980, consume_rate: 980, lag: 120, error_pct: 0 },
    syncStatus: { sourceRows: 410000000, sinkRows: 409998750, delta: 1250, lastSynced: '09:31:01' },
  },
]

/* -------------------------------------------------------------- incidents */

export const INCIDENTS: IncidentReport[] = [
  {
    id: 'inc-1',
    title: 'fraud-detector Consumer Lag 급증',
    // e2(WARNING lag) → 트리거, r1(ERROR DEAD) → CRITICAL 상향
    severity: 'critical',
    status: 'open',
    createdAt: '09:14',
    updatedAt: '09:31',
    triggerEventId: 'e2',
    relatedEventIds: ['r1'],
    summary:
      'cg-fraud-detector Consumer Group의 lag이 18,420건을 초과하였고, 이후 모든 멤버 연결이 끊겨 DEAD 상태로 전환되었습니다. eda.transactions.events Pipeline 처리량이 약 12% 감소 중입니다.',
    rootCause:
      'fraud-detector Pod 재시작 중 liveness probe 타임아웃이 너무 짧게 설정되어 약 40초마다 리밸런싱이 재발하고 있습니다. 반복 리밸런싱으로 인해 결국 모든 멤버가 그룹에서 제거되었습니다.',
    affectedPipelines: ['pl-4'],
    affectedTeams: ['이상거래 탐지팀'],
    aiActions: [
      {
        id: 'a1',
        label: 'fraud-detector 5 인스턴스로 스케일 아웃',
        risk: 'low',
        estimatedTime: '~2 min',
        detail: 'Consumer 병렬성을 높여 Partition 부하를 리밸런싱 임계값 이하로 낮춥니다.',
      },
      {
        id: 'a2',
        label: 'Liveness Probe 타임아웃을 30초로 상향',
        risk: 'low',
        estimatedTime: '~1 min',
        detail: '배치 처리 중 Pod가 강제 종료되는 것을 방지하여 리밸런싱 루프를 종료합니다.',
      },
      {
        id: 'a3',
        label: 'fraud.txn.events Partition 리밸런싱',
        risk: 'medium',
        estimatedTime: '~4 min',
        detail: 'Partition 리더십을 재분배하여 복구 중 특정 Broker에 Hot Partition이 집중되지 않도록 합니다.',
      },
    ],
    actionLog: [
      { time: '09:14', actor: 'Bifrost AI', action: '인시던트 생성 — cg-fraud-detector lag 임계값(5,000건) 초과' },
      { time: '09:31', actor: 'Bifrost AI', action: 'CRITICAL 상향 — cg-fraud-detector DEAD 전환 감지' },
    ],
  },
  {
    id: 'inc-2',
    title: 'audit-source Connector 반복 재시작 및 Task FAILED',
    // e6(WARNING lag) → 트리거, 이후 WARNING 누적 → e1(ERROR) → CRITICAL 상향
    severity: 'critical',
    status: 'investigating',
    createdAt: '07:48',
    updatedAt: '09:31',
    triggerEventId: 'e6',
    relatedEventIds: ['e4', 'r3', 'e3', 'r2', 'e1'],
    summary:
      'cg-audit이 REBALANCING 루프를 반복하다 audit-source Connector Task 1이 최종 FAILED 상태로 전환되었습니다. audit-event-stream Pipeline이 error 상태로 전환되어 이벤트 수집이 중단되었습니다.',
    rootCause:
      'session.timeout.ms가 평균 배치 처리 시간보다 낮게 설정되어 느린 배치를 멤버 장애로 오인합니다. 반복 리밸런싱으로 Connector Task 처리가 지연되어 최종 FAILED로 전환되었습니다.',
    affectedPipelines: ['pl-3'],
    affectedTeams: ['T커머스팀'],
    aiActions: [
      {
        id: 'b1',
        label: 'session.timeout.ms를 45초로 상향',
        risk: 'low',
        estimatedTime: '~1 min',
        detail: '느린 배치 처리에 충분한 여유 시간을 부여하여 멤버가 잘못 제거되는 것을 방지합니다.',
      },
      {
        id: 'b2',
        label: 'audit-source Connector 재시작',
        risk: 'medium',
        estimatedTime: '~3 min',
        detail: 'session.timeout.ms 수정 후 FAILED Task를 재시작하여 이벤트 수집을 재개합니다.',
      },
    ],
    actionLog: [
      { time: '07:48', actor: 'Bifrost AI', action: '인시던트 생성 — cg-audit lag 5,820건 초과' },
      { time: '08:47', actor: 'Bifrost AI', action: 'WARNING 누적 — cg-audit REBALANCING 7분 지속' },
      { time: '08:55', actor: '이준호', action: '조사 시작 — audit-source Connector 재시작 이력 확인 중' },
      { time: '09:31', actor: 'Bifrost AI', action: 'CRITICAL 상향 — audit-source Task 1 FAILED' },
    ],
  },
  {
    id: 'inc-3',
    title: 'broker-2 디스크 사용량 80% 초과',
    severity: 'warning',
    status: 'resolved',
    createdAt: '05/21 22:10',
    updatedAt: '05/21 22:41',
    triggerEventId: 'r0',
    relatedEventIds: [],
    summary: 'broker-2 로그 볼륨이 80%를 초과했습니다. 보존 정책 압축이 실행되어 사용률이 61%로 회복되었습니다.',
    rootCause: '트래픽이 많은 3개 Topic에 보존 정책 변경이 적용되지 않았습니다.',
    affectedPipelines: [],
    affectedTeams: ['플랫폼팀'],
    aiActions: [],
    actionLog: [
      { time: '22:10', actor: 'Bifrost AI', action: '인시던트 생성 — broker-2 디스크 80% 초과' },
      { time: '22:33', actor: '한소라', action: '3개 Topic에 보존 정책 적용' },
      { time: '22:41', actor: '한소라', action: '인시던트 해결 — 디스크 사용률 61% 복구 확인' },
    ],
  },
]

/* ----------------------------------------------------------------- events */

/*
  이벤트 로그 레벨 기준 (기능 명세서 B.5 기준):
  ERROR : 서비스 중단 수준 — Connector FAILED, DB 연결 실패, lag ≥ 50,000, Replication lag ≥ 5,000ms
  WARNING: 임계값 초과, 일시 오류 — lag ≥ 5,000 (< 50,000), Connector 재시작 1회, REBALANCING 5분+, Replication lag ≥ 1,000ms, Retry 발생
  INFO   : 정상 운영 중 상태 변화 — Pipeline 생성/삭제/Pause/Resume, 스냅샷 완료, 스케일 이벤트 관측

  제외 대상: Kafka 브로커 CPU/디스크/네트워크 (설계 원칙 §2 — 인프라 레이어 비노출)
*/
export const ACTIVITY_EVENTS: ActivityEvent[] = [
  // ERROR → inc-2 상향 트리거
  { id: 'e1', time: '09:31', level: 'error',   message: 'audit-source Connector Task 1 FAILED — 파이프라인 error 상태로 전환', pipelineId: 'pl-3', incidentId: 'inc-2' },
  // WARNING → inc-1 트리거
  { id: 'e2', time: '09:14', level: 'warning', message: 'cg-fraud-detector lag 18,420건 초과 (임계값 5,000건)', pipelineId: 'pl-4', incidentId: 'inc-1' },
  // WARNING → inc-2 관련
  { id: 'e3', time: '08:55', level: 'warning', message: 'audit-source Connector Task 재시작 1회 — Connection timeout', pipelineId: 'pl-3', incidentId: 'inc-2' },
  // WARNING → inc-2 관련
  { id: 'e4', time: '08:47', level: 'warning', message: 'cg-audit REBALANCING 7분 지속 (임계값 5분 초과)', pipelineId: 'pl-3', incidentId: 'inc-2' },
  // WARNING — 단독 (1건, 인시던트 미생성)
  { id: 'e5', time: '08:30', level: 'warning', message: 'orders-prod DB Replication lag 1,240ms (임계값 1,000ms 초과)', pipelineId: 'pl-1' },
  // WARNING → inc-2 트리거
  { id: 'e6', time: '07:48', level: 'warning', message: 'cg-audit lag 5,820건 초과 (임계값 5,000건)', pipelineId: 'pl-3', incidentId: 'inc-2' },
  { id: 'e7', time: '07:30', level: 'info',    message: 'Pipeline audit-event-stream 일시정지 (TA 액션)', pipelineId: 'pl-3' },
  { id: 'e8', time: '07:12', level: 'info',    message: 'users-cdc-sync 초기 스냅샷 완료 → 스트리밍 모드 전환', pipelineId: 'pl-2' },
  { id: 'e9', time: '07:05', level: 'info',    message: 'orders-prod DB Replication lag 정상화 (현재 38ms)' },
  { id: 'e10', time: '06:40', level: 'info',   message: 'Pipeline orders-event-stream 생성 완료, 상태 active', pipelineId: 'pl-1' },
]

export const RESOURCE_EVENTS: ResourceEvent[] = [
  // ERROR → inc-1 상향 트리거
  { id: 'r1', time: '09:31', level: 'error',   resourceType: 'Consumer Group', resourceName: 'cg-fraud-detector', message: 'STABLE → DEAD 전환 — 모든 멤버 연결 끊김 (8분간 응답 없음)', incidentId: 'inc-1' },
  // WARNING → inc-2 관련
  { id: 'r2', time: '08:55', level: 'warning', resourceType: 'Connector',       resourceName: 'audit-source',       message: 'Task 재시작 1시간 내 2회 (임계값 3회)', incidentId: 'inc-2' },
  // WARNING → inc-2 관련
  { id: 'r3', time: '08:47', level: 'warning', resourceType: 'Consumer Group', resourceName: 'cg-audit',            message: 'REBALANCING 7분 지속 — 멤버 타임아웃', incidentId: 'inc-2' },
  // INFO — 단독
  { id: 'r4', time: '08:40', level: 'info',    resourceType: 'Topic',           resourceName: 'eda.orders.events',  message: 'Partition 수 4 → 6으로 변경' },
  // WARNING — 단독 (1건, 자동 복구)
  { id: 'r5', time: '08:11', level: 'warning', resourceType: 'Connector',       resourceName: 'users-source',       message: 'Task 1 재시작 1회 — 일시적 오류 후 자동 복구' },
  { id: 'r6', time: '07:12', level: 'info',    resourceType: 'Connector',       resourceName: 'users-source',       message: '초기 스냅샷 완료, 스트리밍 모드 전환' },
  { id: 'r7', time: '06:40', level: 'info',    resourceType: 'Connector',       resourceName: 'orders-source',      message: 'Connector 배포 완료, Task RUNNING 전환' },
  // WARNING → inc-3 트리거 (전일 22:10)
  { id: 'r0', time: '05/21 22:10', level: 'warning', resourceType: 'Broker', resourceName: 'broker-2', message: '디스크 사용률 80.2% 초과 (임계값 80%)', incidentId: 'inc-3' },
]

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

/* --------------------------------------------------------------- settings */

export const MEMBERS: Member[] = [
  { name: '김민준', email: 'ta@bifrost.io', role: 'admin', joinedAt: '2026-02-11' },
  { name: '이민지', email: 'minji@bifrost.io', role: 'developer', joinedAt: '2026-02-14' },
  { name: '박정우', email: 'david@bifrost.io', role: 'developer', joinedAt: '2026-03-20' },
  { name: '조유나', email: 'yuna@bifrost.io', role: 'operator', joinedAt: '2026-04-02' },
]

export const KAFKA_USERS: KafkaUser[] = [
  { id: 'ku1', principal: 'User:orders-source', auth: 'SCRAM-SHA-512', secret: 'orders-source-scram', acl: { read: true, write: true, admin: false }, status: 'active', lastActive: '2s ago' },
  { id: 'ku2', principal: 'User:notification-svc', auth: 'SCRAM-SHA-512', secret: 'notification-scram', acl: { read: true, write: false, admin: false }, status: 'active', lastActive: '5s ago' },
  { id: 'ku3', principal: 'User:fraud-detector', auth: 'mTLS', secret: 'fraud-detector-mtls', acl: { read: true, write: false, admin: false }, status: 'inactive', lastActive: '6m ago' },
  { id: 'ku4', principal: 'User:admin-cli', auth: 'mTLS', secret: 'admin-mtls', acl: { read: true, write: true, admin: true }, status: 'active', lastActive: '1h ago' },
]

export const KAFKA_SECRETS: KafkaSecret[] = [
  { id: 'ks1', name: 'orders-source-scram', type: 'SCRAM', cluster: 'primary', connections: 2, lastRotated: '2026-04-30', status: 'active' },
  { id: 'ks2', name: 'notification-scram', type: 'SCRAM', cluster: 'primary', connections: 3, lastRotated: '2026-05-02', status: 'active' },
  { id: 'ks3', name: 'fraud-detector-mtls', type: 'mTLS', cluster: 'primary', connections: 0, lastRotated: '2026-03-18', status: 'revoked' },
  { id: 'ks4', name: 'admin-mtls', type: 'mTLS', cluster: 'primary', connections: 1, lastRotated: '2026-05-10', status: 'active' },
]

export const BOOTSTRAP_SERVER = 'pkc-bifrost.ap-northeast-2.aws.confluent.cloud:9092'
export const LAG_THRESHOLD_WARNING = 5000
export const LAG_THRESHOLD_CRITICAL = 20000
export const LAG_THRESHOLD = LAG_THRESHOLD_WARNING
