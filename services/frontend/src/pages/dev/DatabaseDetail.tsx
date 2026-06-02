import { useState } from 'react'
import { Icon } from '../../components/Icon'
import { TechIcon, nodeKind } from '../../components/TechIcon'
import { MetricCard, Panel, StatusBadge } from '../../components/blocks'
import { useToast } from '../../components/Toast'
import { useApp } from '../../store/AppStore'
import { dbPipelines, nodeName, pipelineLabel } from '../../data/helpers'
import type { CapabilityCheck, Edge, Node } from '../../data/types'
import { cn } from '../../lib/format'

const SCHEMA_TABLES = [
  { name: 'orders', rows: '8.4M', size: '4.2 GB', cols: [
    { name: 'id', type: 'bigint', pk: true },
    { name: 'customer_id', type: 'bigint', pk: false },
    { name: 'status', type: 'varchar(32)', pk: false },
    { name: 'amount', type: 'numeric(12,2)', pk: false },
    { name: 'created_at', type: 'timestamptz', pk: false },
  ] },
  { name: 'order_items', rows: '21.7M', size: '6.1 GB', cols: [
    { name: 'id', type: 'bigint', pk: true },
    { name: 'order_id', type: 'bigint', pk: false },
    { name: 'sku', type: 'varchar(64)', pk: false },
    { name: 'qty', type: 'integer', pk: false },
  ] },
  { name: 'customers', rows: '2.1M', size: '1.4 GB', cols: [
    { name: 'id', type: 'bigint', pk: true },
    { name: 'email', type: 'varchar(255)', pk: false },
    { name: 'created_at', type: 'timestamptz', pk: false },
  ] },
  { name: 'audit_log', rows: '54M', size: '0.4 GB', cols: [
    { name: 'id', type: 'bigint', pk: true },
    { name: 'actor', type: 'varchar(128)', pk: false },
    { name: 'action', type: 'varchar(64)', pk: false },
  ] },
]

function defaultChecks(node: Node): CapabilityCheck[] {
  if (node.checks) return node.checks
  if (node.tech === 'mariadb')
    return [
      { label: 'binlog_format = ROW', state: 'pass', detail: 'Row-based binary logging is enabled.' },
      { label: 'REPLICATION SLAVE privilege', state: 'pass', detail: 'The CDC user can read the binlog.' },
      { label: 'GTID mode', state: node.status === 'healthy' ? 'pass' : 'warn', detail: 'GTID enables safe failover repositioning.' },
      { label: 'binlog retention', state: 'pass', detail: 'Retention window is 7 days.' },
    ]
  return [
    { label: 'wal_level = logical', state: 'pass', detail: 'Logical decoding is enabled.' },
    { label: 'REPLICATION privilege', state: 'pass', detail: 'The CDC user can create replication slots.' },
    { label: 'Replication slot capacity', state: node.cdc?.slots?.startsWith('0') ? 'pass' : 'pass', detail: `Slots in use: ${node.cdc?.slots}.` },
    { label: 'REPLICA IDENTITY FULL', state: node.status === 'healthy' ? 'pass' : 'warn', detail: 'Some tables use DEFAULT — updates ship key columns only.' },
  ]
}

export function DatabaseDetail() {
  const app = useApp()
  const toast = useToast()
  const node = app.nodes.find((n) => n.id === app.selectedDatabaseId)
  const [tab, setTab] = useState('Overview')
  const tabs = ['Overview', 'Schema', '연결 검사', 'Pipelines']

  if (!node) return <div className="px-6 py-10 text-sm text-gray-500">Database not found.</div>
  const pipelines = dbPipelines(node.id, app.edges)

  return (
    <div className="px-6 py-5">
      <button
        onClick={() => app.setView('databases')}
        className="mb-3 flex items-center gap-1.5 text-[12.5px] font-medium text-gray-500 hover:text-gray-800"
      >
        <Icon name="arrow-left" size={15} />
        Databases
      </button>

      <div className="flex items-center gap-3 rounded-xl border border-gray-200 bg-white px-5 py-4">
        <TechIcon kind={nodeKind(node)} size={44} />
        <div>
          <div className="flex items-center gap-2">
            <h1 className="text-[17px] font-semibold text-gray-900">{nodeName(node)}</h1>
            <span className="rounded bg-gray-100 px-1.5 py-0.5 text-[10px] font-medium text-gray-500">
              {node.techLabel}
            </span>
            <StatusBadge status={node.status} label={node.status === 'healthy' ? 'connected' : node.status} />
          </div>
          <div className="mt-0.5 font-mono text-[12px] text-gray-400">{node.host}</div>
        </div>
        <div className="flex-1" />
        <button
          onClick={() => toast('Re-scanning database capabilities…')}
          className="flex items-center gap-1.5 rounded-md border border-gray-300 px-2.5 py-1.5 text-[12.5px] font-medium text-gray-700 hover:bg-gray-50"
        >
          <Icon name="refresh" size={13} />
          Re-scan
        </button>
      </div>

      <div className="mt-4 flex gap-1 border-b border-gray-200">
        {tabs.map((t) => (
          <button
            key={t}
            onClick={() => setTab(t)}
            className={cn(
              '-mb-px border-b-2 px-3 pb-2 text-[13px] font-medium transition-colors',
              tab === t ? 'border-brand-600 text-brand-700' : 'border-transparent text-gray-500 hover:text-gray-700',
            )}
          >
            {t}
          </button>
        ))}
      </div>

      <div className="mt-4">
        {tab === 'Overview' && (
          <div className="space-y-4">
            <div className="grid grid-cols-4 gap-4">
              <MetricCard label="Status" value={<StatusBadge status={node.status} />} />
              <MetricCard label="TPS" value={node.metrics?.tps ?? 0} />
              <MetricCard label="Replication lag" value={`${node.metrics?.lag_ms ?? 0}`} sub="ms" tone={(node.metrics?.lag_ms ?? 0) > 1000 ? 'warn' : 'good'} />
              <MetricCard label="Schema size" value={node.schema?.size ?? '—'} sub={`${node.schema?.tables ?? 0} tables`} />
            </div>
            <Panel title="Pipeline 역할">
              {pipelines.length === 0 ? (
                <div className="px-4 py-6 text-center text-[12.5px] text-gray-400">
                  연결된 파이프라인이 없습니다
                </div>
              ) : (
                <div className="divide-y divide-gray-50">
                  {pipelines.map((e) => {
                    const isSource = e.source === node.id
                    const isEda = e.pattern === 'fan-out'
                    return (
                      <button
                        key={e.id}
                        onClick={() => app.openPipeline(e.id)}
                        className="flex w-full items-center gap-3 px-4 py-3 text-left hover:bg-gray-50"
                      >
                        <Icon name="route" size={14} className="shrink-0 text-gray-400" />
                        <span className="flex-1 truncate text-[13px] font-medium text-gray-800">
                          {pipelineLabel(e)}
                        </span>
                        <span className={cn(
                          'shrink-0 rounded px-1.5 py-0.5 text-[9.5px] font-bold uppercase',
                          isEda ? 'bg-violet-100 text-violet-700' : 'bg-sky-100 text-sky-700',
                        )}>
                          {isEda ? 'EDA' : 'CDC'}
                        </span>
                        <span className={cn(
                          'shrink-0 rounded px-1.5 py-0.5 text-[9.5px] font-bold uppercase',
                          isSource ? 'bg-emerald-100 text-emerald-700' : 'bg-amber-100 text-amber-700',
                        )}>
                          {isSource ? 'source' : 'sink'}
                        </span>
                        <StatusBadge status={e.status} />
                      </button>
                    )
                  })}
                </div>
              )}
            </Panel>
          </div>
        )}

        {tab === 'Schema' && <SchemaTab />}

        {tab === '연결 검사' && (
          <ConnectionCheckTab node={node} edges={pipelines} />
        )}

        {tab === 'Pipelines' && (
          <Panel title="Pipelines using this database">
            <div className="divide-y divide-gray-50">
              {dbPipelines(node.id, app.edges).map((e) => (
                <button
                  key={e.id}
                  onClick={() => app.openPipeline(e.id)}
                  className="flex w-full items-center gap-3 px-4 py-3 text-left hover:bg-gray-50"
                >
                  <Icon name="route" size={15} className="text-gray-400" />
                  <span className="text-[13px] font-medium text-gray-800">{pipelineLabel(e)}</span>
                  <span
                    className={cn(
                      'rounded px-1.5 py-0.5 text-[9.5px] font-bold uppercase',
                      e.source === node.id ? 'bg-sky-100 text-sky-700' : 'bg-violet-100 text-violet-700',
                    )}
                  >
                    {e.source === node.id ? 'source' : 'sink'}
                  </span>
                  <div className="flex-1" />
                  <StatusBadge status={e.status} />
                </button>
              ))}
              {dbPipelines(node.id, app.edges).length === 0 && (
                <div className="px-4 py-10 text-center text-sm text-gray-400">
                  This database is not used by any pipeline yet.
                </div>
              )}
            </div>
          </Panel>
        )}
      </div>
    </div>
  )
}


/* ---- 친화적 Capability Check 라벨 매핑 ---- */
const FRIENDLY_LABELS: Record<string, { label: string; desc: string }> = {
  'wal_level = logical':       { label: '변경 감지 활성화',       desc: 'DB가 데이터 변경 이력을 기록하도록 설정되어 있습니다.' },
  'REPLICATION privilege':     { label: '연결 권한',               desc: 'Bifrost가 DB에 접근할 수 있는 권한이 있습니다.' },
  'Replication slot capacity': { label: '연결 슬롯 여유 공간',     desc: '파이프라인을 추가로 연결할 수 있는 슬롯이 있습니다.' },
  'REPLICA IDENTITY FULL':     { label: '변경 데이터 완전성',      desc: '일부 테이블이 변경 전/후 데이터를 완전히 기록하지 않을 수 있습니다.' },
  'binlog_format = ROW':       { label: '변경 감지 방식',          desc: '행 단위로 변경이 감지됩니다. 정확한 복제에 필요한 설정입니다.' },
  'REPLICATION SLAVE privilege':{ label: '연결 권한',              desc: 'Bifrost가 DB 변경 이력을 읽을 수 있는 권한이 있습니다.' },
  'GTID mode':                  { label: '안전한 장애 복구',       desc: '서버 장애 시 파이프라인이 올바른 위치에서 재시작됩니다.' },
  'binlog retention':           { label: '변경 이력 보존 기간',    desc: '변경 이력이 7일간 보존됩니다.' },
}

/* ---- Sink 연결 검사 항목 ---- */
const SINK_CHECKS: CapabilityCheck[] = [
  { label: '연결 확인', state: 'pass', detail: 'Bifrost가 Sink DB에 성공적으로 접속할 수 있습니다.' },
  { label: '쓰기 권한', state: 'pass', detail: 'Sink DB에 INSERT/UPDATE/DELETE 권한이 있습니다.' },
  { label: '스키마 호환성', state: 'warn', detail: 'Sink 테이블이 Source 스키마와 일부 다를 수 있습니다. 테이블 매핑을 확인하세요.' },
  { label: '트랜잭션 격리 수준', state: 'pass', detail: 'READ COMMITTED 이상의 격리 수준이 설정되어 있습니다.' },
]

function CheckSection({
  title,
  subtitle,
  checks,
}: {
  title: string
  subtitle: string
  checks: CapabilityCheck[]
}) {
  const allPass = checks.every((c) => c.state === 'pass')
  const warnCount = checks.filter((c) => c.state === 'warn').length
  const failCount = checks.filter((c) => c.state === 'fail').length
  const [showDetail, setShowDetail] = useState(false)

  return (
    <div className="space-y-2">
      <div
        className={cn(
          'flex items-center gap-3 rounded-xl border px-5 py-4',
          allPass
            ? 'border-emerald-200 bg-emerald-50'
            : warnCount > 0
              ? 'border-amber-200 bg-amber-50'
              : 'border-rose-200 bg-rose-50',
        )}
      >
        <Icon
          name={allPass ? 'check' : 'alert'}
          size={20}
          strokeWidth={allPass ? 3 : 2}
          className={allPass ? 'text-emerald-600' : warnCount > 0 ? 'text-amber-600' : 'text-rose-600'}
        />
        <div className="flex-1">
          <div className={cn('text-[14px] font-semibold', allPass ? 'text-emerald-800' : warnCount > 0 ? 'text-amber-800' : 'text-rose-800')}>
            {title}
          </div>
          <div className={cn('text-[12px]', allPass ? 'text-emerald-600' : 'text-amber-600')}>
            {allPass
              ? subtitle
              : failCount > 0
                ? `${failCount}개 항목 해결 필요`
                : `${warnCount}개 항목 확인 권장`}
          </div>
        </div>
        <button
          onClick={() => setShowDetail((v) => !v)}
          className="flex items-center gap-1 text-[12px] font-medium text-gray-500 hover:text-gray-700"
        >
          {showDetail ? '항목 숨기기' : '항목 보기'}
          <Icon name={showDetail ? 'chevron-up' : 'chevron-down'} size={13} />
        </button>
      </div>

      {showDetail && (
        <div className="rounded-xl border border-gray-200 bg-white divide-y divide-gray-50 overflow-hidden">
          {checks.map((c) => {
            const friendly = FRIENDLY_LABELS[c.label] ?? { label: c.label, desc: c.detail }
            return (
              <div key={c.label} className="flex gap-3 px-4 py-3">
                <Icon
                  name={c.state === 'pass' ? 'check' : c.state === 'warn' ? 'alert' : 'x'}
                  size={14}
                  strokeWidth={c.state === 'pass' ? 3 : 2}
                  className={cn(
                    'mt-0.5 shrink-0',
                    c.state === 'pass' ? 'text-emerald-500' : c.state === 'warn' ? 'text-amber-500' : 'text-rose-500',
                  )}
                />
                <div className="flex-1">
                  <div className="text-[13px] font-medium text-gray-800">{friendly.label}</div>
                  <div className="text-[12px] text-gray-500">{friendly.desc}</div>
                </div>
                <span className="mt-0.5 shrink-0 font-mono text-[10px] text-gray-300">{c.label}</span>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}

function ConnectionCheckTab({ node, edges }: { node: Node; edges: Edge[] }) {
  const sourceChecks = defaultChecks(node)
  const isSink = edges.some((e) => e.sink === node.id)

  return (
    <div className="space-y-4">
      <CheckSection
        title="Source 연결 검사"
        subtitle="이 DB를 변경 감지(CDC) Source로 사용할 수 있습니다."
        checks={sourceChecks}
      />
      {isSink && (
        <CheckSection
          title="Sink 연결 검사"
          subtitle="이 DB를 동기화 대상(Sink)으로 사용할 수 있습니다."
          checks={SINK_CHECKS}
        />
      )}
      {!isSink && (
        <div className="rounded-xl border border-dashed border-gray-200 px-5 py-4 text-[13px] text-gray-400">
          이 DB가 CDC Sink로 사용되는 파이프라인이 없습니다. Sink로 연결된 파이프라인이 생성되면 Sink 연결 검사가 표시됩니다.
        </div>
      )}
    </div>
  )
}

function SchemaTab() {
  const [open, setOpen] = useState<string | null>('orders')
  return (
    <Panel title="Tables">
      <div className="divide-y divide-gray-50">
        {SCHEMA_TABLES.map((t) => (
          <div key={t.name}>
            <button
              onClick={() => setOpen(open === t.name ? null : t.name)}
              className="flex w-full items-center gap-3 px-4 py-2.5 text-left hover:bg-gray-50"
            >
              <Icon name={open === t.name ? 'chevron-down' : 'chevron-right'} size={14} className="text-gray-400" />
              <Icon name="table" size={15} className="text-gray-400" />
              <span className="font-mono text-[13px] font-medium text-gray-800">{t.name}</span>
              <div className="flex-1" />
              <span className="text-[12px] text-gray-500">{t.rows} rows</span>
              <span className="w-16 text-right text-[12px] text-gray-400">{t.size}</span>
            </button>
            {open === t.name && (
              <div className="bg-gray-50/60 px-4 py-2">
                {t.cols.map((c) => (
                  <div key={c.name} className="flex items-center gap-2 py-1 pl-9 text-[12px]">
                    <span className="font-mono font-medium text-gray-700">{c.name}</span>
                    <span className="font-mono text-gray-400">{c.type}</span>
                    {c.pk && (
                      <span className="rounded bg-violet-50 px-1.5 py-0.5 text-[10px] font-semibold text-violet-700">PK</span>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>
        ))}
      </div>
    </Panel>
  )
}
