import { Fragment, useEffect, useMemo, useRef, useState } from 'react'
import { Bar, BarChart, CartesianGrid, Cell, Tooltip, XAxis, YAxis } from 'recharts'
import { Icon } from '../../components/Icon'
import { MetricCard, Panel, StatusBadge } from '../../components/blocks'
import { TrendChart, CHART_COLORS, ResponsiveChart } from '../../components/Charts'
import { TechIcon, nodeKind } from '../../components/TechIcon'
import { useToast } from '../../components/Toast'
import { useApp, CLUSTER } from '../../store/AppStore'
import { genSeries, pipelineConsumers, pipelineLabel } from '../../data/helpers'
import { BOOTSTRAP_SERVER, CONSUMER_GROUPS, LAG_THRESHOLD } from '../../data/mock'
import type { Edge, Node } from '../../data/types'
import { api, type ConnectorInfo, type SchemaColumn, type SyncStatusResponse } from '../../lib/api'
import { cn, formatNum } from '../../lib/format'

/* ---------------------------------------------------------------- topic partition data */

const TOPIC_PARTITIONS: Record<string, Array<{
  id: number; leader: string; beginOffset: number; endOffset: number
}>> = {
  'eda.orders.events': [
    { id: 0, leader: 'broker-1', beginOffset: 14000,  endOffset: 184200 },
    { id: 1, leader: 'broker-2', beginOffset: 16000,  endOffset: 184300 },
    { id: 2, leader: 'broker-3', beginOffset: 15200,  endOffset: 184400 },
    { id: 3, leader: 'broker-1', beginOffset: 14800,  endOffset: 184310 },
    { id: 4, leader: 'broker-2', beginOffset: 15600,  endOffset: 184220 },
    { id: 5, leader: 'broker-3', beginOffset: 14400,  endOffset: 184350 },
  ],
  'cdc.users.cdc': [
    { id: 0, leader: 'broker-1', beginOffset: 8000,   endOffset: 42100 },
    { id: 1, leader: 'broker-2', beginOffset: 9000,   endOffset: 42150 },
    { id: 2, leader: 'broker-3', beginOffset: 8500,   endOffset: 42200 },
  ],
  'eda.audit_log.events': [
    { id: 0, leader: 'broker-1', beginOffset: 3000,   endOffset: 91400 },
    { id: 1, leader: 'broker-2', beginOffset: 3800,   endOffset: 91600 },
    { id: 2, leader: 'broker-3', beginOffset: 3400,   endOffset: 91800 },
    { id: 3, leader: 'broker-1', beginOffset: 3200,   endOffset: 92000 },
  ],
  'eda.transactions.events': [
    { id: 0, leader: 'broker-1', beginOffset: 20000,  endOffset: 310000 },
    { id: 1, leader: 'broker-2', beginOffset: 23000,  endOffset: 310400 },
    { id: 2, leader: 'broker-3', beginOffset: 21000,  endOffset: 310800 },
    { id: 3, leader: 'broker-1', beginOffset: 26000,  endOffset: 310200 },
    { id: 4, leader: 'broker-2', beginOffset: 22000,  endOffset: 311600 },
    { id: 5, leader: 'broker-3', beginOffset: 25000,  endOffset: 311200 },
    { id: 6, leader: 'broker-1', beginOffset: 24000,  endOffset: 311400 },
    { id: 7, leader: 'broker-2', beginOffset: 23000,  endOffset: 311300 },
  ],
  'cdc.events.cdc': [
    { id: 0, leader: 'broker-1', beginOffset: 50000,  endOffset: 980000 },
    { id: 1, leader: 'broker-2', beginOffset: 55000,  endOffset: 980300 },
    { id: 2, leader: 'broker-3', beginOffset: 52000,  endOffset: 980600 },
    { id: 3, leader: 'broker-1', beginOffset: 58000,  endOffset: 980900 },
    { id: 4, leader: 'broker-2', beginOffset: 54000,  endOffset: 981200 },
    { id: 5, leader: 'broker-3', beginOffset: 56000,  endOffset: 981500 },
  ],
}

const TOPIC_RETENTION: Record<string, string> = {
  'eda.orders.events':       '7일',
  'cdc.users.cdc':           '3일',
  'eda.audit_log.events':    '30일',
  'eda.transactions.events': '7일',
  'cdc.events.cdc':          '14일',
}

/* ---------------------------------------------------------------- consumer group offset data */

const PARTITION_OFFSETS: Record<string, Array<{
  partition: number; member: string | null; committed: number; endOffset: number
}>> = {
  'cg-notification': [
    { partition: 0, member: 'notification-0', committed: 184100, endOffset: 184200 },
    { partition: 1, member: 'notification-1', committed: 184280, endOffset: 184300 },
    { partition: 2, member: 'notification-2', committed: 184050, endOffset: 184400 },
    { partition: 3, member: 'notification-0', committed: 184090, endOffset: 184310 },
    { partition: 4, member: 'notification-1', committed: 184100, endOffset: 184220 },
    { partition: 5, member: 'notification-2', committed: 184280, endOffset: 184350 },
  ],
  'cg-search': [
    { partition: 2, member: 'search-0', committed: 184050, endOffset: 184400 },
    { partition: 3, member: 'search-0', committed: 184090, endOffset: 184310 },
    { partition: 4, member: 'search-1', committed: 184100, endOffset: 184200 },
    { partition: 5, member: 'search-1', committed: 184280, endOffset: 184300 },
  ],
  'cg-audit': [
    { partition: 0, member: 'audit-0', committed: 84000,  endOffset: 91400 },
    { partition: 1, member: 'audit-0', committed: 85200,  endOffset: 91600 },
    { partition: 2, member: 'audit-1', committed: 85800,  endOffset: 91800 },
    { partition: 3, member: 'audit-1', committed: 84500,  endOffset: 92000 },
  ],
  'cg-fraud-detector': [
    { partition: 0, member: null, committed: 291600, endOffset: 310000 },
    { partition: 1, member: null, committed: 290200, endOffset: 310400 },
    { partition: 2, member: null, committed: 291800, endOffset: 310800 },
    { partition: 3, member: null, committed: 290100, endOffset: 310200 },
  ],
  'cg-risk-scorer': [
    { partition: 4, member: 'risk-0', committed: 309800, endOffset: 311600 },
    { partition: 5, member: 'risk-1', committed: 309900, endOffset: 311200 },
    { partition: 6, member: 'risk-2', committed: 309850, endOffset: 311400 },
    { partition: 7, member: 'risk-3', committed: 309750, endOffset: 311300 },
  ],
}

/* ---------------------------------------------------------------- CDC sync / replication data */

const TABLE_SYNC_STATUS: Record<string, Array<{
  table: string; sourceRows: number; sinkRows: number; lastSynced: string; status: 'synced' | 'lag' | 'error'
}>> = {
  'cdc.users.cdc': [
    { table: 'public.users',         sourceRows: 2100000,  sinkRows: 2099850,  lastSynced: '09:31:22', status: 'synced' },
    { table: 'public.user_profiles', sourceRows: 1850000,  sinkRows: 1847200,  lastSynced: '09:29:18', status: 'lag'    },
    { table: 'public.sessions',      sourceRows: 8200000,  sinkRows: 8200000,  lastSynced: '09:31:25', status: 'synced' },
    { table: 'public.oauth_tokens',  sourceRows: 430000,   sinkRows: 430000,   lastSynced: '09:31:10', status: 'synced' },
  ],
  'cdc.events.cdc': [
    { table: 'public.events',        sourceRows: 12400000, sinkRows: 12396000, lastSynced: '09:30:55', status: 'lag'    },
    { table: 'public.event_types',   sourceRows: 48,       sinkRows: 48,       lastSynced: '09:31:00', status: 'synced' },
    { table: 'public.event_tags',    sourceRows: 312000,   sinkRows: 312000,   lastSynced: '09:31:10', status: 'synced' },
    { table: 'public.aggregates',    sourceRows: 780000,   sinkRows: 778400,   lastSynced: '09:30:40', status: 'lag'    },
  ],
}

const REPLICATION_SLOTS: Record<string, {
  slot: string; plugin: string; lsn: string; lagBytes: number; retainedWal: string
}> = {
  'cdc.users.cdc':  { slot: 'bifrost_users_slot',  plugin: 'pgoutput', lsn: '0/1A4F820', lagBytes: 14200,  retainedWal: '14 KB'  },
  'cdc.events.cdc': { slot: 'bifrost_events_slot', plugin: 'pgoutput', lsn: '0/3C8A140', lagBytes: 248000, retainedWal: '242 KB' },
}

const CDC_ERROR_EVENTS: Record<string, Array<{
  ts: string; table: string; reason: string; skipped: boolean
}>> = {
  'cdc.users.cdc': [
    { ts: '09:28:14', table: 'public.users',         reason: 'TOASTed column skipped (bio)',           skipped: true  },
    { ts: '09:15:02', table: 'public.user_profiles', reason: '스키마 변경: avatar_url 컬럼 추가',       skipped: false },
  ],
  'cdc.events.cdc': [
    { ts: '09:22:31', table: 'public.events',        reason: '메시지 크기 1MB 초과 — 스킵 처리됨',      skipped: true  },
    { ts: '08:47:10', table: 'public.aggregates',    reason: 'NULL constraint 위반 (category_id)',     skipped: true  },
  ],
}

/* ---------------------------------------------------------------- sample messages */

/* ---- Kafka message browser data ---- */

interface KafkaMsg {
  partition: number
  offset: number
  tsMs: number
  tsLabel: string
  key: string | null
  valueSize: number
  op: 'c' | 'u' | 'd' | 'r'
  headers: Record<string, string>
  before: Record<string, unknown> | null
  after:  Record<string, unknown> | null
}

const BASE_TS = 1748227800000 // 2026-05-26 09:30:00 (epoch ms)

const EDA_MESSAGES: KafkaMsg[] = [
  { partition:2, offset:184217, tsMs:BASE_TS+9334,  tsLabel:'09:30:09.334', key:'order:88422', valueSize:312, op:'c', headers:{'debezium.connector':'orders-source'}, before:null, after:{id:88422,customer_id:3021,status:'PLACED',amount:12500,created_at:'2026-05-26T09:30:09Z'} },
  { partition:1, offset:184216, tsMs:BASE_TS+8011,  tsLabel:'09:30:08.011', key:'order:80012', valueSize:298, op:'d', headers:{'debezium.connector':'orders-source'}, before:{id:80012,customer_id:1844,status:'CANCELLED',amount:88000}, after:null },
  { partition:5, offset:184215, tsMs:BASE_TS+6442,  tsLabel:'09:30:06.442', key:'order:88419', valueSize:344, op:'u', headers:{'debezium.connector':'orders-source'}, before:{id:88419,status:'PAID',amount:54900}, after:{id:88419,status:'SHIPPED',amount:54900,shipped_at:'2026-05-26T09:30:06Z'} },
  { partition:0, offset:184214, tsMs:BASE_TS+5104,  tsLabel:'09:30:05.104', key:'order:88420', valueSize:332, op:'u', headers:{'debezium.connector':'orders-source'}, before:{id:88420,status:'PLACED',amount:42990}, after:{id:88420,status:'PAID',amount:42990,paid_at:'2026-05-26T09:30:05Z'} },
  { partition:2, offset:184213, tsMs:BASE_TS+4823,  tsLabel:'09:30:04.823', key:'order:88421', valueSize:318, op:'c', headers:{'debezium.connector':'orders-source'}, before:null, after:{id:88421,customer_id:2990,status:'PLACED',amount:42990,created_at:'2026-05-26T09:30:04Z'} },
  { partition:3, offset:184212, tsMs:BASE_TS+3201,  tsLabel:'09:30:03.201', key:'order:88418', valueSize:301, op:'u', headers:{'debezium.connector':'orders-source'}, before:{id:88418,status:'PLACED'}, after:{id:88418,status:'PAID'} },
  { partition:4, offset:184211, tsMs:BASE_TS+2088,  tsLabel:'09:30:02.088', key:'order:88417', valueSize:288, op:'c', headers:{'debezium.connector':'orders-source'}, before:null, after:{id:88417,customer_id:1200,status:'PLACED',amount:9800} },
  { partition:1, offset:184210, tsMs:BASE_TS+1540,  tsLabel:'09:30:01.540', key:'order:88416', valueSize:310, op:'u', headers:{'debezium.connector':'orders-source'}, before:{id:88416,status:'SHIPPED'}, after:{id:88416,status:'DELIVERED'} },
  { partition:0, offset:184209, tsMs:BASE_TS+980,   tsLabel:'09:30:00.980', key:'order:88415', valueSize:322, op:'d', headers:{'debezium.connector':'orders-source'}, before:{id:88415,status:'CANCELLED',amount:15000}, after:null },
  { partition:5, offset:184208, tsMs:BASE_TS+210,   tsLabel:'09:30:00.210', key:'order:88414', valueSize:296, op:'c', headers:{'debezium.connector':'orders-source'}, before:null, after:{id:88414,customer_id:4410,status:'PLACED',amount:67500} },
]

let _edaCounter = 0
function nextEdaMsg(): KafkaMsg {
  _edaCounter++
  const now = Date.now()
  const pad = (n: number) => String(n).padStart(2, '0')
  const d = new Date(now)
  const tsLabel = `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${String(d.getMilliseconds()).padStart(3,'0')}`
  const ops: KafkaMsg['op'][] = ['c','u','u','d']
  const op = ops[_edaCounter % ops.length]
  const id = 88422 + _edaCounter
  return {
    partition: _edaCounter % 6,
    offset: 184217 + _edaCounter,
    tsMs: now,
    tsLabel,
    key: `order:${id}`,
    valueSize: 280 + Math.floor(Math.random() * 80),
    op,
    headers: { 'debezium.connector': 'orders-source' },
    before: op !== 'c' ? { id, status: 'PLACED' } : null,
    after:  op !== 'd' ? { id, status: op === 'c' ? 'PLACED' : 'PAID', amount: 10000 + _edaCounter * 500 } : null,
  }
}

const CDC_MESSAGES: KafkaMsg[] = [
  { partition:1, offset:42105, tsMs:BASE_TS+22000, tsLabel:'09:30:22.000', key:'users:5201', valueSize:412, op:'u', headers:{'debezium.connector':'users-source','debezium.db':'users_prod'}, before:{id:5201,email:'kim@example.com',status:'ACTIVE',updated_at:'2026-05-26T09:20:00Z'}, after:{id:5201,email:'kim@newdomain.com',status:'ACTIVE',updated_at:'2026-05-26T09:30:22Z'} },
  { partition:0, offset:42104, tsMs:BASE_TS+18000, tsLabel:'09:30:18.000', key:'users:5202', valueSize:388, op:'c', headers:{'debezium.connector':'users-source','debezium.db':'users_prod'}, before:null, after:{id:5202,email:'lee@example.com',status:'ACTIVE',created_at:'2026-05-26T09:30:18Z'} },
  { partition:2, offset:42103, tsMs:BASE_TS+15000, tsLabel:'09:30:15.000', key:'users:5200', valueSize:394, op:'u', headers:{'debezium.connector':'users-source','debezium.db':'users_prod'}, before:{id:5200,status:'ACTIVE'}, after:{id:5200,status:'DORMANT',updated_at:'2026-05-26T09:30:15Z'} },
  { partition:1, offset:42102, tsMs:BASE_TS+10000, tsLabel:'09:30:10.000', key:'users:5198', valueSize:366, op:'d', headers:{'debezium.connector':'users-source','debezium.db':'users_prod'}, before:{id:5198,email:'deleted@example.com',status:'INACTIVE'}, after:null },
  { partition:0, offset:42101, tsMs:BASE_TS-2000,  tsLabel:'09:29:58.000', key:'users:5197', valueSize:378, op:'u', headers:{'debezium.connector':'users-source','debezium.db':'users_prod'}, before:{id:5197,status:'ACTIVE'}, after:{id:5197,status:'SUSPENDED',updated_at:'2026-05-26T09:29:58Z'} },
  { partition:2, offset:42100, tsMs:BASE_TS-8000,  tsLabel:'09:29:52.000', key:'users:5196', valueSize:352, op:'c', headers:{'debezium.connector':'users-source','debezium.db':'users_prod'}, before:null, after:{id:5196,email:'park@example.com',status:'ACTIVE',created_at:'2026-05-26T09:29:52Z'} },
  { partition:0, offset:42099, tsMs:BASE_TS-15000, tsLabel:'09:29:45.000', key:'users:5195', valueSize:370, op:'u', headers:{'debezium.connector':'users-source','debezium.db':'users_prod'}, before:{id:5195,status:'SUSPENDED'}, after:{id:5195,status:'INACTIVE'} },
]

let _cdcCounter = 0
function nextCdcMsg(): KafkaMsg {
  _cdcCounter++
  const now = Date.now()
  const pad = (n: number) => String(n).padStart(2, '0')
  const d = new Date(now)
  const tsLabel = `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${String(d.getMilliseconds()).padStart(3,'0')}`
  const id = 5202 + _cdcCounter
  const op: KafkaMsg['op'] = _cdcCounter % 3 === 0 ? 'd' : _cdcCounter % 3 === 1 ? 'c' : 'u'
  return {
    partition: _cdcCounter % 3,
    offset: 42105 + _cdcCounter,
    tsMs: now,
    tsLabel,
    key: `users:${id}`,
    valueSize: 350 + Math.floor(Math.random() * 60),
    op,
    headers: { 'debezium.connector': 'users-source', 'debezium.db': 'users_prod' },
    before: op !== 'c' ? { id, status: 'ACTIVE' } : null,
    after:  op !== 'd' ? { id, email: `user${id}@example.com`, status: op === 'c' ? 'ACTIVE' : 'DORMANT' } : null,
  }
}

const tooltipStyle = {
  borderRadius: 8, border: '1px solid #e5e7eb', fontSize: 12,
  boxShadow: '0 4px 16px rgba(0,0,0,0.08)',
}

/* ---------------------------------------------------------------- main */

export function PipelineDetail() {
  const app = useApp()
  const toast = useToast()
  const edge = app.edges.find((e) => e.id === app.selectedPipelineId)
  const isEda = edge?.pattern === 'fan-out'

  // Connector 탭은 EDA/CDC 모두 표시(실제 커넥터는 ConnectorTab이 백엔드에서 조회, #107)
  const tabs = isEda
    ? ['Overview', 'Consumers', 'Connector', 'Messages', 'Connection Guide']
    : ['Overview', 'Connector', 'Sync', 'Messages', 'Table Mapping']

  const [tab, setTab] = useState(tabs[0])

  if (!edge) return <div className="px-6 py-10 text-sm text-gray-500">Pipeline not found.</div>
  const source    = app.nodes.find((n) => n.id === edge.source)!
  const sink      = edge.sink ? app.nodes.find((n) => n.id === edge.sink) ?? null : null
  const consumers = pipelineConsumers(edge, app.nodes)

  return (
    <div className="px-6 py-5">
      <button
        onClick={() => app.setView('pipelines')}
        className="mb-3 flex items-center gap-1.5 text-[12.5px] font-medium text-gray-500 hover:text-gray-800"
      >
        <Icon name="arrow-left" size={15} />
        Pipelines
      </button>

      {/* banner */}
      <div className="rounded-xl border border-gray-200 bg-white px-5 py-4">
        <div className="flex items-center gap-3">
          <h1 className="text-[17px] font-semibold text-gray-900">{pipelineLabel(edge)}</h1>
          <span className={cn('rounded px-2 py-0.5 text-[11px] font-semibold',
            isEda ? 'bg-violet-100 text-violet-700' : 'bg-sky-100 text-sky-700')}>
            {isEda ? '이벤트 스트림' : '데이터 동기화'}
          </span>
          <StatusBadge status={edge.status} />
          <div className="flex-1" />
          {edge.status === 'paused'
            ? <ActBtn icon="play"  label="Resume" onClick={async () => { await app.resumePipeline(edge.id); toast('Pipeline resumed') }} />
            : <ActBtn icon="pause" label="Pause"  onClick={async () => { await app.pausePipeline(edge.id); toast('Pipeline paused') }} />}
          <ActBtn icon="trash" label="Delete" danger
            onClick={async () => { await app.deletePipeline(edge.id); toast('Pipeline deleted', 'info'); app.setView('pipelines') }} />
        </div>
        <div className="mt-2.5 flex items-center gap-2 font-mono text-[12px] text-gray-500">
          <span className="text-gray-700">{source.alias ?? source.label}</span>
          <Icon name="arrow-right" size={13} className="text-gray-300" />
          <span>[{edge.table?.name}]</span>
          <Icon name="arrow-right" size={13} className="text-gray-300" />
          <span className="text-gray-700">{sink ? sink.alias ?? sink.label : edge.topic}</span>
        </div>
      </div>

      {/* tabs */}
      <div className="mt-4 flex gap-1 border-b border-gray-200">
        {tabs.map((t) => (
          <button key={t} onClick={() => setTab(t)}
            className={cn('-mb-px border-b-2 px-3 pb-2 text-[13px] font-medium transition-colors',
              tab === t ? 'border-brand-600 text-brand-700' : 'border-transparent text-gray-500 hover:text-gray-700')}>
            {t}
          </button>
        ))}
      </div>

      <div className="mt-4">
        {tab === 'Overview'          && <OverviewTab edge={edge} consumers={consumers} />}
        {tab === 'Consumers'         && <ConsumersTab edge={edge} consumers={consumers} />}
        {tab === 'Connector'         && <ConnectorTab edge={edge} />}
        {tab === 'Sync'              && <SyncTab edge={edge} />}
        {tab === 'Messages'          && <MessagesTab edge={edge} />}
        {tab === 'Connection Guide'  && <GuideTab edge={edge} />}
        {tab === 'Table Mapping'     && <MappingTab edge={edge} />}
      </div>
    </div>
  )
}

function ActBtn({ icon, label, onClick, danger }: {
  icon: 'play' | 'pause' | 'trash'; label: string; onClick: () => void; danger?: boolean
}) {
  return (
    <button onClick={onClick} className={cn(
      'flex items-center gap-1.5 rounded-md border px-2.5 py-1.5 text-[12.5px] font-medium transition-colors',
      danger ? 'border-rose-200 text-rose-600 hover:bg-rose-50' : 'border-gray-300 text-gray-700 hover:bg-gray-50',
    )}>
      <Icon name={icon} size={13} />
      {label}
    </button>
  )
}

/* ---------------------------------------------------------------- Overview tab */

function OverviewTab({ edge, consumers }: { edge: Edge; consumers: Node[] }) {
  const m = edge.metrics!
  const isEda = edge.pattern === 'fan-out'

  const topicMeta  = CLUSTER.CLUSTER_TOPICS.find((t) => t.name === edge.topic)
  const partitions = TOPIC_PARTITIONS[edge.topic] ?? []
  const retention  = TOPIC_RETENTION[edge.topic] ?? '7일'
  const replicaPct = topicMeta?.replicaPct ?? 100
  const isrOk      = replicaPct === 100

  const groups     = CONSUMER_GROUPS.filter((g) => consumers.some((c) => c.consumerGroup === g.name))
  const maxLagGroup = groups.length > 0 ? groups.reduce((a, b) => b.totalLag > a.totalLag ? b : a) : null

  const throughputData = useMemo(() => genSeries([
    { key: 'produced', base: m.produce_rate, vary: Math.max(10, m.produce_rate * 0.15) },
    { key: 'consumed', base: m.consume_rate, vary: Math.max(10, m.consume_rate * 0.12) },
  ], 24), [m.produce_rate, m.consume_rate])

  return (
    <div className="space-y-4">

      {/* metric cards */}
      <div className="grid grid-cols-4 gap-4">
        <MetricCard label="Produced"    value={formatNum(m.produce_rate)} sub="msg / sec" />
        <MetricCard label="Consumed"    value={formatNum(m.consume_rate)} sub="msg / sec" />
        {isEda ? (
          <MetricCard
            label="Max consumer lag"
            value={maxLagGroup ? formatNum(maxLagGroup.totalLag) : '—'}
            sub={maxLagGroup?.name ?? 'no groups'}
            tone={maxLagGroup && maxLagGroup.totalLag >= LAG_THRESHOLD ? 'warn' : 'good'}
          />
        ) : (
          <MetricCard label="소스 지연" value={`${m.lag}초`} sub="source.ts_ms 기준" tone={m.lag > 10 ? 'warn' : 'good'} />
        )}
        <MetricCard label="Error rate"  value={`${m.error_pct}%`} tone={m.error_pct > 0.5 ? 'bad' : 'good'} />
      </div>

      {/* throughput chart */}
      <Panel title="처리량 추이"
        right={
          <div className="flex items-center gap-4">
            <div className="flex items-center gap-1.5 text-[11.5px] text-gray-500">
              <span className="h-2 w-2 rounded-full" style={{ background: CHART_COLORS.brand }} />Produced
            </div>
            <div className="flex items-center gap-1.5 text-[11.5px] text-gray-500">
              <span className="h-2 w-2 rounded-full" style={{ background: CHART_COLORS.emerald }} />Consumed
            </div>
          </div>
        }>
        <div className="px-3 py-3">
          <TrendChart data={throughputData} type="area" height={160}
            series={[
              { key: 'produced', label: 'Produced', color: CHART_COLORS.brand },
              { key: 'consumed', label: 'Consumed', color: CHART_COLORS.emerald },
            ]} />
        </div>
      </Panel>

      {/* topic & partitions */}
      <Panel title="Topic & Partitions">
        {/* header row */}
        <div className="flex items-center gap-3 border-b border-gray-100 px-5 py-3.5">
          <span className="font-mono text-[13.5px] font-semibold text-gray-900">{edge.topic}</span>
          <StatusBadge status={topicMeta?.status ?? 'active'} />
          <div className="ml-auto flex items-center gap-4 text-[12px] text-gray-500">
            <span><span className="font-semibold text-gray-700">{partitions.length}</span> 파티션</span>
            <span className={cn('font-semibold', isrOk ? 'text-emerald-600' : 'text-amber-600')}>
              ISR {isrOk ? '정상' : `${Math.round(replicaPct)}%`}
            </span>
            <span>Retention <span className="font-semibold text-gray-700">{retention}</span></span>
          </div>
        </div>

        {partitions.length > 0 && <PartitionViz partitions={partitions} />}

        {/* table */}
        <table className="w-full text-[12.5px]">
          <thead>
            <tr className="border-b border-gray-100 text-left text-[10.5px] uppercase tracking-wide text-gray-400">
              <th className="px-5 py-2 font-semibold">Partition</th>
              <th className="px-4 py-2 font-semibold">Leader</th>
              <th className="px-4 py-2 text-right font-semibold">Begin offset</th>
              <th className="px-4 py-2 text-right font-semibold">End offset</th>
              <th className="px-5 py-2 font-semibold">Messages</th>
            </tr>
          </thead>
          <tbody>
            {(() => {
              const maxMsg = Math.max(...partitions.map((p) => p.endOffset - p.beginOffset), 1)
              return partitions.map((p) => {
                const msgCount = p.endOffset - p.beginOffset
                const pct = (msgCount / maxMsg) * 100
                return (
                  <tr key={p.id} className="border-b border-gray-50 last:border-0">
                    <td className="px-5 py-2.5">
                      <span className="rounded bg-gray-100 px-1.5 py-0.5 font-mono text-[11px] font-semibold text-gray-600">P{p.id}</span>
                    </td>
                    <td className="px-4 py-2.5 font-mono text-[11.5px] text-gray-500">{p.leader}</td>
                    <td className="px-4 py-2.5 text-right font-mono text-[11.5px] tabular-nums text-gray-500">{formatNum(p.beginOffset)}</td>
                    <td className="px-4 py-2.5 text-right font-mono text-[11.5px] tabular-nums text-gray-700">{formatNum(p.endOffset)}</td>
                    <td className="px-5 py-2.5">
                      <div className="flex items-center gap-2">
                        <div className="h-1.5 w-24 shrink-0 overflow-hidden rounded-full bg-gray-100">
                          <div className="h-full rounded-full bg-brand-400 transition-all" style={{ width: `${pct}%` }} />
                        </div>
                        <span className="font-mono text-[11.5px] tabular-nums font-semibold text-gray-800">
                          {formatNum(msgCount)}
                        </span>
                      </div>
                    </td>
                  </tr>
                )
              })
            })()}
            {partitions.length === 0 && (
              <tr><td colSpan={5} className="px-5 py-6 text-center text-gray-400">파티션 정보 없음</td></tr>
            )}
          </tbody>
        </table>
        {!isrOk && (
          <div className="mx-5 mb-4 mt-1 flex items-center gap-2 rounded-lg border border-amber-200 bg-amber-50 px-3.5 py-2.5 text-[12px] text-amber-700">
            <Icon name="alert" size={13} className="shrink-0" />
            Under-replicated partitions 감지 — 브로커 장애 시 데이터 유실 위험
          </div>
        )}
      </Panel>
    </div>
  )
}

/* ---------------------------------------------------------------- Consumers tab (EDA) */

function ConsumersTab({ edge, consumers }: { edge: Edge; consumers: Node[] }) {
  const groups = CONSUMER_GROUPS.filter((g) => consumers.some((c) => c.consumerGroup === g.name))
  const [openGroup, setOpenGroup] = useState<string | null>(groups[0]?.name ?? null)

  const lagChartData = groups.map((g) => ({ name: g.name, lag: g.totalLag }))
  const axis = { fontSize: 10, fill: '#94a3b8' }

  return (
    <div className="space-y-4">

      {/* lag bar chart */}
      <Panel title="Consumer Group Lag"
        right={
          <span className="text-[12px] text-gray-400">
            임계값 <span className="font-semibold text-amber-600">{formatNum(LAG_THRESHOLD)}</span>
          </span>
        }>
        {groups.length === 0 ? (
          <div className="px-5 py-10 text-center text-[12.5px] text-gray-400">Consumer group 없음</div>
        ) : (
          <>
            <div className="px-3 pt-3" style={{ height: 200 }}>
              <ResponsiveChart width="100%" height="100%" initialDimension={{ width: 300, height: 200 }}>
                <BarChart
                  data={lagChartData}
                  margin={{ top: 4, right: 16, bottom: 0, left: -8 }}
                  barSize={36}
                  onClick={(d) => d?.activeLabel && setOpenGroup(
                    openGroup === d.activeLabel ? null : d.activeLabel as string
                  )}
                >
                  <CartesianGrid strokeDasharray="3 3" stroke="#eef0f3" vertical={false} />
                  <XAxis dataKey="name" tick={{ ...axis, fontSize: 11 }} tickLine={false} axisLine={false} />
                  <YAxis tick={axis} tickLine={false} axisLine={false} width={56}
                    tickFormatter={(v) => formatNum(v as number)} />
                  <Tooltip contentStyle={tooltipStyle}
                    formatter={(v) => [formatNum(v as number), 'Total lag']} />
                  <Bar dataKey="lag" name="Total lag" radius={[4, 4, 0, 0]}>
                    {lagChartData.map((entry) => (
                      <Cell
                        key={entry.name}
                        fill={entry.lag >= LAG_THRESHOLD ? CHART_COLORS.amber : CHART_COLORS.emerald}
                        opacity={openGroup && openGroup !== entry.name ? 0.35 : 1}
                        cursor="pointer"
                      />
                    ))}
                  </Bar>
                </BarChart>
              </ResponsiveChart>
            </div>
            <p className="pb-3 pt-1 text-center text-[11px] text-gray-400">
              바를 클릭하면 파티션별 오프셋을 확인할 수 있습니다
            </p>
          </>
        )}
      </Panel>

      {/* groups table with expandable partition offsets */}
      <Panel title="Consumer Groups">
        <table className="w-full text-[12.5px]">
          <thead>
            <tr className="border-b border-gray-100 text-left text-[10.5px] uppercase tracking-wide text-gray-400">
              <th className="px-5 py-2 font-semibold">Group</th>
              <th className="px-4 py-2 font-semibold">State</th>
              <th className="px-4 py-2 font-semibold">Members</th>
              <th className="px-4 py-2 text-right font-semibold">Total lag</th>
              <th className="px-4 py-2 font-semibold">Last commit</th>
              <th className="px-5 py-2" />
            </tr>
          </thead>
          <tbody>
            {groups.map((g) => {
              const offsets  = PARTITION_OFFSETS[g.name] ?? []
              const isOpen   = openGroup === g.name
              return (
                <Fragment key={g.name}>
                  <tr className={cn('border-b border-gray-50', isOpen && 'bg-brand-50/30')}>
                    <td className="px-5 py-2.5 font-mono font-medium text-gray-800">{g.name}</td>
                    <td className="px-4 py-2.5"><StatusBadge status={g.state} /></td>
                    <td className="px-4 py-2.5 text-gray-600">{g.members}</td>
                    <td className={cn('px-4 py-2.5 text-right font-mono font-semibold tabular-nums',
                      g.totalLag >= LAG_THRESHOLD ? 'text-amber-600' : 'text-gray-700')}>
                      {formatNum(g.totalLag)}
                    </td>
                    <td className="px-4 py-2.5 text-gray-500">{g.lastCommit}</td>
                    <td className="px-5 py-2.5 text-right">
                      <button
                        onClick={() => setOpenGroup(isOpen ? null : g.name)}
                        className="flex items-center gap-1 rounded-md border border-gray-200 px-2 py-1 text-[11px] font-medium text-gray-500 hover:bg-gray-50"
                      >
                        오프셋 <Icon name={isOpen ? 'chevron-up' : 'chevron-down'} size={12} />
                      </button>
                    </td>
                  </tr>

                  {isOpen && (
                    <tr>
                      <td colSpan={6} className="bg-gray-50/60 px-5 pb-3 pt-2">
                        {offsets.length === 0 ? (
                          <p className="py-3 text-center text-[12px] text-gray-400">파티션 오프셋 정보 없음</p>
                        ) : (
                          <table className="w-full text-[11.5px]">
                            <thead>
                              <tr className="border-b border-gray-200 text-[10px] uppercase tracking-wide text-gray-400">
                                <th className="pb-1.5 pr-4 text-left font-semibold">Partition</th>
                                <th className="pb-1.5 pr-4 text-left font-semibold">Member</th>
                                <th className="pb-1.5 pr-4 text-right font-semibold">Committed offset</th>
                                <th className="pb-1.5 pr-4 text-right font-semibold">End offset</th>
                                <th className="pb-1.5 text-right font-semibold">Lag</th>
                              </tr>
                            </thead>
                            <tbody>
                              {offsets.map((o) => {
                                const lag = o.endOffset - o.committed
                                return (
                                  <tr key={o.partition} className="border-b border-gray-100 last:border-0">
                                    <td className="py-1.5 pr-4">
                                      <span className="rounded bg-gray-200 px-1.5 py-0.5 font-mono text-[10px] font-semibold text-gray-600">P{o.partition}</span>
                                    </td>
                                    <td className="py-1.5 pr-4 font-mono text-gray-600">
                                      {o.member ?? <span className="italic text-gray-300">unassigned</span>}
                                    </td>
                                    <td className="py-1.5 pr-4 text-right font-mono tabular-nums text-gray-500">{formatNum(o.committed)}</td>
                                    <td className="py-1.5 pr-4 text-right font-mono tabular-nums text-gray-500">{formatNum(o.endOffset)}</td>
                                    <td className={cn('py-1.5 text-right font-mono font-semibold tabular-nums',
                                      lag >= LAG_THRESHOLD ? 'text-amber-600' : lag > 0 ? 'text-gray-700' : 'text-emerald-500')}>
                                      {formatNum(lag)}
                                    </td>
                                  </tr>
                                )
                              })}
                            </tbody>
                          </table>
                        )}
                      </td>
                    </tr>
                  )}
                </Fragment>
              )
            })}
            {groups.length === 0 && (
              <tr><td colSpan={6} className="px-5 py-8 text-center text-gray-400">Consumer group 없음</td></tr>
            )}
          </tbody>
        </table>
      </Panel>
    </div>
  )
}

/* ---------------------------------------------------------------- Connector tab */

function connectorStateClass(state: string | null): string {
  switch (state) {
    case 'RUNNING':    return 'bg-emerald-100 text-emerald-700'
    case 'FAILED':     return 'bg-rose-100 text-rose-700'
    case 'PAUSED':     return 'bg-amber-100 text-amber-700'
    default:           return 'bg-gray-100 text-gray-500'   // UNASSIGNED / null(대기)
  }
}

function ConnectorCard({ c, topic }: { c: ConnectorInfo; topic: string }) {
  const isSource  = c.kind === 'source'
  const kindColor = isSource ? 'border-sky-200 bg-sky-50' : 'border-violet-200 bg-violet-50'
  const kindText  = isSource ? 'text-sky-700' : 'text-violet-700'

  return (
    <div className={cn('rounded-xl border-2', kindColor)}>
      {/* ── connector header ─────────────────────── */}
      <div className={cn('flex items-center gap-3 border-b px-5 py-3', kindColor)}>
        <div className={cn('flex h-7 w-7 items-center justify-center rounded-lg',
          isSource ? 'bg-sky-100' : 'bg-violet-100')}>
          <Icon name={isSource ? 'database' : 'layers'} size={14} className={kindText} />
        </div>
        <div className="min-w-0 flex-1">
          <div className="truncate text-[13px] font-bold text-gray-900">{c.name}</div>
          <div className="truncate font-mono text-[11px] text-gray-500">{c.connectorClass}</div>
        </div>
        <span className={cn('rounded-full px-2.5 py-0.5 text-[10.5px] font-bold uppercase', kindText,
          isSource ? 'bg-sky-100' : 'bg-violet-100')}>
          {c.kind}
        </span>
      </div>

      <div className="space-y-3 rounded-b-xl bg-white p-4">
        {c.lastError && (
          <div className="flex items-start gap-2 rounded-lg border border-rose-200 bg-rose-50 px-3.5 py-2.5 text-rose-700">
            <Icon name="alert" size={13} className="mt-0.5 shrink-0" />
            <span className="break-all font-mono text-[11px] leading-relaxed">{c.lastError}</span>
          </div>
        )}

        <div className="grid grid-cols-3 divide-x divide-gray-100 rounded-lg border border-gray-100 bg-gray-50">
          <div className="px-4 py-3">
            <div className="text-[10.5px] uppercase tracking-wide text-gray-400">State</div>
            <span className={cn('mt-1 inline-block rounded px-1.5 py-0.5 text-[11px] font-semibold',
              connectorStateClass(c.state))}>
              {c.state ?? '대기중'}
            </span>
          </div>
          <SyncStat label="Max Tasks" value={String(c.tasksMax)} />
          <SyncStat label="구독 토픽" value={topic} />
        </div>

        <div className="px-1 text-[11px] text-gray-400">
          마지막 상태 갱신: {c.updatedAt ? new Date(c.updatedAt).toLocaleString('ko-KR') : '—'}
        </div>
      </div>
    </div>
  )
}

function ConnectorTab({ edge }: { edge: Edge }) {
  const app = useApp()
  const wsId = app.currentProject?.id
  const [connectors, setConnectors] = useState<ConnectorInfo[] | null>(null)
  const [error, setError] = useState(false)

  useEffect(() => {
    if (!wsId) return
    let cancelled = false
    setConnectors(null)
    setError(false)
    api
      .listPipelineConnectors(wsId, edge.id)
      .then((cs) => { if (!cancelled) setConnectors(cs) })
      .catch(() => { if (!cancelled) setError(true) })
    return () => { cancelled = true }
  }, [wsId, edge.id])

  if (error) {
    return (
      <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-gray-200 bg-white py-16">
        <Icon name="alert" size={24} className="mb-2 text-rose-300" />
        <p className="text-[13px] text-gray-400">커넥터 정보를 불러오지 못했습니다</p>
      </div>
    )
  }
  if (connectors === null) {
    return (
      <div className="flex items-center justify-center rounded-xl border border-dashed border-gray-200 bg-white py-16 text-[13px] text-gray-400">
        불러오는 중…
      </div>
    )
  }
  if (connectors.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-gray-200 bg-white py-16">
        <Icon name="zap" size={24} className="mb-2 text-gray-300" />
        <p className="text-[13px] text-gray-400">이 파이프라인에 연결된 커넥터가 없습니다</p>
      </div>
    )
  }

  return (
    <div className="space-y-4">
      {connectors.map((c) => (
        <ConnectorCard key={c.name} c={c} topic={edge.topic} />
      ))}
    </div>
  )
}

/* ---------------------------------------------------------------- Sync tab (CDC) */

function SyncTab({ edge }: { edge: Edge }) {
  const app        = useApp()
  const wsId       = app.currentProject?.id
  const sourceNode = app.nodes.find((n) => n.id === edge.source) ?? null
  const sinkNode   = edge.sink ? app.nodes.find((n) => n.id === edge.sink) ?? null : null

  // 실제 source/sink 행수(#107). -1은 접속 실패/테이블 미존재(생성 중).
  const [sync, setSync]       = useState<SyncStatusResponse | null>(null)
  const [syncErr, setSyncErr] = useState(false)
  useEffect(() => {
    if (!wsId) return
    let cancelled = false
    setSync(null)
    setSyncErr(false)
    api
      .pipelineSyncStatus(wsId, edge.id)
      .then((s) => { if (!cancelled) setSync(s) })
      .catch(() => { if (!cancelled) setSyncErr(true) })
    return () => { cancelled = true }
  }, [wsId, edge.id])

  const tableName  = edge.table ? `${edge.table.schema}.${edge.table.name}` : '—'
  const sinkReady  = !!sync && sync.sourceRows >= 0 && sync.sinkRows >= 0
  const syncPct    = sinkReady
    ? (sync!.sourceRows > 0 ? (sync!.sinkRows / sync!.sourceRows) * 100 : 100)
    : 0
  const isHealthy  = sinkReady && syncPct >= 99.9
  const barColor   = isHealthy ? 'bg-emerald-400' : syncPct >= 99.0 ? 'bg-amber-400' : 'bg-rose-400'
  const pctColor   = isHealthy ? 'text-emerald-600' : syncPct >= 99.0 ? 'text-amber-600' : 'text-rose-600'

  const sourceDelay = useMemo(() => genSeries([{ key: 'delay', base: 3, vary: 9 }], 24), [])
  const deltaBase   = sync && sync.delta >= 0 ? sync.delta : 0
  const deltaTrend  = useMemo(() => genSeries([
    { key: 'delta', base: deltaBase, vary: deltaBase * 0.2 + 1, drift: -deltaBase / 48 },
  ], 24), [deltaBase])
  const eventDist   = useMemo(() => genSeries([
    { key: 'insert', base: 420, vary: 80 },
    { key: 'update', base: 210, vary: 40 },
    { key: 'delete', base: 35,  vary: 15 },
  ], 12), [])
  const axis = { fontSize: 10, fill: '#94a3b8' }

  return (
    <div className="space-y-4">
      <Panel>
        {/* ── Source ↔ Sink 시각 ─────────────────────────────────── */}
        <div className="px-8 py-8">
          <div className="grid grid-cols-[1fr_180px_1fr] items-center gap-6">

            {/* Source DB */}
            <DBNodeCard node={sourceNode} role="Source" />

            {/* Center: sync status */}
            <div className="flex flex-col items-center gap-3">
              <span className="rounded-full border border-gray-200 bg-gray-50 px-2.5 py-0.5 font-mono text-[10.5px] font-semibold text-gray-500">
                {tableName}
              </span>

              <div className="flex w-full items-center gap-1">
                <div className="h-px flex-1 border-t border-dashed border-gray-300" />
                <span className="shrink-0 rounded-full bg-gray-100 p-1">
                  <Icon name="arrow-right" size={12} className="text-gray-400" />
                </span>
                <div className="h-px flex-1 border-t border-dashed border-gray-300" />
              </div>

              <span className={cn('text-[22px] font-bold tabular-nums leading-none', pctColor)}>
                {syncPct.toFixed(3)}%
              </span>

              <div className="w-full overflow-hidden rounded-full bg-gray-100" style={{ height: 7 }}>
                <div
                  className={cn('h-full rounded-full transition-all', barColor)}
                  style={{ width: `${Math.min(syncPct, 100)}%` }}
                />
              </div>

              <span className={cn('text-[11.5px] font-medium', isHealthy ? 'text-emerald-600' : 'text-amber-600')}>
                {!sinkReady
                  ? 'sink 준비중'
                  : isHealthy
                    ? '동기화 완료'
                    : `Δ +${formatNum(Math.max(0, sync?.delta ?? 0))} rows`}
              </span>
            </div>

            {/* Sink DB */}
            <DBNodeCard node={sinkNode} role="Sink" />
          </div>
        </div>

        {/* ── 메트릭 수치(실제 행수, #107) ───────────────────────────── */}
        {syncErr ? (
          <div className="border-t border-gray-100 px-6 py-4 text-center text-[12.5px] text-gray-400">
            동기화 상태를 불러오지 못했습니다
          </div>
        ) : sync === null ? (
          <div className="border-t border-gray-100 px-6 py-4 text-center text-[12.5px] text-gray-400">
            불러오는 중…
          </div>
        ) : (
          <div className="grid grid-cols-4 divide-x divide-gray-100 border-t border-gray-100">
            <SyncStat label="Source rows"  value={sync.sourceRows < 0 ? '—' : formatNum(sync.sourceRows)} />
            <SyncStat label="Sink rows"    value={sync.sinkRows < 0 ? '준비중' : formatNum(sync.sinkRows)}
              tone={sync.sinkRows < 0 ? 'warn' : undefined} />
            <SyncStat
              label="미동기화 Δ"
              value={sync.delta < 0 ? '—' : sync.delta > 0 ? `+${formatNum(sync.delta)}` : '0 ✓'}
              tone={sync.delta > 5000 ? 'warn' : 'good'}
            />
            <SyncStat label="조회 시각" value={new Date(sync.checkedAt).toLocaleTimeString('ko-KR')} />
          </div>
        )}
      </Panel>

      {/* ── 차트 패널 ─────────────────────────────────────────────── */}
      <div className="grid grid-cols-2 gap-3">
        <Panel title="소스 지연 추이 (ms)" right={<span className="text-[12px] text-gray-400">최근 2시간</span>}>
          <div className="px-3 py-3">
            <TrendChart
              data={sourceDelay} type="area" height={130}
              series={[{ key: 'delay', label: '지연 (ms)', color: CHART_COLORS.violet }]}
            />
          </div>
        </Panel>
        <Panel title="미동기화 Rows 추이" right={<span className="text-[12px] text-gray-400">최근 2시간</span>}>
          <div className="px-3 py-3">
            <TrendChart
              data={deltaTrend} type="area" height={130}
              series={[{ key: 'delta', label: 'Δ rows', color: CHART_COLORS.amber }]}
            />
          </div>
        </Panel>
      </div>

      <Panel title="이벤트 타입 분포" right={<span className="text-[12px] text-gray-400">최근 1시간</span>}>
        <div className="px-4 py-3">
          <ResponsiveChart width="100%" height={140}>
            <BarChart data={eventDist} barSize={10} margin={{ top: 4, right: 8, left: -28, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" vertical={false} />
              <XAxis dataKey="ts" tick={axis} tickLine={false} axisLine={false} />
              <YAxis tick={axis} tickLine={false} axisLine={false} />
              <Tooltip contentStyle={tooltipStyle} />
              <Bar dataKey="insert" stackId="a" fill={CHART_COLORS.emerald} name="INSERT" radius={[0,0,0,0]} />
              <Bar dataKey="update" stackId="a" fill={CHART_COLORS.amber}   name="UPDATE" radius={[0,0,0,0]} />
              <Bar dataKey="delete" stackId="a" fill={CHART_COLORS.red}     name="DELETE" radius={[2,2,0,0]} />
            </BarChart>
          </ResponsiveChart>
          <div className="mt-2 flex justify-center gap-5">
            {[['INSERT', CHART_COLORS.emerald], ['UPDATE', CHART_COLORS.amber], ['DELETE', CHART_COLORS.red]].map(([label, color]) => (
              <div key={label} className="flex items-center gap-1.5">
                <span className="h-2.5 w-2.5 rounded-sm" style={{ background: color }} />
                <span className="text-[11px] text-gray-500">{label}</span>
              </div>
            ))}
          </div>
        </div>
      </Panel>
    </div>
  )
}

function DBNodeCard({ node, role }: { node: Node | null; role: 'Source' | 'Sink' }) {
  if (!node) return (
    <div className="flex flex-col items-center gap-2 rounded-xl border border-dashed border-gray-200 px-6 py-8">
      <Icon name="database" size={28} className="text-gray-300" />
      <span className="text-[12px] text-gray-400">연결된 DB 없음</span>
    </div>
  )
  return (
    <div className="flex flex-col items-center gap-2.5 rounded-xl border border-gray-200 bg-white px-5 py-6 text-center shadow-sm">
      <TechIcon kind={nodeKind(node)} size={44} />
      <div>
        <div className="text-[14px] font-semibold text-gray-900">{node.alias ?? node.label}</div>
        <div className="mt-0.5 text-[11.5px] text-gray-500">{node.techLabel}</div>
        <div className="mt-0.5 font-mono text-[10.5px] text-gray-400">{node.host}</div>
      </div>
      <span className={cn(
        'rounded px-2 py-0.5 text-[9.5px] font-bold uppercase',
        role === 'Source' ? 'bg-sky-100 text-sky-700' : 'bg-violet-100 text-violet-700',
      )}>
        {role}
      </span>
      <div className="flex items-center gap-1.5 text-[11px]">
        <span className={cn('h-1.5 w-1.5 rounded-full',
          node.status === 'healthy' ? 'bg-emerald-400' : 'bg-amber-400')} />
        <span className="text-gray-500">{node.status}</span>
      </div>
    </div>
  )
}

/* ---------------------------------------------------------------- Messages tab */

const OP_META: Record<KafkaMsg['op'], { label: string; cls: string }> = {
  c: { label: 'INSERT', cls: 'bg-emerald-50 text-emerald-700 border-emerald-200' },
  u: { label: 'UPDATE', cls: 'bg-amber-50  text-amber-700  border-amber-200'  },
  d: { label: 'DELETE', cls: 'bg-rose-50   text-rose-700   border-rose-200'   },
  r: { label: 'READ',   cls: 'bg-sky-50    text-sky-700    border-sky-200'    },
}

function MessagesTab({ edge }: { edge: Edge }) {
  const isEda = edge.pattern === 'fan-out'
  const seedData = isEda ? EDA_MESSAGES : CDC_MESSAGES
  const maxPartitions = isEda ? 6 : 3

  const [msgs, setMsgs] = useState<KafkaMsg[]>(seedData)
  const [partition, setPartition] = useState<'all' | number>('all')
  const [live, setLive] = useState(false)
  const [search, setSearch] = useState('')
  const [selected, setSelected] = useState<number | null>(null)
  const [rawView, setRawView] = useState(false)
  const liveRef = useRef(live)
  liveRef.current = live

  useEffect(() => {
    if (!live) return
    const id = setInterval(() => {
      if (!liveRef.current) return
      const next = isEda ? nextEdaMsg() : nextCdcMsg()
      setMsgs((prev) => [next, ...prev].slice(0, 100))
    }, 1800)
    return () => clearInterval(id)
  }, [live, isEda])

  const visible = msgs.filter((m) => {
    if (partition !== 'all' && m.partition !== partition) return false
    if (search && !m.key?.toLowerCase().includes(search.toLowerCase()) &&
        !JSON.stringify(m.after ?? m.before ?? '').toLowerCase().includes(search.toLowerCase())) return false
    return true
  })

  const selectedMsg = selected !== null ? msgs.find((m) => m.offset === selected) ?? null : null

  function buildRawEnvelope(m: KafkaMsg) {
    return {
      op: m.op,
      ts_ms: m.tsMs,
      source: {
        connector: m.headers['debezium.connector'] ?? 'unknown',
        db: m.headers['debezium.db'] ?? edge.table?.schema ?? 'db',
        table: edge.table?.name ?? 'table',
        lsn: `0/${m.offset.toString(16).toUpperCase()}`,
      },
      before: m.before,
      after: m.after,
    }
  }

  return (
    <div className="space-y-3">
      {/* toolbar */}
      <div className="flex items-center gap-2 rounded-xl border border-gray-200 bg-white px-4 py-2.5">
        {/* partition selector */}
        <div className="flex items-center gap-1">
          <span className="text-[11.5px] text-gray-400">파티션</span>
          <select
            value={partition === 'all' ? 'all' : String(partition)}
            onChange={(e) => setPartition(e.target.value === 'all' ? 'all' : Number(e.target.value))}
            className="rounded border border-gray-200 px-2 py-1 text-[12px] font-medium text-gray-700 outline-none focus:border-brand-400"
          >
            <option value="all">전체</option>
            {Array.from({ length: maxPartitions }, (_, i) => (
              <option key={i} value={i}>P{i}</option>
            ))}
          </select>
        </div>

        <div className="h-4 w-px bg-gray-200" />

        {/* search */}
        <div className="relative flex-1">
          <Icon name="search" size={13} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-gray-400" />
          <input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Key 또는 Value 검색"
            className="h-7 w-full rounded border border-gray-200 bg-gray-50 pl-8 pr-3 text-[12px] outline-none focus:border-brand-400 focus:bg-white"
          />
        </div>

        <div className="h-4 w-px bg-gray-200" />

        {/* raw toggle */}
        <button
          onClick={() => setRawView((v) => !v)}
          className={cn(
            'flex items-center gap-1.5 rounded border px-2.5 py-1 text-[11.5px] font-medium transition-colors',
            rawView ? 'border-brand-300 bg-brand-50 text-brand-700' : 'border-gray-200 text-gray-500 hover:bg-gray-50',
          )}
        >
          <Icon name="log" size={12} />
          Raw envelope
        </button>

        {/* live toggle */}
        <button
          onClick={() => setLive((v) => !v)}
          className={cn(
            'flex items-center gap-1.5 rounded border px-2.5 py-1 text-[11.5px] font-medium transition-colors',
            live ? 'border-rose-300 bg-rose-50 text-rose-600' : 'border-gray-200 text-gray-500 hover:bg-gray-50',
          )}
        >
          <span className={cn('h-1.5 w-1.5 rounded-full', live ? 'animate-pulse bg-rose-500' : 'bg-gray-300')} />
          {live ? 'Live' : 'Live'}
        </button>

        <span className="ml-auto text-[11px] text-gray-400">
          {visible.length}개 메시지
        </span>
      </div>

      {/* table */}
      <div className="overflow-hidden rounded-xl border border-gray-200 bg-white">
        {/* header */}
        <div className="grid grid-cols-[40px_90px_160px_1fr_80px_80px] gap-0 border-b border-gray-100 bg-gray-50 px-4 py-2">
          {['P', 'Offset', 'Timestamp', 'Key', 'Op', 'Size'].map((h) => (
            <span key={h} className="text-[11px] font-semibold uppercase tracking-wide text-gray-400">{h}</span>
          ))}
        </div>

        <div className="divide-y divide-gray-50">
          {visible.length === 0 && (
            <div className="py-12 text-center text-[13px] text-gray-400">메시지가 없습니다.</div>
          )}
          {visible.map((m) => {
            const isOpen = selected === m.offset
            const meta = OP_META[m.op]
            return (
              <div key={`${m.partition}-${m.offset}`}>
                <button
                  onClick={() => setSelected(isOpen ? null : m.offset)}
                  className={cn(
                    'grid w-full grid-cols-[40px_90px_160px_1fr_80px_80px] items-center gap-0 px-4 py-2.5 text-left text-[12px] transition-colors hover:bg-gray-50/70',
                    isOpen && 'bg-brand-50/40',
                  )}
                >
                  <span className="font-mono text-gray-400">P{m.partition}</span>
                  <span className="font-mono text-gray-600">{formatNum(m.offset)}</span>
                  <span className="font-mono text-gray-500">{m.tsLabel}</span>
                  <span className="truncate font-mono font-medium text-gray-800">{m.key ?? '(null)'}</span>
                  <span>
                    <span className={cn('rounded border px-1.5 py-0.5 text-[10px] font-bold', meta.cls)}>
                      {meta.label}
                    </span>
                  </span>
                  <span className="font-mono text-[11px] text-gray-400">{m.valueSize} B</span>
                </button>

                {isOpen && selectedMsg && (
                  <div className="border-t border-gray-100 bg-gray-50/60 px-4 py-3">
                    {/* meta row */}
                    <div className="mb-3 flex flex-wrap items-center gap-3 text-[11px] text-gray-400">
                      <span className="font-mono">Partition <strong className="text-gray-600">{m.partition}</strong></span>
                      <span>·</span>
                      <span className="font-mono">Offset <strong className="text-gray-600">{formatNum(m.offset)}</strong></span>
                      <span>·</span>
                      <span className="font-mono">{m.tsLabel}</span>
                      {Object.entries(m.headers).map(([k, v]) => (
                        <span key={k} className="rounded bg-gray-200 px-1.5 py-0.5 font-mono text-gray-500">
                          {k}: {v}
                        </span>
                      ))}
                    </div>

                    {rawView ? (
                      /* raw debezium envelope */
                      <div>
                        <div className="mb-1 text-[10.5px] font-semibold uppercase tracking-wide text-gray-400">Debezium Envelope</div>
                        <pre className="overflow-x-auto rounded-lg border border-gray-200 bg-[#1b1e24] px-4 py-3 font-mono text-[11.5px] leading-relaxed text-gray-200">
                          {JSON.stringify(buildRawEnvelope(m), null, 2)}
                        </pre>
                      </div>
                    ) : (
                      /* friendly before/after */
                      <div className="grid grid-cols-2 gap-3">
                        <MsgJsonBox
                          title={m.op === 'c' ? 'before (신규)' : 'before (변경 전)'}
                          data={m.before}
                          empty="null"
                        />
                        <MsgJsonBox
                          title={m.op === 'd' ? 'after (삭제됨)' : 'after (변경 후)'}
                          data={m.after}
                          empty="null"
                        />
                      </div>
                    )}
                  </div>
                )}
              </div>
            )
          })}
        </div>
      </div>
    </div>
  )
}

function MsgJsonBox({ title, data, empty }: { title: string; data: unknown; empty: string }) {
  return (
    <div>
      <div className="mb-1.5 font-mono text-[10.5px] font-semibold uppercase tracking-wide text-gray-400">{title}</div>
      <pre className="overflow-x-auto rounded-lg border border-gray-200 bg-white px-3 py-2.5 font-mono text-[11.5px] leading-relaxed text-gray-700">
        {data == null ? empty : JSON.stringify(data, null, 2)}
      </pre>
    </div>
  )
}

/* ---------------------------------------------------------------- Connection Guide tab (EDA) */

function GuideTab({ edge }: { edge: Edge }) {
  const toast = useToast()
  const [lang, setLang] = useState<'Java' | 'Python' | 'Node.js'>('Java')
  const [reveal, setReveal] = useState(false)
  const copy = (v: string, what: string) => { navigator.clipboard?.writeText(v); toast(`${what} copied`) }
  const snippets: Record<string, string> = {
    Java:     `var props = new Properties();\nprops.put("bootstrap.servers", "${BOOTSTRAP_SERVER}");\nprops.put("group.id", "my-consumer");\nprops.put("key.deserializer", StringDeserializer.class);\n\nvar consumer = new KafkaConsumer<>(props);\nconsumer.subscribe(List.of("${edge.topic}"));`,
    Python:   `from kafka import KafkaConsumer\n\nconsumer = KafkaConsumer(\n    "${edge.topic}",\n    bootstrap_servers="${BOOTSTRAP_SERVER}",\n    group_id="my-consumer",\n)\nfor msg in consumer:\n    handle(msg.value)`,
    'Node.js': `const { Kafka } = require("kafkajs")\nconst kafka = new Kafka({ brokers: ["${BOOTSTRAP_SERVER}"] })\nconst consumer = kafka.consumer({ groupId: "my-consumer" })\nawait consumer.subscribe({ topic: "${edge.topic}" })`,
  }

  return (
    <div className="space-y-4">
      <Panel title="Connection details">
        <div className="space-y-2.5 px-4 py-3.5">
          <Row label="Bootstrap server" value={BOOTSTRAP_SERVER} onCopy={() => copy(BOOTSTRAP_SERVER, 'Bootstrap server')} />
          <Row label="Topic name"       value={edge.topic}        onCopy={() => copy(edge.topic, 'Topic name')} />
          <div className="flex items-center gap-2 text-[12.5px]">
            <span className="w-36 shrink-0 text-gray-500">Auth credentials</span>
            <span className="flex-1 font-mono text-gray-700">
              {reveal ? 'SCRAM-SHA-512 · user=consumer-svc · pw=•k3yR0t8a' : '•••••••••••••••••••'}
            </span>
            <button onClick={() => setReveal(r => !r)} className="text-gray-400 hover:text-gray-700">
              <Icon name={reveal ? 'eye-off' : 'eye'} size={15} />
            </button>
          </div>
        </div>
      </Panel>
      <Panel title="Consumer code"
        right={
          <div className="flex gap-1">
            {(['Java', 'Python', 'Node.js'] as const).map((l) => (
              <button key={l} onClick={() => setLang(l)}
                className={cn('rounded px-2 py-1 text-[11.5px] font-medium',
                  lang === l ? 'bg-brand-600 text-white' : 'text-gray-500 hover:bg-gray-100')}>
                {l}
              </button>
            ))}
          </div>
        }>
        <div className="relative">
          <button onClick={() => copy(snippets[lang], `${lang} snippet`)}
            className="absolute right-3 top-3 rounded border border-white/15 bg-white/10 px-1.5 py-0.5 text-[11px] text-gray-300 hover:bg-white/20">
            Copy
          </button>
          <pre className="overflow-x-auto rounded-b-xl bg-[#1b1e24] px-4 py-3.5 font-mono text-[12px] leading-relaxed text-gray-200">
            {snippets[lang]}
          </pre>
        </div>
      </Panel>
    </div>
  )
}

function Row({ label, value, onCopy }: { label: string; value: string; onCopy: () => void }) {
  return (
    <div className="flex items-center gap-2 text-[12.5px]">
      <span className="w-36 shrink-0 text-gray-500">{label}</span>
      <span className="flex-1 truncate font-mono text-gray-700">{value}</span>
      <button onClick={onCopy} className="text-gray-400 hover:text-gray-700">
        <Icon name="copy" size={14} />
      </button>
    </div>
  )
}

/* ---------------------------------------------------------------- Table Mapping tab (CDC) */

function MappingTab({ edge }: { edge: Edge }) {
  const app = useApp()
  const wsId = app.currentProject?.id
  const [cols, setCols] = useState<SchemaColumn[] | null>(null)
  const [error, setError] = useState(false)

  useEffect(() => {
    if (!wsId) return
    let cancelled = false
    setCols(null)
    setError(false)
    api
      .databaseSchema(wsId, edge.source)
      .then((res) => {
        if (cancelled) return
        const t = res.tables.find(
          (tb) => tb.name === edge.table?.name && (!edge.table?.schema || tb.schema === edge.table?.schema),
        )
        setCols(t?.columns ?? [])
      })
      .catch(() => { if (!cancelled) setError(true) })
    return () => { cancelled = true }
  }, [wsId, edge.source, edge.table?.name, edge.table?.schema])

  return (
    <Panel title={`Table mapping · ${edge.table?.schema}.${edge.table?.name}`}>
      {error ? (
        <div className="px-4 py-10 text-center text-[13px] text-gray-400">스키마를 불러오지 못했습니다</div>
      ) : cols === null ? (
        <div className="px-4 py-10 text-center text-[13px] text-gray-400">불러오는 중…</div>
      ) : cols.length === 0 ? (
        <div className="px-4 py-10 text-center text-[13px] text-gray-400">컬럼 정보를 찾을 수 없습니다</div>
      ) : (
        <table className="w-full text-[12.5px]">
          <thead>
            <tr className="border-b border-gray-100 text-left text-[11px] uppercase tracking-wide text-gray-400">
              <th className="px-4 py-2 font-semibold">Source column</th>
              <th className="px-4 py-2 font-semibold">Destination column</th>
              <th className="px-4 py-2 font-semibold">Type</th>
              <th className="px-4 py-2 font-semibold">Flags</th>
            </tr>
          </thead>
          <tbody>
            {cols.map((c) => (
              <tr key={c.name} className="border-b border-gray-50">
                <td className="px-4 py-2.5 font-mono font-medium text-gray-800">{c.name}</td>
                {/* CDC(direct)는 동일 컬럼명으로 복제 → 대상 컬럼명 동일 */}
                <td className="px-4 py-2.5 font-mono text-gray-600">{c.name}</td>
                <td className="px-4 py-2.5 font-mono text-gray-500">{c.type}</td>
                <td className="px-4 py-2.5">
                  <div className="flex gap-1.5">
                    {c.primaryKey && <span className="rounded bg-violet-50 px-1.5 py-0.5 text-[10px] font-semibold text-violet-700">PK</span>}
                    {!c.nullable && <span className="rounded bg-gray-100 px-1.5 py-0.5 text-[10px] font-semibold text-gray-500">NOT NULL</span>}
                    {c.indexed && <span className="rounded bg-sky-50 px-1.5 py-0.5 text-[10px] font-semibold text-sky-700">INDEX</span>}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Panel>
  )
}

/* ---------------------------------------------------------------- Partition visualization */

function PartitionViz({ partitions }: {
  partitions: Array<{ id: number; leader: string; beginOffset: number; endOffset: number }>
}) {
  const chartData = partitions.map((p) => ({
    name: `P${p.id}`,
    messages: p.endOffset - p.beginOffset,
    leader: p.leader,
  }))
  const msgs = chartData.map((d) => d.messages)
  const avg = msgs.reduce((a, b) => a + b, 0) / msgs.length
  const max = Math.max(...msgs, 1)
  const isSkewed = max > avg * 1.3

  // broker color mapping
  const brokers = [...new Set(partitions.map((p) => p.leader))].sort()
  const brokerColors: Record<string, string> = {
    [brokers[0]]: CHART_COLORS.brand,
    [brokers[1]]: CHART_COLORS.violet,
    [brokers[2]]: CHART_COLORS.emerald,
    [brokers[3]]: CHART_COLORS.amber,
  }
  const brokerCount = brokers.reduce<Record<string, number>>((acc, b) => {
    acc[b] = partitions.filter((p) => p.leader === b).length
    return acc
  }, {})

  const axis = { fontSize: 10, fill: '#94a3b8' }
  const chartH = Math.max(140, partitions.length * 28)

  return (
    <div className="grid grid-cols-[1fr_200px] gap-0 border-b border-gray-100 bg-gray-50/40">
      {/* partition balance bar chart */}
      <div className="border-r border-gray-100 px-4 py-3">
        <div className="mb-1.5 flex items-center gap-2">
          <span className="text-[11px] font-semibold uppercase tracking-wide text-gray-400">Partition Balance</span>
          {isSkewed && (
            <span className="flex items-center gap-1 rounded-full border border-amber-200 bg-amber-50 px-2 py-0.5 text-[10px] font-semibold text-amber-600">
              <Icon name="alert" size={10} />편향 감지
            </span>
          )}
        </div>
        <div style={{ height: chartH }}>
          <ResponsiveChart width="100%" height="100%" initialDimension={{ width: 300, height: chartH }}>
            <BarChart layout="vertical" data={chartData}
              margin={{ top: 0, right: 40, bottom: 0, left: 4 }} barSize={14}>
              <XAxis type="number" tick={axis} tickLine={false} axisLine={false}
                tickFormatter={(v) => formatNum(v as number)} width={60} />
              <YAxis type="category" dataKey="name" tick={{ ...axis, fontSize: 11 }}
                tickLine={false} axisLine={false} width={28} />
              <Tooltip contentStyle={tooltipStyle}
                formatter={(v) => [formatNum(v as number), 'Messages']} />
              <Bar dataKey="messages" radius={[0, 4, 4, 0]}>
                {chartData.map((entry) => (
                  <Cell
                    key={entry.name}
                    fill={entry.messages > avg * 1.3 ? CHART_COLORS.amber : brokerColors[entry.leader] ?? CHART_COLORS.brand}
                  />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveChart>
        </div>
      </div>

      {/* broker leader distribution */}
      <div className="px-4 py-3">
        <div className="mb-2 text-[11px] font-semibold uppercase tracking-wide text-gray-400">Leader 분포</div>
        <div className="space-y-2.5">
          {brokers.map((broker) => {
            const count = brokerCount[broker] ?? 0
            const pct = (count / partitions.length) * 100
            const color = brokerColors[broker] ?? CHART_COLORS.brand
            return (
              <div key={broker}>
                <div className="mb-1 flex items-center justify-between">
                  <span className="font-mono text-[11px] text-gray-600">{broker}</span>
                  <span className="text-[11px] font-semibold text-gray-700">{count}개</span>
                </div>
                <div className="h-1.5 w-full overflow-hidden rounded-full bg-gray-200">
                  <div className="h-full rounded-full transition-all" style={{ width: `${pct}%`, background: color }} />
                </div>
              </div>
            )
          })}
        </div>
        <div className="mt-3 border-t border-gray-100 pt-2.5">
          <div className="text-[10.5px] text-gray-400">
            {Object.values(brokerCount).every((c) => c === Object.values(brokerCount)[0])
              ? <span className="font-medium text-emerald-600">균등 분배</span>
              : <span className="font-medium text-amber-600">불균등 분배</span>}
          </div>
        </div>
      </div>
    </div>
  )
}

/* ---------------------------------------------------------------- shared helpers */

function SyncStat({ label, value, tone }: { label: string; value: string; tone?: 'good' | 'warn' }) {
  return (
    <div className="px-5 py-3.5">
      <div className="text-[11px] uppercase tracking-wide text-gray-400">{label}</div>
      <div className={cn('mt-0.5 truncate text-[14px] font-semibold',
        tone === 'warn' ? 'text-amber-600' : tone === 'good' ? 'text-emerald-600' : 'text-gray-900')}>
        {value}
      </div>
    </div>
  )
}

function Kv({ label, value, tone }: { label: string; value: string; tone?: 'warn' }) {
  return (
    <div className="flex items-center gap-1.5 text-[12.5px]">
      <span className="text-gray-400">{label}</span>
      <span className={cn('font-semibold', tone === 'warn' ? 'text-amber-600' : 'text-gray-700')}>{value}</span>
    </div>
  )
}
