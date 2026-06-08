import { useEffect, useMemo, useState } from 'react'
import { Icon } from '../../components/Icon'
import { Gauge, MetricCard, PageHead, Panel, StatusBadge } from '../../components/blocks'
import { TrendChart, CHART_COLORS, ChartLegend } from '../../components/Charts'
import { formatNum, cn } from '../../lib/format'
import {
  api,
  type BrokerInfo,
  type ConnectClusterResponse,
  type KafkaClusterResponse,
  type ThroughputPoint,
} from '../../lib/api'

/* 초당 바이트 → 사람이 읽는 단위(KB/MB/s). */
function fmtRate(bytesPerSec: number | null): string {
  if (bytesPerSec == null) return '—'
  if (bytesPerSec >= 1e6) return `${(bytesPerSec / 1e6).toFixed(1)} MB/s`
  if (bytesPerSec >= 1e3) return `${(bytesPerSec / 1e3).toFixed(0)} KB/s`
  return `${Math.round(bytesPerSec)} B/s`
}
function fmtBytes(bytes: number): string {
  if (bytes >= 1e9) return `${(bytes / 1e9).toFixed(1)} GB`
  if (bytes >= 1e6) return `${(bytes / 1e6).toFixed(0)} MB`
  return `${(bytes / 1e3).toFixed(0)} KB`
}

/* ---------------------------------------------------------------- component */

export function OperatorCluster() {
  const [tab, setTab] = useState('Brokers')
  const tabs = ['Brokers', 'KafkaConnect']
  const [selectedBroker, setSelectedBroker] = useState<BrokerInfo | null>(null)

  const crumbs: { label: string; onClick?: () => void }[] = [{ label: 'Cluster' }]
  if (selectedBroker) {
    crumbs[0].onClick = () => setSelectedBroker(null)
    crumbs.push({ label: `broker-${selectedBroker.id}` })
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

function BrokersTab({ onSelectBroker }: { onSelectBroker: (b: BrokerInfo) => void }) {
  const [data, setData] = useState<KafkaClusterResponse | null>(null)
  const [throughput, setThroughput] = useState<ThroughputPoint[]>([])

  useEffect(() => {
    let cancelled = false
    const load = () => {
      api.clusterKafka().then((d) => { if (!cancelled) setData(d) }).catch(() => {})
      api.clusterThroughput(30).then((t) => { if (!cancelled) setThroughput(t) }).catch(() => {})
    }
    load()
    const timer = setInterval(load, 5000)
    return () => { cancelled = true; clearInterval(timer) }
  }, [])

  const tputData = useMemo(() =>
    throughput.map((p) => ({
      t: p.timestamp,
      produce: Math.round(p.produceRate * 100) / 100,
      consume: Math.round(p.consumeRate * 100) / 100,
    })), [throughput])
  const tputSeries = [
    { key: 'produce', label: 'Produce msg/s', color: CHART_COLORS.amber },
    { key: 'consume', label: 'Consume msg/s', color: CHART_COLORS.brand },
  ]

  if (!data) return <div className="px-2 py-10 text-center text-[13px] text-gray-400">불러오는 중…</div>

  const urp = data.underReplicated
  const controllerLabel = data.controllerId >= 0 ? `broker-${data.controllerId}` : '—'

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-4 gap-4">
        <MetricCard label="Active controller" value={controllerLabel} icon="cpu" />
        <MetricCard label="Under-replicated" value={urp} icon="alert" tone={urp ? 'warn' : 'good'} />
        <MetricCard label="Offline partitions" value={data.offlinePartitions} tone={data.offlinePartitions ? 'bad' : 'good'} />
        <MetricCard label="Total partitions" value={data.totalPartitions} icon="server" />
      </div>

      <div className="grid grid-cols-3 gap-4">
        {data.brokers.map((b) => (
          <button
            key={b.id}
            onClick={() => onSelectBroker(b)}
            className="rounded-xl border border-gray-200 bg-white text-left transition-all hover:border-brand-300 hover:shadow-md"
          >
            <Panel>
              <div className="px-4 py-3">
                <div className="flex items-center gap-2">
                  <Icon name="server" size={16} className="text-gray-400" />
                  <span className="text-[13.5px] font-semibold text-gray-900">broker-{b.id}</span>
                  {b.controller && (
                    <span className="rounded bg-brand-50 px-1.5 py-0.5 text-[9.5px] font-bold uppercase text-brand-700">controller</span>
                  )}
                  <div className="ml-auto flex items-center gap-1.5">
                    <StatusBadge status={b.status} />
                    <Icon name="chevron-right" size={13} className="text-gray-400" />
                  </div>
                </div>
                <div className="mt-1 text-[11.5px] text-gray-400">{b.leaderPartitions} leader partitions · {fmtBytes(b.logDirBytes)}</div>
                <div className="mt-3 space-y-2.5">
                  <Gauge label="CPU" value={b.cpuPct ?? 0} />
                  <Gauge label="Disk" value={b.diskUsedPct ?? 0} />
                </div>
                <div className="mt-3 flex gap-4 border-t border-gray-100 pt-2.5 text-[11.5px]">
                  <span className="text-gray-500">Net in <span className="font-medium text-gray-800">{fmtRate(b.netInBytesPerSec)}</span></span>
                  <span className="text-gray-500">Net out <span className="font-medium text-gray-800">{fmtRate(b.netOutBytesPerSec)}</span></span>
                </div>
              </div>
            </Panel>
          </button>
        ))}
      </div>

      <Panel title="Cluster throughput" right={<ChartLegend series={tputSeries} />}>
        <div className="px-3 py-3">
          <TrendChart data={tputData} series={tputSeries} height={190} timeAxis />
        </div>
      </Panel>
    </div>
  )
}

/* ---------------------------------------------------------------- Broker detail */

function BrokerDetail({ broker }: { broker: BrokerInfo }) {
  return (
    <div className="space-y-4">
      <div className="grid grid-cols-4 gap-4">
        <MetricCard label="Leader partitions" value={broker.leaderPartitions} icon="server" />
        <MetricCard label="CPU" value={broker.cpuPct != null ? `${broker.cpuPct}%` : '—'} tone={(broker.cpuPct ?? 0) > 80 ? 'warn' : 'good'} />
        <MetricCard label="Disk" value={broker.diskUsedPct != null ? `${broker.diskUsedPct}%` : '—'} tone={(broker.diskUsedPct ?? 0) > 80 ? 'warn' : 'good'} />
        <MetricCard label="Log dir" value={fmtBytes(broker.logDirBytes)} icon="server" />
      </div>

      <Panel title="네트워크">
        <div className="grid grid-cols-2 divide-x divide-gray-100">
          <div className="px-6 py-4">
            <div className="text-[11px] uppercase tracking-wide text-gray-400">Inbound</div>
            <div className="mt-1 text-[22px] font-semibold text-gray-900">{fmtRate(broker.netInBytesPerSec)}</div>
          </div>
          <div className="px-6 py-4">
            <div className="text-[11px] uppercase tracking-wide text-gray-400">Outbound</div>
            <div className="mt-1 text-[22px] font-semibold text-gray-900">{fmtRate(broker.netOutBytesPerSec)}</div>
          </div>
        </div>
      </Panel>

      <Panel title="브로커 정보">
        <div className="divide-y divide-gray-50 px-4">
          {[
            ['Broker ID', String(broker.id)],
            ['Host', broker.host],
            ['Port', String(broker.port)],
            ['Controller', broker.controller ? 'yes' : 'no'],
            ['Status', broker.status],
          ].map(([k, v]) => (
            <div key={k} className="flex items-center py-2.5 text-[12px]">
              <span className="w-40 shrink-0 text-gray-500">{k}</span>
              <span className="font-mono text-gray-700">{v}</span>
            </div>
          ))}
        </div>
      </Panel>
    </div>
  )
}

/* ---------------------------------------------------------------- KafkaConnect tab */

function KafkaConnectTab() {
  const [data, setData] = useState<ConnectClusterResponse | null>(null)

  useEffect(() => {
    let cancelled = false
    const load = () => { api.clusterConnect().then((d) => { if (!cancelled) setData(d) }).catch(() => {}) }
    load()
    const timer = setInterval(load, 5000)
    return () => { cancelled = true; clearInterval(timer) }
  }, [])

  if (!data) return <div className="px-2 py-10 text-center text-[13px] text-gray-400">불러오는 중…</div>

  const running = data.workers.filter((w) => w.state === 'Running').length
  const notRunning = data.workers.length - running
  const totalTasks = data.connectors.reduce((s, c) => s + c.tasks, 0)

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-4 gap-4">
        <MetricCard label="Workers" value={data.workers.length} icon="server" />
        <MetricCard label="Running" value={running} tone="good" />
        <MetricCard label="Not running" value={notRunning} tone={notRunning ? 'warn' : 'good'} />
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
              <th className="px-4 py-2 font-semibold">GC (누적)</th>
              <th className="px-4 py-2 font-semibold">Version</th>
            </tr>
          </thead>
          <tbody>
            {data.workers.map((w) => {
              const heapPct = w.heapUsedBytes && w.heapMaxBytes ? w.heapUsedBytes / w.heapMaxBytes : 0
              const heapBar = heapPct >= 0.95 ? 'bg-rose-400' : heapPct >= 0.85 ? 'bg-amber-400' : 'bg-emerald-400'
              return (
                <tr key={w.name} className="border-b border-gray-50">
                  <td className="px-4 py-3">
                    <div className="font-mono text-[12px] font-medium text-gray-800">{w.name}</div>
                    <div className="font-mono text-[10.5px] text-gray-400">{w.host ?? '—'}</div>
                  </td>
                  <td className="px-4 py-3">
                    <span className={cn('rounded px-1.5 py-0.5 text-[10px] font-bold uppercase',
                      w.state === 'Running' ? 'bg-emerald-50 text-emerald-700' : 'bg-amber-50 text-amber-700')}>
                      {w.state}
                    </span>
                  </td>
                  <td className="px-4 py-3 w-40">
                    {w.heapUsedBytes != null && w.heapMaxBytes != null ? (
                      <>
                        <div className="mb-1 flex items-center justify-between">
                          <span className="text-[11px] font-semibold tabular-nums text-gray-700">
                            {fmtBytes(w.heapUsedBytes)} / {fmtBytes(w.heapMaxBytes)}
                          </span>
                          <span className="text-[10px] text-gray-500">{Math.round(heapPct * 100)}%</span>
                        </div>
                        <div className="h-1.5 w-full overflow-hidden rounded-full bg-gray-200">
                          <div className={cn('h-full rounded-full', heapBar)} style={{ width: `${heapPct * 100}%` }} />
                        </div>
                      </>
                    ) : <span className="text-gray-400">—</span>}
                  </td>
                  <td className="px-4 py-3 text-gray-700 tabular-nums">{w.cpuPct != null ? `${w.cpuPct}%` : '—'}</td>
                  <td className="px-4 py-3 font-mono text-[12px] text-gray-600">{w.gcSeconds != null ? `${w.gcSeconds}s` : '—'}</td>
                  <td className="px-4 py-3 font-mono text-[11.5px] text-gray-400">{w.version ?? '—'}</td>
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
              <th className="px-4 py-2 font-semibold">Pipeline</th>
              <th className="px-4 py-2 font-semibold">Tasks</th>
            </tr>
          </thead>
          <tbody>
            {data.connectors.map((c) => (
              <tr key={c.name} className="border-b border-gray-50">
                <td className="px-4 py-2.5 font-mono font-medium text-gray-800">{c.name}</td>
                <td className="px-4 py-2.5">
                  <span className={cn('rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase',
                    c.kind === 'SOURCE' ? 'bg-sky-50 text-sky-700' : 'bg-violet-50 text-violet-700')}>{c.kind}</span>
                </td>
                <td className="px-4 py-2.5">
                  <span className={cn('rounded px-1.5 py-0.5 text-[10px] font-bold uppercase',
                    c.status === 'RUNNING' ? 'bg-emerald-50 text-emerald-700'
                      : c.status === 'PAUSED' ? 'bg-gray-100 text-gray-600' : 'bg-rose-50 text-rose-700')}>{c.status}</span>
                </td>
                <td className="px-4 py-2.5 text-gray-500 text-[11.5px]">{c.pipeline}</td>
                <td className="px-4 py-2.5 text-gray-600">{c.tasks}</td>
              </tr>
            ))}
            {data.connectors.length === 0 && (
              <tr><td colSpan={5} className="px-4 py-6 text-center text-gray-400">커넥터 없음</td></tr>
            )}
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
            {data.plugins.map((p) => (
              <tr key={p.className} className="border-b border-gray-50">
                <td className="px-4 py-2.5 font-mono text-[11px] text-gray-700">{p.className}</td>
                <td className="px-4 py-2.5">
                  <span className={cn('rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase',
                    p.type === 'source' ? 'bg-sky-50 text-sky-700' : 'bg-violet-50 text-violet-700')}>{p.type}</span>
                </td>
                <td className="px-4 py-2.5 font-mono text-[11.5px] text-gray-500">{p.version}</td>
              </tr>
            ))}
            {data.plugins.length === 0 && (
              <tr><td colSpan={3} className="px-4 py-6 text-center text-gray-400">플러그인 정보 없음</td></tr>
            )}
          </tbody>
        </table>
      </Panel>

      <Panel title="Connect 클러스터 설정">
        <div className="divide-y divide-gray-50 px-4">
          {Object.entries(data.config).map(([key, value]) => (
            <div key={key} className="flex items-center py-2.5 text-[12px]">
              <span className="w-64 shrink-0 font-mono text-gray-500">{key}</span>
              <span className="font-mono text-gray-700 break-all">{value}</span>
            </div>
          ))}
        </div>
      </Panel>
    </div>
  )
}
