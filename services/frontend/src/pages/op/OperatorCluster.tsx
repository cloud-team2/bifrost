import { useMemo, useState } from 'react'
import { Icon } from '../../components/Icon'
import { Gauge, MetricCard, PageHead, Panel, StatusBadge } from '../../components/blocks'
import { TrendChart, CHART_COLORS, ChartLegend } from '../../components/Charts'
import { CLUSTER } from '../../store/AppStore'
import { genSeries } from '../../data/helpers'
import { formatNum, cn } from '../../lib/format'
import type { Broker } from '../../data/types'

/* ---------------------------------------------------------------- component */

export function OperatorCluster() {
  const [tab, setTab] = useState('Brokers')
  const tabs = ['Brokers', 'KafkaConnect']

  const [selectedBroker, setSelectedBroker] = useState<Broker | null>(null)

  function back() {
    setSelectedBroker(null)
  }

  const crumbs: { label: string; onClick?: () => void }[] = [{ label: 'Cluster' }]
  if (selectedBroker) {
    crumbs[0].onClick = back
    crumbs.push({ label: selectedBroker.name })
  }

  const showBreadcrumb = crumbs.length > 1

  return (
    <div className="px-6 py-5">
      {showBreadcrumb ? (
        <div className="mb-4 flex items-center gap-1.5 text-[12.5px]">
          {crumbs.map((c, i) => (
            <span key={i} className="flex items-center gap-1.5">
              {i > 0 && <Icon name="chevron-right" size={13} className="text-gray-300" />}
              {c.onClick ? (
                <button onClick={c.onClick} className="font-medium text-brand-600 hover:underline">{c.label}</button>
              ) : (
                <span className="font-medium text-gray-700">{c.label}</span>
              )}
            </span>
          ))}
        </div>
      ) : (
        <PageHead title="Cluster" />
      )}

      {selectedBroker ? (
        <BrokerDetail broker={selectedBroker} />
      ) : (
        <>
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
            {tab === 'Brokers' && <BrokersTab onSelectBroker={setSelectedBroker} />}
            {tab === 'KafkaConnect' && <KafkaConnectTab />}
          </div>
        </>
      )}
    </div>
  )
}

/* ---------------------------------------------------------------- Brokers tab */

function BrokersTab({ onSelectBroker }: { onSelectBroker: (b: Broker) => void }) {
  const brokers = CLUSTER.BROKERS
  const totalParts = CLUSTER.CLUSTER_TOPICS.reduce((s, t) => s + t.partitions, 0)
  const urp = CLUSTER.CLUSTER_TOPICS.filter((t) => t.replicaPct < 100).length
  const throughput = useMemo(() => genSeries([{ key: 'produce', base: 7200, vary: 1400 }, { key: 'consume', base: 7000, vary: 1400 }]), [])
  const disk = useMemo(() => genSeries([{ key: 'disk', base: 54, vary: 2, drift: 0.5 }], 30), [])
  const tputSeries = [
    { key: 'produce', label: 'Produce msg/s', color: CHART_COLORS.amber },
    { key: 'consume', label: 'Consume msg/s', color: CHART_COLORS.brand },
  ]

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-4 gap-4">
        <MetricCard label="Active controller" value="broker-2" icon="cpu" />
        <MetricCard label="Under-replicated" value={urp} icon="alert" tone={urp ? 'warn' : 'good'} />
        <MetricCard label="Offline partitions" value={0} tone="good" />
        <MetricCard label="Total partitions" value={totalParts} icon="server" />
      </div>

      <div className="grid grid-cols-3 gap-4">
        {brokers.map((b) => (
          <button
            key={b.id}
            onClick={() => onSelectBroker(b)}
            className="rounded-xl border border-gray-200 bg-white text-left transition-all hover:border-brand-300 hover:shadow-md"
          >
            <Panel>
              <div className="px-4 py-3">
                <div className="flex items-center gap-2">
                  <Icon name="server" size={16} className="text-gray-400" />
                  <span className="text-[13.5px] font-semibold text-gray-900">{b.name}</span>
                  <div className="ml-auto flex items-center gap-1.5">
                    <StatusBadge status={b.status} />
                    <Icon name="chevron-right" size={13} className="text-gray-400" />
                  </div>
                </div>
                <div className="mt-1 text-[11.5px] text-gray-400">{b.leaderPartitions} leader partitions</div>
                <div className="mt-3 space-y-2.5">
                  <Gauge label="CPU" value={b.cpu} />
                  <Gauge label="Disk" value={b.disk} />
                </div>
                <div className="mt-3 flex gap-4 border-t border-gray-100 pt-2.5 text-[11.5px]">
                  <span className="text-gray-500">
                    Net in <span className="font-medium text-gray-800">{b.netIn} MB/s</span>
                  </span>
                  <span className="text-gray-500">
                    Net out <span className="font-medium text-gray-800">{b.netOut} MB/s</span>
                  </span>
                </div>
              </div>
            </Panel>
          </button>
        ))}
      </div>

      <div className="grid grid-cols-2 gap-4">
        <Panel title="Cluster throughput" right={<ChartLegend series={tputSeries} />}>
          <div className="px-3 py-3">
            <TrendChart data={throughput} series={tputSeries} height={190} />
          </div>
        </Panel>
        <Panel title="Disk usage forecast (%)">
          <div className="px-3 py-3">
            <TrendChart
              data={disk}
              type="area"
              height={190}
              series={[{ key: 'disk', label: 'Disk %', color: CHART_COLORS.violet }]}
              refLine={{ y: 80, label: 'alert 80%' }}
            />
          </div>
        </Panel>
      </div>
    </div>
  )
}

/* ---------------------------------------------------------------- Broker detail drill-down */

function BrokerDetail({ broker }: { broker: Broker }) {
  return (
    <div className="space-y-4">
      <div className="grid grid-cols-4 gap-4">
        <MetricCard label="Leader partitions" value={broker.leaderPartitions} icon="server" />
        <MetricCard label="CPU" value={`${broker.cpu}%`} tone={broker.cpu > 70 ? 'warn' : 'good'} />
        <MetricCard label="Disk" value={`${broker.disk}%`} tone={broker.disk > 75 ? 'warn' : 'good'} />
        <MetricCard label="Status" value={broker.status} tone={broker.status === 'healthy' ? 'good' : broker.status === 'warning' ? 'warn' : 'bad'} />
      </div>

      <Panel title="네트워크">
        <div className="grid grid-cols-2 divide-x divide-gray-100">
          <div className="px-6 py-4">
            <div className="text-[11px] uppercase tracking-wide text-gray-400">Inbound</div>
            <div className="mt-1 text-[22px] font-semibold text-gray-900">{broker.netIn} <span className="text-[14px] font-normal text-gray-500">MB/s</span></div>
          </div>
          <div className="px-6 py-4">
            <div className="text-[11px] uppercase tracking-wide text-gray-400">Outbound</div>
            <div className="mt-1 text-[22px] font-semibold text-gray-900">{broker.netOut} <span className="text-[14px] font-normal text-gray-500">MB/s</span></div>
          </div>
        </div>
      </Panel>
    </div>
  )
}

/* ---------------------------------------------------------------- KafkaConnect tab */

const KAFKA_CONNECT_WORKERS = [
  { id: 'connect-worker-0', host: '10.20.1.10:8083', state: 'RUNNING',  version: '3.6.1', connectors: 4, tasks: 7, heapUsedMB: 412, heapMaxMB: 768, cpuPct: 8,  gcTimeSec: 0.3 },
  { id: 'connect-worker-1', host: '10.20.1.11:8083', state: 'RUNNING',  version: '3.6.1', connectors: 4, tasks: 7, heapUsedMB: 390, heapMaxMB: 768, cpuPct: 7,  gcTimeSec: 0.2 },
  { id: 'connect-worker-2', host: '10.20.1.12:8083', state: 'WARNING',  version: '3.6.0', connectors: 2, tasks: 3, heapUsedMB: 664, heapMaxMB: 768, cpuPct: 72, gcTimeSec: 6.1 },
]

const KAFKA_CONNECT_PLUGINS = [
  { name: 'io.debezium.connector.mysql.MySqlConnector', version: '2.5.0', type: 'Source' },
  { name: 'io.debezium.connector.postgresql.PostgresConnector', version: '2.5.0', type: 'Source' },
  { name: 'io.confluent.connect.jdbc.JdbcSinkConnector', version: '10.7.3', type: 'Sink' },
  { name: 'org.apache.kafka.connect.file.FileStreamSourceConnector', version: '3.6.1', type: 'Source' },
]

function KafkaConnectTab() {
  const running = KAFKA_CONNECT_WORKERS.filter((w) => w.state === 'RUNNING').length
  const warning = KAFKA_CONNECT_WORKERS.filter((w) => w.state === 'WARNING').length
  const totalTasks = KAFKA_CONNECT_WORKERS.reduce((s, w) => s + w.tasks, 0)

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-4 gap-4">
        <MetricCard label="Workers" value={KAFKA_CONNECT_WORKERS.length} icon="server" />
        <MetricCard label="Running" value={running} tone="good" />
        <MetricCard label="Warning" value={warning} tone={warning ? 'warn' : 'good'} />
        <MetricCard label="Total tasks" value={totalTasks} />
      </div>

      <Panel title="Worker nodes">
        <table className="w-full text-[12.5px]">
          <thead>
            <tr className="border-b border-gray-100 text-left text-[11px] uppercase tracking-wide text-gray-400">
              <th className="px-4 py-2 font-semibold">Worker</th>
              <th className="px-4 py-2 font-semibold">State</th>
              <th className="px-4 py-2 font-semibold">JVM Heap</th>
              <th className="px-4 py-2 font-semibold">CPU</th>
              <th className="px-4 py-2 font-semibold">GC / 30s</th>
              <th className="px-4 py-2 font-semibold">Tasks</th>
              <th className="px-4 py-2 font-semibold">Version</th>
            </tr>
          </thead>
          <tbody>
            {KAFKA_CONNECT_WORKERS.map((w) => {
              const heapPct = w.heapUsedMB / w.heapMaxMB
              const heapWarn = heapPct >= 0.95 ? 'text-rose-600' : heapPct >= 0.85 ? 'text-amber-600' : 'text-gray-700'
              const heapBar = heapPct >= 0.95 ? 'bg-rose-400' : heapPct >= 0.85 ? 'bg-amber-400' : 'bg-emerald-400'
              const cpuWarn = w.cpuPct >= 70 ? 'text-rose-600' : w.cpuPct >= 40 ? 'text-amber-600' : 'text-gray-700'
              const cpuBar = w.cpuPct >= 70 ? 'bg-rose-400' : w.cpuPct >= 40 ? 'bg-amber-400' : 'bg-emerald-400'
              const gcWarn = w.gcTimeSec > 5 ? 'text-rose-600' : w.gcTimeSec > 2 ? 'text-amber-600' : 'text-gray-700'
              return (
                <tr key={w.id} className="border-b border-gray-50">
                  <td className="px-4 py-3">
                    <div className="font-mono text-[12px] font-medium text-gray-800">{w.id}</div>
                    <div className="font-mono text-[10.5px] text-gray-400">{w.host}</div>
                  </td>
                  <td className="px-4 py-3">
                    <span className={cn('rounded px-1.5 py-0.5 text-[10px] font-bold uppercase',
                      w.state === 'RUNNING' ? 'bg-emerald-50 text-emerald-700' : 'bg-amber-50 text-amber-700')}>
                      {w.state}
                    </span>
                  </td>
                  <td className="px-4 py-3 w-36">
                    <div className="flex items-center justify-between mb-1">
                      <span className={cn('text-[11px] font-semibold tabular-nums', heapWarn)}>
                        {w.heapUsedMB} / {w.heapMaxMB} MB
                      </span>
                      <span className={cn('text-[10px]', heapWarn)}>{Math.round(heapPct * 100)}%</span>
                    </div>
                    <div className="h-1.5 w-full overflow-hidden rounded-full bg-gray-200">
                      <div className={cn('h-full rounded-full', heapBar)} style={{ width: `${heapPct * 100}%` }} />
                    </div>
                  </td>
                  <td className="px-4 py-3 w-28">
                    <div className="flex items-center justify-between mb-1">
                      <span className={cn('text-[11px] font-semibold tabular-nums', cpuWarn)}>{w.cpuPct}%</span>
                    </div>
                    <div className="h-1.5 w-full overflow-hidden rounded-full bg-gray-200">
                      <div className={cn('h-full rounded-full', cpuBar)} style={{ width: `${w.cpuPct}%` }} />
                    </div>
                  </td>
                  <td className={cn('px-4 py-3 font-mono text-[12px] font-semibold', gcWarn)}>{w.gcTimeSec}s</td>
                  <td className="px-4 py-3 text-gray-600">{w.tasks}</td>
                  <td className="px-4 py-3 font-mono text-[11.5px] text-gray-400">{w.version}</td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </Panel>

      <Panel title="Connectors">
        <table className="w-full text-[12.5px]">
          <thead>
            <tr className="border-b border-gray-100 text-left text-[11px] uppercase tracking-wide text-gray-400">
              <th className="px-4 py-2 font-semibold">Name</th>
              <th className="px-4 py-2 font-semibold">Kind</th>
              <th className="px-4 py-2 font-semibold">Status</th>
              <th className="px-4 py-2 font-semibold">Project</th>
              <th className="px-4 py-2 font-semibold">Tasks</th>
              <th className="px-4 py-2 font-semibold">Records/s</th>
            </tr>
          </thead>
          <tbody>
            {CLUSTER.CLUSTER_CONNECTORS.map((c) => (
              <tr key={c.name} className="border-b border-gray-50">
                <td className="px-4 py-2.5 font-mono font-medium text-gray-800">{c.name}</td>
                <td className="px-4 py-2.5">
                  <span className={cn(
                    'rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase',
                    c.kind === 'Source' ? 'bg-sky-50 text-sky-700' : 'bg-violet-50 text-violet-700',
                  )}>{c.kind}</span>
                </td>
                <td className="px-4 py-2.5"><StatusBadge status={c.status} /></td>
                <td className="px-4 py-2.5 text-gray-500 text-[11.5px]">{c.project}</td>
                <td className="px-4 py-2.5 text-gray-600">{c.tasks}</td>
                <td className="px-4 py-2.5 text-gray-600">{formatNum(c.recordsPerSec)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Panel>

      <Panel title="설치된 플러그인">
        <table className="w-full text-[12.5px]">
          <thead>
            <tr className="border-b border-gray-100 text-left text-[11px] uppercase tracking-wide text-gray-400">
              <th className="px-4 py-2 font-semibold">Class</th>
              <th className="px-4 py-2 font-semibold">Type</th>
              <th className="px-4 py-2 font-semibold">Version</th>
            </tr>
          </thead>
          <tbody>
            {KAFKA_CONNECT_PLUGINS.map((p) => (
              <tr key={p.name} className="border-b border-gray-50">
                <td className="px-4 py-2.5 font-mono text-[11px] text-gray-700">{p.name}</td>
                <td className="px-4 py-2.5">
                  <span
                    className={cn(
                      'rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase',
                      p.type === 'Source' ? 'bg-sky-50 text-sky-700' : 'bg-violet-50 text-violet-700',
                    )}
                  >
                    {p.type}
                  </span>
                </td>
                <td className="px-4 py-2.5 font-mono text-[11.5px] text-gray-500">{p.version}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Panel>

      <Panel title="Connect 클러스터 설정">
        <div className="divide-y divide-gray-50 px-4">
          {[
            { key: 'group.id', value: 'bifrost-connect-cluster' },
            { key: 'config.storage.topic', value: '_connect-configs' },
            { key: 'offset.storage.topic', value: '_connect-offsets' },
            { key: 'status.storage.topic', value: '_connect-status' },
            { key: 'key.converter', value: 'org.apache.kafka.connect.json.JsonConverter' },
            { key: 'value.converter', value: 'io.confluent.connect.avro.AvroConverter' },
          ].map(({ key, value }) => (
            <div key={key} className="flex items-center py-2.5 text-[12px]">
              <span className="w-64 shrink-0 font-mono text-gray-500">{key}</span>
              <span className="font-mono text-gray-700">{value}</span>
            </div>
          ))}
        </div>
      </Panel>
    </div>
  )
}
