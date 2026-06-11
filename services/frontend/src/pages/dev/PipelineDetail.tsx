import { Fragment, useEffect, useMemo, useRef, useState } from 'react'
import { Bar, BarChart, CartesianGrid, Cell, Tooltip, XAxis, YAxis } from 'recharts'
import { Icon } from '../../components/Icon'
import { MetricCard, Panel, StatusBadge } from '../../components/blocks'
import { TrendChart, CHART_COLORS, ResponsiveChart } from '../../components/Charts'
import { TechIcon, nodeKind } from '../../components/TechIcon'
import { useToast } from '../../components/Toast'
import { useApp } from '../../store/AppStore'
import { pipelineLabel } from '../../data/helpers'
import { buildConsumerSnippets, escapeSnippetValue } from '../../lib/pipelineSnippets'
import type { Edge, Node } from '../../data/types'
import {
  api,
  type ConnectionGuideResponse,
  type ConnectorInfo,
  type ConsumerGroupInfo,
  type EventDistPoint,
  type KafkaMessageRecord,
  type MetricPoint,
  type PipelineMetricsResponse,
  type SyncStatusResponse,
  type TableMappingResponse,
  type ThroughputPoint,
  type TopicInfoResponse,
  type TraceSummaryResponse,
} from '../../lib/api'
import { TraceWaterfall } from '../../components/TraceWaterfall'
import { cn, formatNum } from '../../lib/format'

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
  // (#266) Sync 내용을 Overview로 이동, Topic & Partition은 별도 'Topic' 탭으로 분리.
  const tabs = isEda
    ? ['Overview', 'Consumers', 'Connector', 'Messages', 'Connection Guide', 'Tracing']
    : ['Overview', 'Topic', 'Connector', 'Messages', 'Table Mapping', 'Tracing']

  const [tab, setTab] = useState(tabs[0])

  // (#267) 탭별 에러 위치 표시용 — 커넥터 상태를 폴링해 FAILED/lastError를 감지(복구 시 자동 해제).
  const wsId = app.currentProject?.id
  const [connectors, setConnectors] = useState<ConnectorInfo[] | null>(null)
  useEffect(() => {
    if (!wsId || !edge) return
    let cancelled = false
    const load = () => api.listPipelineConnectors(wsId, edge.id)
      .then((cs) => { if (!cancelled) setConnectors(cs) })
      .catch(() => { /* 일시적 조회 실패는 점 표시에 영향 주지 않음 */ })
    load()
    const timer = setInterval(load, 5000)
    return () => { cancelled = true; clearInterval(timer) }
  }, [wsId, edge?.id])

  if (!edge) return <div className="px-6 py-10 text-sm text-gray-500">Pipeline not found.</div>
  const source    = app.nodes.find((n) => n.id === edge.source)!
  const sink      = edge.sink ? app.nodes.find((n) => n.id === edge.sink) ?? null : null

  // (#267) 원인 attribution → 탭 매핑: DB(연결 끊김/차단) → Overview, 커넥터 FAILED/에러 → Connector.
  const connectorErr = !!connectors?.some((c) => c.state === 'FAILED' || (c.lastError != null && c.lastError !== ''))
  const dbErr        = source.status === 'error' || sink?.status === 'error'
  const tabErrors: Record<string, boolean> = { Overview: dbErr, Connector: connectorErr }

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
            {tabErrors[t] && (
              <span title="이 영역에 에러가 있습니다"
                className="ml-1.5 inline-block h-1.5 w-1.5 rounded-full bg-rose-500 align-middle" />
            )}
          </button>
        ))}
      </div>

      <div className="mt-4">
        {/* (#266) Overview = 동기화 뷰(CDC) / 토픽(EDA, sink 없음) · Topic = 토픽&파티션 */}
        {tab === 'Overview'          && (isEda ? <TopicTab edge={edge} /> : <SyncTab edge={edge} />)}
        {tab === 'Topic'             && <TopicTab edge={edge} />}
        {tab === 'Consumers'         && <ConsumersTab edge={edge} />}
        {tab === 'Connector'         && <ConnectorTab edge={edge} />}
        {tab === 'Messages'          && <MessagesTab edge={edge} />}
        {tab === 'Connection Guide'  && <GuideTab edge={edge} />}
        {tab === 'Table Mapping'     && <MappingTab edge={edge} />}
        {tab === 'Tracing'           && <TraceTab edge={edge} />}
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

/* 시계열 차트 시간 범위 선택기(Prometheus 스타일). 짧은 창일수록 백엔드 step이 촘촘해진다. */
const RANGE_OPTIONS: { label: string; minutes: number }[] = [
  { label: '5m',  minutes: 5 },
  { label: '15m', minutes: 15 },
  { label: '30m', minutes: 30 },
  { label: '1h',  minutes: 60 },
  { label: '3h',  minutes: 180 },
]

function RangeSelector({ value, onChange }: { value: number; onChange: (m: number) => void }) {
  return (
    <div className="flex items-center gap-0.5 rounded-md border border-gray-200 bg-gray-50 p-0.5">
      {RANGE_OPTIONS.map((o) => (
        <button key={o.minutes} onClick={() => onChange(o.minutes)}
          className={cn('rounded px-2 py-0.5 text-[11px] font-medium transition-colors',
            value === o.minutes ? 'bg-white text-gray-900 shadow-sm' : 'text-gray-500 hover:text-gray-700')}>
          {o.label}
        </button>
      ))}
    </div>
  )
}

/* ---------------------------------------------------------------- Topic tab (토픽 & 파티션) */
/* (#266) 기존 Overview에서 Topic & Partition만 분리한 탭. 처리량·메트릭 카드는 폐기(API는 유지). */

function TopicTab({ edge }: { edge: Edge }) {
  const app = useApp()
  const wsId = app.currentProject?.id

  const [topicInfo, setTopicInfo] = useState<TopicInfoResponse | null>(null)

  useEffect(() => {
    if (!wsId) return
    let cancelled = false
    const load = () => {
      api.pipelineTopicInfo(wsId, edge.id).then((t) => { if (!cancelled) setTopicInfo(t) }).catch(() => {})
    }
    load()
    const timer = setInterval(load, 5000)
    return () => { cancelled = true; clearInterval(timer) }
  }, [wsId, edge.id])

  const partitions = topicInfo?.partitions ?? []
  const retentionMs = topicInfo?.retentionMs ?? -1
  const retention = retentionMs > 0
    ? retentionMs >= 86400000 ? `${Math.round(retentionMs / 86400000)}일` : `${Math.round(retentionMs / 3600000)}시간`
    : '—'
  const isrPct = topicInfo?.isrPct ?? 100
  const isrOk = isrPct >= 100

  // EDA 핵심 지표: Debezium MilliSecondsBehindSource 추이
  const [sourceDelaySeries, setSourceDelaySeries] = useState<MetricPoint[]>([])
  useEffect(() => {
    if (!wsId) return
    let cancelled = false
    const load = () => {
      api.pipelineSourceDelay(wsId, edge.id).then((d) => { if (!cancelled) setSourceDelaySeries(d) }).catch(() => {})
    }
    load()
    const timer = setInterval(load, 10000)
    return () => { cancelled = true; clearInterval(timer) }
  }, [wsId, edge.id])

  const sourceDelayData = sourceDelaySeries.map((p) => ({ t: p.timestamp, delay: p.value >= 0 ? p.value : null }))

  return (
    <div className="space-y-4">

      {/* topic & partitions */}
      <Panel title="Topic & Partitions">
        {/* header row */}
        <div className="flex items-center gap-3 border-b border-gray-100 px-5 py-3.5">
          <span className="font-mono text-[13.5px] font-semibold text-gray-900">{edge.topic}</span>
          <StatusBadge status="active" />
          <div className="ml-auto flex items-center gap-4 text-[12px] text-gray-500">
            <span><span className="font-semibold text-gray-700">{partitions.length}</span> 파티션</span>
            <span className={cn('font-semibold', isrOk ? 'text-emerald-600' : 'text-amber-600')}>
              ISR {isrOk ? '정상' : `${Math.round(isrPct)}%`}
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

      {/* EDA 지표: Debezium MilliSecondsBehindSource(소스 지연) 추이 */}
      <Panel title="Source Delay"
        right={<span className="text-[12px] text-gray-400">Debezium MilliSecondsBehindSource · 최근 2시간</span>}>
        {sourceDelayData.length === 0 ? (
          <div className="px-5 py-8 text-center text-[12.5px] text-gray-400">
            지표 없음 — Prometheus 미연동이거나 파이프라인이 아직 활성화되지 않았습니다
          </div>
        ) : (
          <div className="px-5 pb-4 pt-2">
            <TrendChart
              data={sourceDelayData}
              series={[{ key: 'delay', label: '소스 지연 (ms)', color: CHART_COLORS.violet }]}
              height={160}
              timeAxis
            />
          </div>
        )}
      </Panel>
    </div>
  )
}

/* ---------------------------------------------------------------- Consumers tab (EDA) */

function ConsumersTab({ edge }: { edge: Edge }) {
  const app = useApp()
  const wsId = app.currentProject?.id
  const [groups, setGroups] = useState<ConsumerGroupInfo[]>([])
  const [lagWarningThreshold, setLagWarningThreshold] = useState<number | null>(null)
  const [lagThresholdError, setLagThresholdError] = useState(false)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    if (!wsId) return
    let cancelled = false
    setLoading(true)
    api.pipelineConsumerGroups(wsId, edge.id)
      .then((g) => { if (!cancelled) setGroups(g) })
      .catch(() => {})
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [wsId, edge.id])

  useEffect(() => {
    if (!wsId) {
      setLagWarningThreshold(null)
      setLagThresholdError(false)
      return
    }
    let cancelled = false
    setLagWarningThreshold(null)
    setLagThresholdError(false)
    api
      .getThresholdSettings(wsId)
      .then((settings) => { if (!cancelled) setLagWarningThreshold(settings.warning) })
      .catch(() => {
        if (!cancelled) {
          setLagWarningThreshold(null)
          setLagThresholdError(true)
        }
      })
    return () => { cancelled = true }
  }, [wsId])

  const [openGroup, setOpenGroup] = useState<string | null>(null)

  const lagChartData = groups.map((g) => ({ name: g.name, lag: g.totalLag }))
  const axis = { fontSize: 10, fill: '#94a3b8' }
  const lagTone = (lag: number): 'unknown' | 'warning' | 'ok' => {
    if (lagWarningThreshold == null) return 'unknown'
    return lag >= lagWarningThreshold ? 'warning' : 'ok'
  }
  const thresholdLabel = lagWarningThreshold == null
    ? lagThresholdError ? '조회 실패' : '불러오는 중…'
    : formatNum(lagWarningThreshold)

  return (
    <div className="space-y-4">

      {/* lag bar chart */}
      <Panel title="Consumer Group Lag"
        right={
          <span className="text-[12px] text-gray-400">
            임계값 <span className={cn('font-semibold', lagThresholdError ? 'text-rose-600' : 'text-amber-600')}>
              {thresholdLabel}
            </span>
          </span>
        }>
        {loading ? (
          <div className="px-5 py-10 text-center text-[12.5px] text-gray-400">불러오는 중…</div>
        ) : groups.length === 0 ? (
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
                        fill={
                          lagTone(entry.lag) === 'unknown'
                            ? CHART_COLORS.slate
                            : lagTone(entry.lag) === 'warning'
                              ? CHART_COLORS.amber
                              : CHART_COLORS.emerald
                        }
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
              const offsets  = g.partitionOffsets ?? []
              const isOpen   = openGroup === g.name
              return (
                <Fragment key={g.name}>
                  <tr className={cn('border-b border-gray-50', isOpen && 'bg-brand-50/30')}>
                    <td className="px-5 py-2.5 font-mono font-medium text-gray-800">{g.name}</td>
                    <td className="px-4 py-2.5"><StatusBadge status={g.state} /></td>
                    <td className="px-4 py-2.5 text-gray-600">{g.members}</td>
                    <td className={cn('px-4 py-2.5 text-right font-mono font-semibold tabular-nums',
                      lagTone(g.totalLag) === 'unknown'
                        ? 'text-slate-500'
                        : lagTone(g.totalLag) === 'warning' ? 'text-amber-600' : 'text-gray-700')}>
                      {formatNum(g.totalLag)}
                    </td>
                    <td className="px-4 py-2.5 text-gray-500">
                      {g.lastCommit > 0 ? new Date(g.lastCommit).toLocaleTimeString('ko-KR') : '—'}
                    </td>
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
                                      lagTone(lag) === 'unknown'
                                        ? 'text-slate-500'
                                        : lagTone(lag) === 'warning'
                                          ? 'text-amber-600'
                                          : lag > 0 ? 'text-gray-700' : 'text-emerald-500')}>
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

/* ---------------------------------------------------------------- Trace tab (#498) */

function TraceTab({ edge }: { edge: Edge }) {
  const app = useApp()
  const wsId = app.currentProject?.id
  const [trace, setTrace] = useState<TraceSummaryResponse | null>(null)
  const [error, setError] = useState(false)

  useEffect(() => {
    if (!wsId) return
    let cancelled = false
    setTrace(null)
    setError(false)
    api.pipelineTrace(wsId, edge.id)
      .then((t) => { if (!cancelled) setTrace(t) })
      .catch(() => { if (!cancelled) setError(true) })
    return () => { cancelled = true }
  }, [wsId, edge.id])

  if (error) {
    return (
      <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-gray-200 bg-white py-16">
        <Icon name="alert" size={24} className="mb-2 text-rose-300" />
        <p className="text-[13px] text-gray-400">추적 정보를 불러오지 못했습니다</p>
      </div>
    )
  }
  if (trace === null) {
    return (
      <div className="flex items-center justify-center rounded-xl border border-dashed border-gray-200 bg-white py-16 text-[13px] text-gray-400">
        불러오는 중…
      </div>
    )
  }
  return (
    <div className="space-y-4">
      {edge.pattern === 'fan-out' && (
        <p className="text-[12px] text-gray-400">
          EDA는 source→topic 구간까지 표시됩니다. consumer(고객 앱)는 같은 traceId로 고객 관측도구에서 이어볼 수 있습니다.
        </p>
      )}
      <TraceWaterfall trace={trace} />
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
  // 추세 차트 실데이터(#126, Prometheus range): 전송된 데이터 개수·이벤트분포.
  // (consumer lag 추이는 60초 커밋 톱니파라 행 동기화와 무관해 오독되므로 노출하지 않는다, #200)
  const [eventSeries, setEventSeries] = useState<EventDistPoint[]>([])
  const [rangeMin, setRangeMin] = useState(15)   // 시계열 차트 시간 범위(기본 15분)
  useEffect(() => {
    if (!wsId) return
    let cancelled = false
    setSync(null)      // 최초 진입만 로딩 표시(폴링 갱신 때는 깜빡임 없이 값만 교체)
    setSyncErr(false)
    // 실시간 갱신(#200): 동기화율·전송 레코드 수·이벤트분포를 주기 폴링해 실시간으로 움직인다.
    const load = () => {
      api.pipelineSyncStatus(wsId, edge.id)
        .then((s) => { if (!cancelled) setSync(s) })
        .catch(() => { if (!cancelled) setSyncErr(true) })
      api.pipelineEventDist(wsId, edge.id, rangeMin).then((d) => { if (!cancelled) setEventSeries(d) }).catch(() => {})
    }
    load()
    const timer = setInterval(load, 5000)
    return () => { cancelled = true; clearInterval(timer) }
  }, [wsId, edge.id, rangeMin])

  const hhmm = (ts: number) => new Date(ts).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })
  const rangeLabel = rangeMin >= 60 ? `최근 ${rangeMin / 60}시간` : `최근 ${rangeMin}분`

  const tableName  = edge.table ? `${edge.table.schema}.${edge.table.name}` : '—'
  // (#501) 완료 판정을 행수 → 컨슈머 lag + sink 커넥터 health 기준으로(UI 요소는 그대로).
  // lag<0 = sink 미소비(준비중), lag=0 = 따라잡음(완료), lag>0 = 처리중. sinkFailed = 오류.
  const lag        = sync?.lag ?? -1
  const endOffset  = sync?.endOffset ?? -1
  const sinkFailed = sync?.sinkFailed ?? false
  const sinkReady  = lag >= 0
  const isHealthy  = sinkReady && lag === 0 && !sinkFailed
  // % = caught-up 비율((end−lag)/end). 미소비면 0, 토픽 비어있으면 100.
  const syncPct    = !sinkReady ? 0
    : endOffset > 0 ? Math.max(0, Math.min(100, ((endOffset - lag) / endOffset) * 100))
    : 100
  const pctColor   = sinkFailed ? 'text-rose-600' : isHealthy ? 'text-emerald-600' : 'text-amber-600'
  const barColor   = sinkFailed ? 'bg-rose-400'   : isHealthy ? 'bg-emerald-400'  : 'bg-amber-400'

  // 실데이터(Prometheus)만 사용. 비어있으면 빈 차트(더미 위장 금지, #175).
  // 소스지연은 Debezium이 전달할 데이터가 없을 때(idle) -1을 준다. 이때는 "지연 0"이 아니라
  // 측정값이 없는 것이므로 null로 두어 그래프를 끊는다(Prometheus처럼). 0으로 클램프하면 거짓 0.
  // t = epoch ms → 실제 시간축(scale=time)으로 배치. 데이터 없는 구간은 빈 공간(균등 시각 눈금).
  // 백엔드는 값이 있는 버킷만 반환 → 빈 구간을 step(=max(30,minutes)s) 간격으로 0-채움해
  // 균등 간격 연속 막대(Grafana 스타일)로 만든다. 마지막 점을 앵커로 잡아 데이터가 그리드에 정렬되게 함.
  const eventDist   = useMemo(() => {
    const stepMs = Math.max(60, rangeMin) * 1000 // 백엔드 evStep(분당 1버킷, 분 경계 고정)과 동일
    const pts    = [...eventSeries].sort((a, b) => a.timestamp - b.timestamp)
    const end    = pts.length ? pts[pts.length - 1].timestamp : Math.floor(Date.now() / stepMs) * stepMs
    const start  = end - rangeMin * 60_000
    const byTs   = new Map(pts.map((p) => [p.timestamp, p]))
    const grid: { t: number; insert: number; update: number; delete: number; count: number }[] = []
    for (let t = end; t >= start; t -= stepMs) {
      const p = byTs.get(t)
      const insert = p?.insert ?? 0, update = p?.update ?? 0, del = p?.delete ?? 0
      grid.push({ t, insert, update, delete: del, count: insert + update + del })
    }
    return grid.reverse()
  }, [eventSeries, rangeMin])
  // 약 6개 라벨만 노출(나머지 버킷은 솎음) → 균등 간격 시각 눈금
  const tickEvery = Math.max(0, Math.ceil(eventDist.length / 6) - 1)
  const axis = { fontSize: 10, fill: '#94a3b8' }

  return (
    <div className="space-y-4">
      <Panel>
        {/* ── Source ↔ Sink 시각 ─────────────────────────────────── */}
        <div className="px-8 py-8">
          <div className="grid grid-cols-[1fr_180px_1fr] items-center gap-6">

            {/* Source DB */}
            <DBNodeCard node={sourceNode} role="Source" />

            {/* Center: 동기화율 (선형) */}
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

              <span className={cn('text-[24px] font-bold tabular-nums leading-none',
                sync === null ? 'text-gray-300' : pctColor)}>
                {sync === null ? '—' : `${syncPct.toFixed(1)}%`}
              </span>

              <div className="w-full overflow-hidden rounded-full bg-gray-100" style={{ height: 7 }}>
                <div className={cn('h-full rounded-full transition-all', barColor)}
                  style={{ width: `${Math.min(syncPct, 100)}%` }} />
              </div>

              <span className={cn('text-[11.5px] font-medium',
                sync === null ? 'text-gray-400'
                  : sinkFailed ? 'text-rose-600' : isHealthy ? 'text-emerald-600' : 'text-amber-600')}>
                {sync === null
                  ? '불러오는 중…'
                  : sinkFailed
                    ? '동기화 오류'
                    : !sinkReady
                      ? 'sink 준비중'
                      : isHealthy
                        ? '동기화 완료'
                        : `${formatNum(lag)}건 처리중`}
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
      {/* 전송 레코드 현황 — 분당 변경 이벤트 수(insert+update+delete). 누적 행 수가 아닌 throughput 지표 */}
      <Panel title="전송 레코드 현황" right={<RangeSelector value={rangeMin} onChange={setRangeMin} />}>
        <div className="px-4 py-3">
          <ResponsiveChart width="100%" height={140}>
            <BarChart data={eventDist} maxBarSize={22} barCategoryGap="8%" margin={{ top: 4, right: 8, left: -28, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" vertical={false} />
              <XAxis dataKey="t" tickFormatter={hhmm} interval={tickEvery}
                     tick={axis} tickLine={false} axisLine={false} />
              <YAxis tick={axis} tickLine={false} axisLine={false} allowDecimals={false} />
              <Tooltip contentStyle={tooltipStyle} labelFormatter={(v) => hhmm(Number(v))} />
              <Bar dataKey="count" fill={CHART_COLORS.brand} name="전송 레코드" radius={[2, 2, 0, 0]} />
            </BarChart>
          </ResponsiveChart>
        </div>
      </Panel>

      <Panel title="이벤트 타입 분포" right={<span className="text-[12px] text-gray-400">{rangeLabel}</span>}>
        <div className="px-4 py-3">
          <ResponsiveChart width="100%" height={140}>
            <BarChart data={eventDist} maxBarSize={22} barCategoryGap="8%" margin={{ top: 4, right: 8, left: -28, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" vertical={false} />
              <XAxis dataKey="t" tickFormatter={hhmm} interval={tickEvery}
                     tick={axis} tickLine={false} axisLine={false} />
              <YAxis tick={axis} tickLine={false} axisLine={false} allowDecimals={false} />
              <Tooltip contentStyle={tooltipStyle} labelFormatter={(v) => hhmm(Number(v))} />
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

const OP_META: Record<string, { label: string; cls: string }> = {
  c: { label: 'INSERT', cls: 'bg-emerald-50 text-emerald-700 border-emerald-200' },
  u: { label: 'UPDATE', cls: 'bg-amber-50  text-amber-700  border-amber-200'  },
  d: { label: 'DELETE', cls: 'bg-rose-50   text-rose-700   border-rose-200'   },
  r: { label: 'READ',   cls: 'bg-sky-50    text-sky-700    border-sky-200'    },
}

function MessagesTab({ edge }: { edge: Edge }) {
  const app = useApp()
  const wsId = app.currentProject?.id
  const [msgs, setMsgs] = useState<KafkaMessageRecord[]>([])
  const [msgLoading, setMsgLoading] = useState(true)
  // (#495) 파티션 선택을 하드코딩(3/6) 대신 실제 토픽 파티션 수로
  const [topicInfo, setTopicInfo] = useState<TopicInfoResponse | null>(null)

  useEffect(() => {
    if (!wsId) return
    let cancelled = false
    setMsgLoading(true)
    api.pipelineMessages(wsId, edge.id, 50)
      .then((m) => { if (!cancelled) setMsgs(m) })
      .catch(() => {})
      .finally(() => { if (!cancelled) setMsgLoading(false) })
    return () => { cancelled = true }
  }, [wsId, edge.id])

  // (#495) 실제 토픽 파티션 수 — 셀렉터를 이걸로 그린다(하드코딩 3/6 제거)
  useEffect(() => {
    if (!wsId) return
    let cancelled = false
    api.pipelineTopicInfo(wsId, edge.id)
      .then((t) => { if (!cancelled) setTopicInfo(t) })
      .catch(() => {})
    return () => { cancelled = true }
  }, [wsId, edge.id])

  const [partition, setPartition] = useState<'all' | number>('all')
  const [live, setLive] = useState(true)   // 기본 live: 진입 즉시 실시간으로 메시지가 쌓이는 걸 본다(#200)
  const [search, setSearch] = useState('')
  const [selected, setSelected] = useState<number | null>(null)
  const [rawView, setRawView] = useState(false)

  // live 모드: 3초마다 새로고침
  useEffect(() => {
    if (!live || !wsId) return
    const id = setInterval(() => {
      api.pipelineMessages(wsId, edge.id, 50)
        .then((m) => setMsgs(m))
        .catch(() => {})
    }, 3000)
    return () => clearInterval(id)
  }, [live, wsId, edge.id])

  const visible = msgs.filter((m) => {
    if (partition !== 'all' && m.partition !== partition) return false
    if (search && !m.key?.toLowerCase().includes(search.toLowerCase()) &&
        !JSON.stringify(m.after ?? m.before ?? '').toLowerCase().includes(search.toLowerCase())) return false
    return true
  })

  // (#495) 셀렉터에 띄울 파티션 id 목록 — 실제 토픽 파티션, 로딩 전엔 메시지에 등장한 파티션으로 폴백
  const partitionIds = useMemo(() => {
    if (topicInfo?.partitions?.length) {
      return topicInfo.partitions.map((p) => p.id).sort((a, b) => a - b)
    }
    return [...new Set(msgs.map((m) => m.partition))].sort((a, b) => a - b)
  }, [topicInfo, msgs])

  const selectedMsg = selected !== null ? msgs.find((m) => m.offset === selected) ?? null : null

  function buildRawEnvelope(m: KafkaMessageRecord) {
    return {
      op: m.op,
      ts_ms: m.tsMs,
      source: {
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
            {partitionIds.map((pid) => (
              <option key={pid} value={pid}>P{pid}</option>
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
          {msgLoading ? (
            <div className="py-12 text-center text-[13px] text-gray-400">불러오는 중…</div>
          ) : visible.length === 0 ? (
            <div className="py-12 text-center text-[13px] text-gray-400">메시지가 없습니다.</div>
          ) : null}
          {visible.map((m) => {
            const isOpen = selected === m.offset
            const meta = OP_META[m.op ?? ''] ?? { label: 'MSG', cls: 'bg-gray-50 text-gray-700 border-gray-200' }
            const tsLabel = new Date(m.tsMs).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', second: '2-digit', fractionalSecondDigits: 3 })
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
                  <span className="font-mono text-gray-500">{tsLabel}</span>
                  <span className="truncate font-mono font-medium text-gray-800">{m.key ?? '(null)'}</span>
                  <span>
                    <span className={cn('rounded border px-1.5 py-0.5 text-[10px] font-bold', meta.cls)}>
                      {meta.label}
                    </span>
                  </span>
                  <span className="font-mono text-[11px] text-gray-400">—</span>
                </button>

                {isOpen && selectedMsg && (
                  <div className="border-t border-gray-100 bg-gray-50/60 px-4 py-3">
                    {/* meta row */}
                    <div className="mb-3 flex flex-wrap items-center gap-3 text-[11px] text-gray-400">
                      <span className="font-mono">Partition <strong className="text-gray-600">{m.partition}</strong></span>
                      <span>·</span>
                      <span className="font-mono">Offset <strong className="text-gray-600">{formatNum(m.offset)}</strong></span>
                      <span>·</span>
                      <span className="font-mono">{tsLabel}</span>
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
  const app = useApp()
  const toast = useToast()
  const wsId = app.currentProject?.id
  const [lang, setLang] = useState<'Java' | 'Python' | 'Node.js'>('Java')
  const [guide, setGuide] = useState<ConnectionGuideResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const copy = (v: string, what: string) => { navigator.clipboard?.writeText(v); toast(`${what} copied`) }

  useEffect(() => {
    if (!wsId) return
    let cancelled = false
    setGuide(null)
    setError(null)
    api
      .getConnectionGuide(wsId, edge.id)
      .then((res) => { if (!cancelled) setGuide(res) })
      .catch((e) => {
        if (!cancelled) setError(e instanceof Error ? e.message : '연결 가이드를 불러오지 못했습니다')
      })
    return () => { cancelled = true }
  }, [wsId, edge.id])

  if (!wsId) {
    return <Panel title="Connection details"><div className="px-4 py-10 text-center text-[13px] text-gray-400">워크스페이스를 먼저 선택하세요.</div></Panel>
  }
  if (error) {
    return <Panel title="Connection details"><div className="px-4 py-10 text-center text-[13px] text-rose-500">{error}</div></Panel>
  }
  if (!guide) {
    return <Panel title="Connection details"><div className="px-4 py-10 text-center text-[13px] text-gray-400">불러오는 중…</div></Panel>
  }

  const credential = guide.credentialReference
  const keyRefs = Object.entries(credential.keyRefs)
  const template = guide.authenticationTemplates[0] ?? null
  const topicName = guide.topics[0]?.name ?? edge.topic ?? '<topic>'
  const snippets: Record<string, string> = buildConsumerSnippets(guide, topicName, template)

  return (
    <div className="space-y-4">
      <Panel title="Connection details">
        <div className="space-y-2.5 px-4 py-3.5">
          <Row label="Bootstrap server" value={guide.bootstrapServers} onCopy={() => copy(guide.bootstrapServers, 'Bootstrap server')} />
          <Row label="Recommended group" value={guide.recommendedGroupId} onCopy={() => copy(guide.recommendedGroupId, 'Consumer group')} />
          <Row label="Auth method" value={guide.authenticationMethod || 'NONE'} onCopy={() => copy(guide.authenticationMethod || 'NONE', 'Auth method')} />
          <Row label="Credential Secret" value={`${credential.namespace}/${credential.secretName}`} onCopy={() => copy(`${credential.namespace}/${credential.secretName}`, 'Credential Secret')} />
        </div>
        <div className="border-t border-gray-100 px-4 py-3.5">
          <div className="mb-2 text-[11px] font-semibold uppercase tracking-wide text-gray-400">Credential key references</div>
          {keyRefs.length === 0 ? (
            <div className="rounded-lg border border-gray-100 bg-gray-50 px-3 py-2 text-[12.5px] text-gray-400">키 참조가 없습니다.</div>
          ) : (
            <div className="grid gap-1.5 sm:grid-cols-2">
              {keyRefs.map(([usage, key]) => (
                <div key={usage} className="flex items-center justify-between rounded-lg border border-gray-100 bg-gray-50 px-3 py-2 text-[12px]">
                  <span className="font-medium text-gray-500">{usage}</span>
                  <span className="font-mono text-gray-700">{key}</span>
                </div>
              ))}
            </div>
          )}
          <div className="mt-2 flex flex-wrap gap-1.5">
            {credential.availableKeys.length === 0 ? (
              <span className="text-[12px] text-gray-400">사용 가능한 Secret 키가 없습니다.</span>
            ) : credential.availableKeys.map((key) => (
              <span key={key} className="rounded bg-gray-100 px-1.5 py-0.5 font-mono text-[11px] text-gray-600">{key}</span>
            ))}
          </div>
        </div>
      </Panel>

      <Panel title="Topic references">
        {guide.topics.length === 0 ? (
          <div className="px-4 py-8 text-center text-[13px] text-gray-400">토픽 참조가 없습니다.</div>
        ) : (
          <table className="w-full text-[12.5px]">
            <thead>
              <tr className="border-b border-gray-100 text-left text-[11px] uppercase tracking-wide text-gray-400">
                <th className="px-4 py-2 font-semibold">Topic</th>
                <th className="px-3 py-2 font-semibold">Source table</th>
                <th className="px-3 py-2 font-semibold">Role</th>
              </tr>
            </thead>
            <tbody>
              {guide.topics.map((topic) => (
                <tr key={`${topic.role}-${topic.name}`} className="border-b border-gray-50">
                  <td className="px-4 py-2.5 font-mono font-medium text-gray-800">{topic.name}</td>
                  <td className="px-3 py-2.5 font-mono text-gray-500">{topic.sourceTable ?? '—'}</td>
                  <td className="px-3 py-2.5 text-gray-500">{topic.role}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Panel>

      <Panel title="Authentication templates">
        {guide.authenticationTemplates.length === 0 ? (
          <div className="px-4 py-8 text-center text-[13px] text-gray-400">인증 템플릿이 없습니다.</div>
        ) : (
          <div className="divide-y divide-gray-100">
            {guide.authenticationTemplates.map((t) => (
              <div key={`${t.type}-${t.securityProtocol}`} className="px-4 py-3">
                <div className="mb-2 flex items-center gap-2">
                  <span className="rounded bg-brand-50 px-2 py-0.5 text-[11px] font-semibold text-brand-700">{t.type}</span>
                  <span className="font-mono text-[12px] text-gray-500">{t.securityProtocol}</span>
                  <span className="text-[12px] text-gray-400">secret {t.credentialReference.secretName}</span>
                </div>
                <div className="grid gap-1.5">
                  {Object.entries(t.properties).map(([key, value]) => (
                    <div key={key} className="grid grid-cols-[180px_1fr] gap-2 rounded-lg border border-gray-100 bg-gray-50 px-3 py-2 text-[12px]">
                      <span className="font-mono text-gray-500">{key}</span>
                      <span className="break-all font-mono text-gray-700">{value}</span>
                    </div>
                  ))}
                </div>
              </div>
            ))}
          </div>
        )}
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

// buildConsumerSnippets and escapeSnippetValue are exported from ../../lib/pipelineSnippets

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
  const [mapping, setMapping] = useState<TableMappingResponse | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!wsId) return
    let cancelled = false
    setMapping(null)
    setError(null)
    api
      .getTableMapping(wsId, edge.id)
      .then((res) => { if (!cancelled) setMapping(res) })
      .catch((e) => {
        if (!cancelled) setError(e instanceof Error ? e.message : '테이블 매핑을 불러오지 못했습니다')
      })
    return () => { cancelled = true }
  }, [wsId, edge.id])

  const titleTable = edge.table ? `${edge.table.schema}.${edge.table.name}` : 'pipeline'

  return (
    <Panel title={`Table mapping · ${titleTable}`}>
      {!wsId ? (
        <div className="px-4 py-10 text-center text-[13px] text-gray-400">워크스페이스를 먼저 선택하세요.</div>
      ) : error ? (
        <div className="px-4 py-10 text-center text-[13px] text-rose-500">{error}</div>
      ) : mapping === null ? (
        <div className="px-4 py-10 text-center text-[13px] text-gray-400">불러오는 중…</div>
      ) : (
        <>
          <div className="grid gap-2 border-b border-gray-100 px-4 py-3 text-[12px] sm:grid-cols-2">
            <div>
              <span className="text-gray-400">Source connector</span>
              <div className="mt-0.5 font-mono text-gray-700">{mapping.sourceConnector || '—'}</div>
            </div>
            <div>
              <span className="text-gray-400">Sink connector</span>
              <div className="mt-0.5 font-mono text-gray-700">{mapping.sinkConnector || '—'}</div>
            </div>
          </div>
          {mapping.mappings.length === 0 ? (
            <div className="px-4 py-10 text-center text-[13px] text-gray-400">테이블 매핑이 없습니다.</div>
          ) : (
            <table className="w-full text-[12.5px]">
              <thead>
                <tr className="border-b border-gray-100 text-left text-[11px] uppercase tracking-wide text-gray-400">
                  <th className="px-4 py-2 font-semibold">Source table</th>
                  <th className="px-4 py-2 font-semibold">Kafka topic</th>
                  <th className="px-4 py-2 font-semibold">Sink table</th>
                </tr>
              </thead>
              <tbody>
                {mapping.mappings.map((m) => (
                  <tr key={`${m.sourceTable}-${m.kafkaTopic}-${m.sinkTable}`} className="border-b border-gray-50">
                    <td className="px-4 py-2.5 font-mono font-medium text-gray-800">{m.sourceTable || '—'}</td>
                    <td className="px-4 py-2.5 font-mono text-gray-600">{m.kafkaTopic || '—'}</td>
                    <td className="px-4 py-2.5 font-mono text-gray-500">{m.sinkTable || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
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
