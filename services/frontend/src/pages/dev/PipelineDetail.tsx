import { Fragment, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { Bar, BarChart, CartesianGrid, Cell, Tooltip, XAxis, YAxis } from 'recharts'
import { Icon } from '../../components/Icon'
import { Panel, StatusBadge } from '../../components/blocks'
import { TrendChart, CHART_COLORS, ResponsiveChart } from '../../components/Charts'
import { TechIcon, nodeKind } from '../../components/TechIcon'
import { useToast } from '../../components/Toast'
import { useApp } from '../../store/AppStore'
import { pipelineLabel } from '../../data/helpers'
import { sinkDisplayStatus } from '../../lib/mappers'
import { formatMessageSize, messageSizeBytes } from '../../lib/messageMetrics'
import { buildConsumerSnippets, escapeSnippetValue } from '../../lib/pipelineSnippets'
import type { Edge, Node } from '../../data/types'
import {
  api,
  type ConnectionGuideResponse,
  type ConnectorInfo,
  type ConsumerGroupInfo,
  type EventDistPoint,
  type KafkaMessageRecord,
  type MessagePageResponse,
  type MetricPoint,
  type SchemaColumn,
  type SyncStatusResponse,
  type TableMappingResponse,
  type TopicInfoResponse,
  type TraceSummaryResponse,
} from '../../lib/api'
import { TraceFlow } from '../../components/TraceFlow'
import { Modal } from '../../components/Modal'
import { cn, formatNum } from '../../lib/format'

const tooltipStyle = {
  borderRadius: 8, border: '1px solid #ececec', fontSize: 12,
  boxShadow: '0 4px 16px rgba(0,0,0,0.08)',
}

/* ---------------------------------------------------------------- main */

export function PipelineDetail() {
  const app = useApp()
  const toast = useToast()
  const edge = app.edges.find((e) => e.id === app.selectedPipelineId)
  const isEda = edge?.pattern === 'fan-out'
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [deleteBusy, setDeleteBusy] = useState(false)

  // Connector 탭은 EDA/CDC 모두 표시(실제 커넥터는 ConnectorTab이 백엔드에서 조회, #107)
  // (#266) Sync 내용을 Overview로 이동, Topic & Partition은 별도 'Topic' 탭으로 분리.
  const tabs = isEda
    ? ['Overview', 'Consumers', 'Connector', 'Messages', 'Connection Guide', 'Tracing']
    : ['Overview', 'Topic', 'Connector', 'Messages', 'Table Mapping', 'Tracing']

  const [tab, setTab] = useState(tabs[0])

  useEffect(() => {
    if (app.pipelineTab && tabs.includes(app.pipelineTab)) {
      setTab(app.pipelineTab)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [app.pipelineTab, app.selectedPipelineId])

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
  const isCreating = edge.status === 'creating'
  const deletePipeline = async () => {
    setDeleteBusy(true)
    try {
      await app.deletePipeline(edge.id)
      toast('Pipeline deleted', 'info')
      app.setView('pipelines')
      setConfirmDelete(false)
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Pipeline 삭제에 실패했습니다', 'error')
    } finally {
      setDeleteBusy(false)
    }
  }

  // (#267) 원인 attribution → 탭 매핑: DB(연결 끊김/차단) → Overview, 커넥터 FAILED/에러 → Connector.
  const connectorErr = !!connectors?.some((c) => c.state === 'FAILED' || (c.lastError != null && c.lastError !== ''))
  // (#547) sink는 CDC-source readiness(BLOCKED)가 무관 → 연결 끊김(UNREACHABLE)만 error로 센다.
  const dbErr        = source.status === 'error' || (!!sink && sinkDisplayStatus(sink) === 'error')
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
            isEda ? 'bg-[#ededed] text-[#6b6b73]' : 'bg-[#ededed] text-[#6b6b73]')}>
            {isEda ? '이벤트 스트림' : '데이터 동기화'}
          </span>
          <StatusBadge status={edge.status} />
          <div className="flex-1" />
          {edge.status === 'paused'
            ? <ActBtn icon="play"  label="Resume" onClick={async () => { await app.resumePipeline(edge.id); toast('Pipeline resumed') }} />
            : <ActBtn icon="pause" label="Pause" disabled={isCreating} title={isCreating ? 'creating 상태에서는 일시정지할 수 없습니다' : undefined}
                onClick={async () => { await app.pausePipeline(edge.id); toast('Pipeline paused') }} />}
          <ActBtn icon="trash" label="Delete" danger
            disabled={isCreating} title={isCreating ? 'creating 상태에서는 삭제할 수 없습니다' : undefined}
            onClick={() => setConfirmDelete(true)} />
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
                className="ml-1.5 inline-block h-1.5 w-1.5 rounded-full bg-[#c0392b] align-middle" />
            )}
          </button>
        ))}
      </div>

      <div className="mt-4">
        {tab === 'Overview'          && (isEda ? <TopicTab edge={edge} /> : <SyncTab edge={edge} />)}
        {tab === 'Topic'             && <TopicTab edge={edge} />}
        {tab === 'Consumers'         && <ConsumersTab edge={edge} />}
        {tab === 'Connector'         && <ConnectorTab edge={edge} />}
        {tab === 'Messages'          && <MessagesTab edge={edge} />}
        {tab === 'Connection Guide'  && <GuideTab edge={edge} />}
        {tab === 'Table Mapping'     && <MappingTab edge={edge} />}
        {tab === 'Tracing'           && <TraceTab edge={edge} />}
      </div>

      {confirmDelete && (
        <DeletePipelineDialog
          edge={edge}
          busy={deleteBusy}
          onCancel={() => setConfirmDelete(false)}
          onConfirm={deletePipeline}
        />
      )}
    </div>
  )
}

function ActBtn({ icon, label, onClick, danger, disabled, title }: {
  icon: 'play' | 'pause' | 'trash'
  label: string
  onClick: () => void
  danger?: boolean
  disabled?: boolean
  title?: string
}) {
  return (
    <button disabled={disabled} title={title} onClick={onClick} className={cn(
      'flex items-center gap-1.5 rounded-md border px-2.5 py-1.5 text-[12.5px] font-medium transition-colors',
      danger ? 'border-[#c0392b] text-[#c0392b] hover:bg-[#fcf3f2]' : 'border-gray-300 text-gray-700 hover:bg-gray-50',
      disabled && 'cursor-not-allowed opacity-45 hover:bg-transparent',
    )}>
      <Icon name={icon} size={13} />
      {label}
    </button>
  )
}

function DeletePipelineDialog({
  edge,
  busy,
  onCancel,
  onConfirm,
}: {
  edge: Edge
  busy: boolean
  onCancel: () => void
  onConfirm: () => void
}) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-gray-900/30 px-4">
      <div className="w-full max-w-md overflow-hidden rounded-xl border border-gray-200 bg-white shadow-xl">
        <div className="flex items-start gap-3 border-b border-gray-100 px-5 py-4">
          <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-[#fcf3f2] text-[#c0392b]">
            <Icon name="trash" size={15} />
          </div>
          <div className="min-w-0">
            <div className="text-[15px] font-semibold text-gray-900">Pipeline 삭제</div>
            <div className="mt-1 text-[12.5px] leading-relaxed text-gray-500">
              <span className="font-semibold text-gray-700">{pipelineLabel(edge)}</span> 파이프라인을 삭제합니다. 연결된 백엔드 리소스 삭제 API가 실행됩니다.
            </div>
          </div>
        </div>
        <div className="flex justify-end gap-2 px-5 py-3">
          <button
            onClick={onCancel}
            disabled={busy}
            className="rounded-md border border-gray-200 px-3 py-1.5 text-[12.5px] font-medium text-gray-600 hover:bg-gray-50 disabled:opacity-50"
          >
            취소
          </button>
          <button
            onClick={onConfirm}
            disabled={busy}
            className="rounded-md bg-[#c0392b] px-3 py-1.5 text-[12.5px] font-medium text-white hover:bg-[#c0392b] disabled:opacity-50"
          >
            {busy ? '삭제 중…' : '삭제'}
          </button>
        </div>
      </div>
    </div>
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
/* Topic & Partition은 독립 탭이며, Overview는 Sync/Topic 내용을 직접 렌더한다. */

function TopicTab({ edge }: { edge: Edge }) {
  const app = useApp()
  const wsId = app.currentProject?.id

  const [topicInfo, setTopicInfo] = useState<TopicInfoResponse | null>(null)
  const [topicInfoErr, setTopicInfoErr] = useState(false)

  useEffect(() => {
    if (!wsId) return
    let cancelled = false
    const load = () => {
      api.pipelineTopicInfo(wsId, edge.id)
        .then((t) => {
          if (!cancelled) {
            setTopicInfo(t)
            setTopicInfoErr(false)
          }
        })
        .catch(() => {
          if (!cancelled) {
            setTopicInfo(null)
            setTopicInfoErr(true)
          }
        })
    }
    load()
    const timer = setInterval(load, 5000)
    return () => { cancelled = true; clearInterval(timer) }
  }, [wsId, edge.id])

  const partitions = topicInfo?.partitions ?? []
  const retentionMs = topicInfo?.retentionMs ?? -1
  const retention = topicInfoErr
    ? '조회 실패'
    : retentionMs > 0
      ? retentionMs >= 86400000 ? `${Math.round(retentionMs / 86400000)}일` : `${Math.round(retentionMs / 3600000)}시간`
      : '—'
  const isrPct = topicInfo?.isrPct ?? 100
  const isrOk = isrPct >= 100

  // EDA 핵심 지표: Debezium MilliSecondsBehindSource 추이. 범위는 overview처럼 5/15/30/1h/3h 선택.
  const [delayRangeMin, setDelayRangeMin] = useState(15)
  const [sourceDelaySeries, setSourceDelaySeries] = useState<MetricPoint[]>([])
  useEffect(() => {
    if (!wsId) return
    let cancelled = false
    const load = () => {
      api.pipelineSourceDelay(wsId, edge.id, delayRangeMin).then((d) => { if (!cancelled) setSourceDelaySeries(d) }).catch(() => {})
    }
    load()
    const timer = setInterval(load, 10000)
    return () => { cancelled = true; clearInterval(timer) }
  }, [wsId, edge.id, delayRangeMin])

  // 소스 지연(Grafana식): 선택 창 전체를 고정 시간축으로 깔고, 데이터가 흐른 버킷에만 값을,
  // idle 버킷은 null로 둬 선을 끊는다(connectNulls=false). 백엔드는 흐른 버킷만 반환하므로
  // 여기서 창 전체 그리드를 재구성해 빈 구간을 명시적으로 null 처리한다.
  const sourceDelayWindow = useMemo(() => {
    const stepMs = Math.max(15, delayRangeMin) * 1000 // 백엔드 stepFor(min)=max(15,min)s 와 동일 해상도
    const now = Date.now()
    const start = now - delayRangeMin * 60 * 1000
    const byBucket = new Map<number, number>()
    for (const p of sourceDelaySeries) {
      if (p.value < 0) continue // -1 = idle 센티넬
      byBucket.set(Math.round((p.timestamp - start) / stepMs), p.value)
    }
    const n = Math.ceil((now - start) / stepMs)
    const data: { t: number; delay: number | null }[] = []
    for (let b = 0; b <= n; b++) {
      data.push({ t: start + b * stepMs, delay: byBucket.has(b) ? (byBucket.get(b) as number) : null })
    }
    return { data, domain: [start, now] as [number, number], hasData: byBucket.size > 0 }
  }, [sourceDelaySeries, delayRangeMin])
  const sourceDelayData = sourceDelayWindow.data

  return (
    <div className="space-y-4">

      {/* topic & partitions */}
      <Panel title="Topic & Partitions">
        {/* header row */}
        <div className="flex items-center gap-3 border-b border-gray-100 px-5 py-3.5">
          <span className="font-mono text-[13.5px] font-semibold text-gray-900">{edge.topic}</span>
          <StatusBadge status={edge.status} />
          <div className="ml-auto flex items-center gap-4 text-[12px] text-gray-500">
            <span>{topicInfoErr ? '파티션 조회 실패' : <><span className="font-semibold text-gray-700">{partitions.length}</span> 파티션</>}</span>
            <span className={cn('font-semibold', topicInfoErr ? 'text-[#c0392b]' : isrOk ? 'text-[#6b6b73]' : 'text-[#6b6b73]')}>
              ISR {topicInfoErr ? '조회 실패' : isrOk ? '정상' : `${Math.round(isrPct)}%`}
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
              <tr><td colSpan={5} className="px-5 py-6 text-center text-gray-400">{topicInfoErr ? '토픽 정보를 불러오지 못했습니다' : '파티션 정보 없음'}</td></tr>
            )}
          </tbody>
        </table>
        {!topicInfoErr && !isrOk && (
          <div className="mx-5 mb-4 mt-1 flex items-center gap-2 rounded-lg border border-[#ececec] bg-[#ededed] px-3.5 py-2.5 text-[12px] text-[#6b6b73]">
            <Icon name="alert" size={13} className="shrink-0" />
            Under-replicated partitions 감지 — 브로커 장애 시 데이터 유실 위험
          </div>
        )}
      </Panel>

      {/* EDA 지표: Debezium MilliSecondsBehindSource(소스 지연) 추이 */}
      <Panel title="Source Delay"
        right={<RangeSelector value={delayRangeMin} onChange={setDelayRangeMin} />}>
        {!sourceDelayWindow.hasData ? (
          <div className="px-5 py-8 text-center text-[12.5px] text-gray-400">
            지표 없음 — 선택한 기간 동안 흐른 데이터가 없습니다 (Prometheus 미연동이거나 idle)
          </div>
        ) : (
          <div className="px-5 pb-4 pt-2">
            <TrendChart
              data={sourceDelayData}
              series={[{ key: 'delay', label: '소스 지연 (ms)', color: CHART_COLORS.violet }]}
              height={160}
              timeAxis
              xDomain={sourceDelayWindow.domain}
              showDots
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
  const axis = { fontSize: 10, fill: '#9a9a9a' }
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
            임계값 <span className={cn('font-semibold', lagThresholdError ? 'text-[#c0392b]' : 'text-[#6b6b73]')}>
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
                  <CartesianGrid strokeDasharray="3 3" stroke="#f1f1f1" vertical={false} />
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
                        : lagTone(g.totalLag) === 'warning' ? 'text-[#6b6b73]' : 'text-gray-700')}>
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
                                          ? 'text-[#6b6b73]'
                                          : lag > 0 ? 'text-gray-700' : 'text-[#8a8a8a]')}>
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
    case 'RUNNING':    return 'bg-[#ededed] text-[#6b6b73]'
    case 'FAILED':     return 'bg-[#fcf3f2] text-[#c0392b]'
    case 'PAUSED':     return 'bg-[#ededed] text-[#6b6b73]'
    default:           return 'bg-gray-100 text-gray-500'   // UNASSIGNED / null(대기)
  }
}

function ConnectorCard({ c, topic }: { c: ConnectorInfo; topic: string }) {
  const isSource  = c.kind === 'source'
  const kindColor = isSource ? 'border-[#ececec] bg-[#ededed]' : 'border-[#ececec] bg-[#ededed]'
  const kindText  = isSource ? 'text-[#6b6b73]' : 'text-[#6b6b73]'

  return (
    <div className={cn('rounded-xl border-2', kindColor)}>
      {/* ── connector header ─────────────────────── */}
      <div className={cn('flex items-center gap-3 border-b px-5 py-3', kindColor)}>
        <div className={cn('flex h-7 w-7 items-center justify-center rounded-lg',
          isSource ? 'bg-[#ededed]' : 'bg-[#ededed]')}>
          <Icon name={isSource ? 'database' : 'layers'} size={14} className={kindText} />
        </div>
        <div className="min-w-0 flex-1">
          <div className="truncate text-[13px] font-bold text-gray-900">{c.name}</div>
          <div className="truncate font-mono text-[11px] text-gray-500">{c.connectorClass}</div>
        </div>
        <span className={cn('rounded-full px-2.5 py-0.5 text-[10.5px] font-bold uppercase', kindText,
          isSource ? 'bg-[#ededed]' : 'bg-[#ededed]')}>
          {c.kind}
        </span>
      </div>

      <div className="space-y-3 rounded-b-xl bg-white p-4">
        {c.lastError && (
          <div className="flex items-start gap-2 rounded-lg border border-[#c0392b] bg-[#fcf3f2] px-3.5 py-2.5 text-[#c0392b]">
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
        <Icon name="alert" size={24} className="mb-2 text-[#c0392b]" />
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
  const traceId = app.selectedTraceId ?? undefined
  const toast = useToast()
  const [trace, setTrace] = useState<TraceSummaryResponse | null>(null)
  const [tracingEnabled, setTracingEnabled] = useState<boolean | null>(null)
  const [error, setError] = useState(false)
  const [busy, setBusy] = useState(false)
  const [pending, setPending] = useState<'on' | 'off' | null>(null)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    if (!wsId) return
    let cancelled = false
    setTrace(null)
    setError(false)
    Promise.all([
      api.pipelineTrace(wsId, edge.id, traceId),
      api.pipelineDataplaneTracing(wsId, edge.id).catch(() => ({ enabled: false })),
    ])
      .then(([t, dt]) => { if (!cancelled) { setTrace(t); setTracingEnabled(dt.enabled) } })
      .catch(() => { if (!cancelled) setError(true) })
    return () => { cancelled = true }
  }, [wsId, edge.id, traceId, reloadKey])

  const refresh = () => setReloadKey((k) => k + 1)

  // 토글은 소스 커넥터를 재시작하므로 확인 모달을 거친다. 실패 시 optimistic 상태를 롤백한다.
  const applyToggle = async () => {
    if (!wsId || tracingEnabled === null) return
    const next = !tracingEnabled
    const prev = tracingEnabled
    setConfirmOpen(false)
    setPending(next ? 'on' : 'off')
    setBusy(true)
    setTracingEnabled(next)
    try {
      await api.setPipelineDataplaneTracing(wsId, edge.id, next)
      toast(next ? '데이터플레인 추적을 켰습니다' : '데이터플레인 추적을 껐습니다', next ? 'success' : 'info')
      refresh()
    } catch (e) {
      setTracingEnabled(prev)
      toast(e instanceof Error ? e.message : '추적 설정 변경에 실패했습니다', 'error')
    } finally {
      setBusy(false)
      setPending(null)
    }
  }

  const hasTrace = trace?.traceId != null && trace.spans.length > 0
  const btn = 'rounded-md border border-gray-200 px-3 py-1.5 text-[12px] font-medium text-gray-600 hover:bg-gray-50 disabled:opacity-50'
  const primaryBtn = 'rounded-md bg-brand-600 px-3.5 py-1.5 text-[12px] font-semibold text-white hover:bg-brand-700 disabled:opacity-50'

  return (
    <div className="space-y-4">
      {/* 헤더: 토글 스위치 + 상태 + 재시작·샘플링 안내(상시 노출) */}
      <div className="space-y-1.5">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2.5">
            <TraceToggle
              on={tracingEnabled === true}
              disabled={busy || tracingEnabled === null}
              onClick={() => setConfirmOpen(true)}
            />
            <span className="text-[13px] font-semibold text-gray-800">데이터플레인 추적</span>
            {tracingEnabled === null ? (
              <span className="text-[11px] text-gray-300">…</span>
            ) : tracingEnabled ? (
              <span className="text-[11px] font-semibold text-[#6b6b73]">ON</span>
            ) : (
              <span className="text-[11px] font-medium text-gray-400">OFF</span>
            )}
          </div>
          <button onClick={refresh} disabled={busy} className={btn}>새로고침</button>
        </div>
        <p className="text-[11.5px] text-gray-400">
          켜기/끄기 시 소스 커넥터가 잠깐 재시작됩니다 (수십 초) · dataplane 추적은 전량 수집됩니다.
          {' '}표시된 trace는 <b className="font-medium text-gray-500">실시간이 아닙니다</b> — 최신 trace를 보려면 <b className="font-medium text-gray-500">새로고침</b>하세요.
        </p>
      </div>

      {edge.pattern === 'fan-out' && (
        <p className="text-[12px] text-gray-400">
          EDA는 source→topic 구간까지 표시됩니다. consumer(고객 앱)는 같은 traceId로 고객 관측도구에서 이어볼 수 있습니다.
        </p>
      )}

      {busy ? (
        <TraceState
          icon={<Spinner />}
          text={pending === 'off' ? '추적을 끄는 중…' : '추적을 켜는 중…'}
          sub="소스 커넥터 재시작 중 (수십 초)"
        />
      ) : tracingEnabled === null ? (
        <TraceState icon={<Spinner />} text="불러오는 중…" />
      ) : tracingEnabled === false ? (
        <TraceState
          icon={<span className="text-[20px] leading-none text-gray-300">◯</span>}
          text="이 파이프라인은 데이터플레인 추적이 꺼져 있습니다"
          sub="추적을 켜면 source→sink 흐름이 trace로 기록됩니다."
          action={<button onClick={() => setConfirmOpen(true)} className={primaryBtn}>추적 켜기</button>}
        />
      ) : error ? (
        <TraceState
          tone="error"
          icon={<Icon name="alert" size={20} className="text-[#c0392b]" />}
          text="추적 정보를 불러오지 못했습니다"
          action={<button onClick={refresh} className={btn}>다시 시도</button>}
        />
      ) : trace === null ? (
        <TraceState icon={<Spinner />} text="불러오는 중…" />
      ) : !hasTrace ? (
        <TraceState
          icon={<TraceWaitDots />}
          text="trace 대기 중"
          sub={trace.note ?? '켜져 있고 최근 샘플 trace를 기다리는 중입니다. 약 5% 샘플링이라 일부 레코드만 기록됩니다.'}
          action={<button onClick={refresh} className={btn}>새로고침</button>}
        />
      ) : (
        <TraceFlow trace={trace} />
      )}

      <Modal
        open={confirmOpen}
        onClose={() => setConfirmOpen(false)}
        title={tracingEnabled ? '데이터플레인 추적을 끌까요?' : '데이터플레인 추적을 켤까요?'}
        subtitle="소스 커넥터가 잠깐 재시작됩니다 (수십 초)."
        footer={
          <>
            <button onClick={() => setConfirmOpen(false)} className={btn}>취소</button>
            <button onClick={applyToggle} className={primaryBtn}>{tracingEnabled ? '끄기' : '켜기'}</button>
          </>
        }
      >
        <p className="text-[13px] text-gray-600">
          {tracingEnabled
            ? '추적을 끄면 이후 변경 이벤트의 trace가 더 이상 기록되지 않습니다.'
            : '추적을 켜면 변경 이벤트마다 약 5% 샘플링으로 trace가 기록됩니다.'}
        </p>
      </Modal>
    </div>
  )
}

function TraceToggle({ on, disabled, onClick }: { on: boolean; disabled: boolean; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      aria-pressed={on}
      className={cn(
        'relative inline-flex h-[22px] w-[38px] shrink-0 items-center rounded-full transition-colors disabled:opacity-50',
        on ? 'bg-[#0d0d0d]' : 'bg-gray-300',
      )}
    >
      <span
        className={cn(
          'inline-block h-[18px] w-[18px] rounded-full bg-white shadow transition-transform',
          on ? 'translate-x-[18px]' : 'translate-x-[2px]',
        )}
      />
    </button>
  )
}

function Spinner() {
  return <span className="bifrost-spin inline-block h-5 w-5 rounded-full border-[2.5px] border-gray-200 border-t-gray-500" />
}

function TraceWaitDots() {
  return (
    <span className="flex items-center gap-1">
      {[0, 0.15, 0.3].map((d) => (
        <span key={d} className="bifrost-dot h-1.5 w-1.5 rounded-full bg-[#c8c8c8]" style={{ animationDelay: `${d}s` }} />
      ))}
    </span>
  )
}

function TraceState({
  icon,
  text,
  sub,
  action,
  tone,
}: {
  icon: ReactNode
  text: string
  sub?: string
  action?: ReactNode
  tone?: 'error'
}) {
  return (
    <div
      className={cn(
        'flex flex-col items-center justify-center rounded-xl border bg-white px-6 py-16 text-center',
        tone === 'error' ? 'border-gray-200 border-l-[3px] border-l-[#c0392b]' : 'border-dashed border-gray-200',
      )}
    >
      <div className="mb-2 flex h-6 items-center justify-center">{icon}</div>
      <p className="text-[13px] text-gray-600">{text}</p>
      {sub && <p className="mt-1 max-w-md text-[12px] text-gray-400">{sub}</p>}
      {action && <div className="mt-3">{action}</div>}
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
  const [eventDistErr, setEventDistErr] = useState(false)
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
      api.pipelineEventDist(wsId, edge.id, rangeMin)
        .then((d) => {
          if (!cancelled) {
            setEventSeries(d)
            setEventDistErr(false)
          }
        })
        .catch(() => {
          if (!cancelled) {
            setEventSeries([])
            setEventDistErr(true)
          }
        })
    }
    load()
    const timer = setInterval(load, 5000)
    return () => { cancelled = true; clearInterval(timer) }
  }, [wsId, edge.id, rangeMin])

  const hhmm = (ts: number) => new Date(ts).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })
  const rangeLabel = rangeMin >= 60 ? `최근 ${rangeMin / 60}시간` : `최근 ${rangeMin}분`

  const tableName  = edge.table ? `${edge.table.schema}.${edge.table.name}` : '—'
  // (#501) 완료 판정 = 컨슈머 lag + sink 커넥터 health + 행수 일치.
  // lag<0 = sink 미소비(준비중), lag>0 = 처리중, sinkFailed = 오류.
  // (#501 보완) lag=0이라도 sink DB 행수가 source와 다르면 "완료" 아님 — lag=0은 "Kafka를 다
  // 소비"했다는 뜻이지 "sink에 다 반영됐다"는 보장이 아니다(에러 드롭·sink 대상 불일치 등).
  const lag        = sync?.lag ?? -1
  const endOffset  = sync?.endOffset ?? -1
  const sinkFailed = sync?.sinkFailed ?? false
  const sourceRows = sync?.sourceRows ?? -1
  const sinkRows   = sync?.sinkRows ?? -1
  const delta      = sync?.delta ?? -1
  const rowsKnown    = sourceRows >= 0 && sinkRows >= 0     // 양쪽 행수 조회 성공 시에만 비교
  const rowsConfirmed = rowsKnown && delta === 0           // 행수가 확정적으로 일치
  const rowsMatch    = !rowsKnown || delta === 0           // 행수 모르면 lag만으로 판단
  // 완료: lag로 따라잡았고 행수 OK, 또는 lag 미상(-1)이지만 행수가 확정 일치.
  // 후자는 Kafka Connect가 sink DB엔 다 썼지만 consumer offset 커밋(offset.flush.interval.ms,
  // 기본 60s) 전이라 lag을 못 읽는 구간 — 데이터는 맞으므로 "준비중"이 아니라 "완료"로 본다.
  const isHealthy  = !sinkFailed && ((lag === 0 && rowsMatch) || (lag < 0 && rowsConfirmed))
  // 행수 불일치: 따라잡았는데(lag=0) sink 행수가 source와 다름.
  const rowMismatch = !sinkFailed && lag === 0 && rowsKnown && delta !== 0
  // (그 외 lag<0 && 행수 미확정 = "sink 준비중" — 상태 텍스트 fallback에서 처리)
  // % : 완료 100, 처리중 caught-up((end−lag)/end), 그 외(불일치/준비중)는 행수 비율, 모르면 0.
  const syncPct    = isHealthy ? 100
    : lag > 0 ? (endOffset > 0 ? Math.max(0, Math.min(99, ((endOffset - lag) / endOffset) * 100)) : 0)
    : rowsKnown && sourceRows > 0 ? Math.min(99, (sinkRows / sourceRows) * 100)
    : 0
  const pctColor   = sinkFailed ? 'text-[#c0392b]' : isHealthy ? 'text-[#6b6b73]' : 'text-[#6b6b73]'
  const barColor   = sinkFailed ? 'bg-[#c0392b]'   : isHealthy ? 'bg-[#c8c8c8]'  : 'bg-[#c8c8c8]'

  // 실데이터(Prometheus)만 사용. 비어있으면 빈 차트(더미 위장 금지, #175).
  // 소스지연은 Debezium이 전달할 데이터가 없을 때(idle) -1을 준다. 이때는 "지연 0"이 아니라
  // 측정값이 없는 것이므로 null로 두어 그래프를 끊는다(Prometheus처럼). 0으로 클램프하면 거짓 0.
  // t = epoch ms → 실제 시간축(scale=time)으로 배치. 데이터 없는 구간은 빈 공간(균등 시각 눈금).
  // 백엔드가 빈 응답을 주면 차트를 합성하지 않고 "데이터 없음"으로 표시한다.
  // 실데이터가 있는 경우에만 중간 빈 버킷을 0으로 채워 균등 간격 연속 막대(Grafana 스타일)로 만든다.
  const eventDist   = useMemo(() => {
    const stepMs = Math.max(60, rangeMin) * 1000 // 백엔드 evStep(분당 1버킷, 분 경계 고정)과 동일
    const pts    = [...eventSeries].sort((a, b) => a.timestamp - b.timestamp)
    if (pts.length === 0) return []
    const end    = pts[pts.length - 1].timestamp
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
  const axis = { fontSize: 10, fill: '#9a9a9a' }
  const eventDistEmptyText = eventDistErr ? '이벤트 분포 조회 실패' : '이벤트 데이터 없음'
  const eventDistEmptySub = eventDistErr
    ? 'Prometheus 또는 백엔드 조회를 완료하지 못했습니다.'
    : '선택한 기간에 표시할 이벤트 분포 데이터가 없습니다.'

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
                  : sinkFailed ? 'text-[#c0392b]' : isHealthy ? 'text-[#6b6b73]' : 'text-[#6b6b73]')}>
                {sync === null
                  ? '불러오는 중…'
                  : sinkFailed
                    ? '동기화 오류'
                    : lag > 0
                      ? `${formatNum(lag)}건 처리중`
                      : rowMismatch
                        ? `행수 불일치 (Δ ${formatNum(delta)})`
                        : isHealthy
                          ? '동기화 완료'
                          : 'sink 준비중'}
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
        {eventDist.length === 0 ? (
          <div className="px-5 py-8 text-center">
            <p className="text-[12.5px] text-gray-500">{eventDistEmptyText}</p>
            <p className="mt-1 text-[11.5px] text-gray-400">{eventDistEmptySub}</p>
          </div>
        ) : (
          <div className="px-4 py-3">
          <ResponsiveChart width="100%" height={140}>
            <BarChart data={eventDist} maxBarSize={22} barCategoryGap="8%" margin={{ top: 4, right: 8, left: -28, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#f1f1f1" vertical={false} />
              <XAxis dataKey="t" tickFormatter={hhmm} interval={tickEvery}
                     tick={axis} tickLine={false} axisLine={false} />
              <YAxis tick={axis} tickLine={false} axisLine={false} allowDecimals={false} />
              <Tooltip contentStyle={tooltipStyle} labelFormatter={(v) => hhmm(Number(v))} />
              <Bar dataKey="count" fill={CHART_COLORS.brand} name="전송 레코드" radius={[2, 2, 0, 0]} />
            </BarChart>
          </ResponsiveChart>
          </div>
        )}
      </Panel>

      <Panel title="이벤트 타입 분포" right={<span className="text-[12px] text-gray-400">{rangeLabel}</span>}>
        {eventDist.length === 0 ? (
          <div className="px-5 py-8 text-center">
            <p className="text-[12.5px] text-gray-500">{eventDistEmptyText}</p>
            <p className="mt-1 text-[11.5px] text-gray-400">{eventDistEmptySub}</p>
          </div>
        ) : (
          <div className="px-4 py-3">
          <ResponsiveChart width="100%" height={140}>
            <BarChart data={eventDist} maxBarSize={22} barCategoryGap="8%" margin={{ top: 4, right: 8, left: -28, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#f1f1f1" vertical={false} />
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
        )}
      </Panel>
    </div>
  )
}

function DBNodeCard({ node, role }: { node: Node | null; role: 'Source' | 'Sink' }) {
  // (#547) sink는 readiness 차단(BLOCKED)을 error 대신 warning으로(연결 끊김만 error).
  const effStatus = node && role === 'Sink' ? sinkDisplayStatus(node) : node?.status
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
        role === 'Source' ? 'bg-[#ededed] text-[#6b6b73]' : 'bg-[#ededed] text-[#6b6b73]',
      )}>
        {role}
      </span>
      <div className="flex items-center gap-1.5 text-[11px]">
        <span className={cn('h-1.5 w-1.5 rounded-full',
          effStatus === 'healthy' ? 'bg-[#c8c8c8]' : effStatus === 'error' ? 'bg-[#c0392b]' : 'bg-[#c8c8c8]')} />
        <span className="text-gray-500">{effStatus}</span>
      </div>
    </div>
  )
}

/* ---------------------------------------------------------------- Messages tab */

const OP_META: Record<string, { label: string; cls: string }> = {
  c: { label: 'INSERT', cls: 'bg-[#ededed] text-[#6b6b73] border-[#ececec]' },
  u: { label: 'UPDATE', cls: 'bg-[#ededed]  text-[#6b6b73]  border-[#ececec]'  },
  d: { label: 'DELETE', cls: 'bg-[#fcf3f2]   text-[#c0392b]   border-[#c0392b]'   },
  r: { label: 'READ',   cls: 'bg-[#ededed]    text-[#6b6b73]    border-[#ececec]'    },
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

  // (#509) 단일 파티션 선택 시 오프셋 페이징 모드. pageStart=null → 해당 파티션 최신 N.
  const PAGE_SIZE = 50
  const pageMode = partition !== 'all'
  const [page, setPage] = useState<MessagePageResponse | null>(null)
  const [pageStart, setPageStart] = useState<number | null>(null)
  const [pageLoading, setPageLoading] = useState(false)

  // 파티션을 바꾸면 페이지를 최신으로 리셋
  useEffect(() => { setPageStart(null); setSelected(null) }, [partition])

  // 페이지 모드: partition/pageStart 변경마다 결정적으로 해당 윈도우 조회
  useEffect(() => {
    if (!wsId || !pageMode) { setPage(null); return }
    let cancelled = false
    setPageLoading(true)
    api.pipelineMessagePage(wsId, edge.id, partition as number, pageStart, PAGE_SIZE)
      .then((r) => { if (!cancelled) setPage(r) })
      .catch(() => { if (!cancelled) setPage(null) })
      .finally(() => { if (!cancelled) setPageLoading(false) })
    return () => { cancelled = true }
  }, [wsId, edge.id, pageMode, partition, pageStart])

  // live 모드: 3초마다 새로고침 (전체 뷰에서만 — 페이징 모드는 결정적 윈도우라 자동갱신 안 함)
  useEffect(() => {
    if (!live || !wsId || pageMode) return
    const id = setInterval(() => {
      api.pipelineMessages(wsId, edge.id, 50)
        .then((m) => setMsgs(m))
        .catch(() => {})
    }, 3000)
    return () => clearInterval(id)
  }, [live, wsId, edge.id, pageMode])

  // 표시 소스: 전체 뷰는 최근 N 머지(msgs), 파티션 뷰는 페이지 레코드
  const sourceMsgs = pageMode ? (page?.records ?? []) : msgs
  const visible = sourceMsgs.filter((m) => {
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

  const selectedMsg = selected !== null ? sourceMsgs.find((m) => m.offset === selected) ?? null : null

  // 페이징 핸들러(과거/최신 방향). startOffset 기준으로 한 페이지씩 이동.
  function goOlder() {
    if (!page) return
    setSelected(null)
    setPageStart(Math.max(page.beginOffset, page.startOffset - PAGE_SIZE))
  }
  function goNewer() {
    if (!page) return
    setSelected(null)
    const next = page.startOffset + PAGE_SIZE
    // 마지막 페이지를 넘어서면 최신(null)로
    setPageStart(next + PAGE_SIZE >= page.endOffset ? null : next)
  }
  function goLatest() { setSelected(null); setPageStart(null) }

  function buildRawEnvelope(m: KafkaMessageRecord) {
    return {
      op: m.op,
      ts_ms: m.tsMs,
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

        {/* live toggle — 페이징(특정 파티션) 모드에선 결정적 윈도우라 비활성 */}
        <button
          onClick={() => setLive((v) => !v)}
          disabled={pageMode}
          title={pageMode ? '파티션 페이징 모드에서는 Live가 비활성화됩니다' : undefined}
          className={cn(
            'flex items-center gap-1.5 rounded border px-2.5 py-1 text-[11.5px] font-medium transition-colors',
            pageMode ? 'cursor-not-allowed border-gray-200 text-gray-300'
              : live ? 'border-[#c0392b] bg-[#fcf3f2] text-[#c0392b]' : 'border-gray-200 text-gray-500 hover:bg-gray-50',
          )}
        >
          <span className={cn('h-1.5 w-1.5 rounded-full',
            pageMode ? 'bg-gray-300' : live ? 'animate-pulse bg-[#c0392b]' : 'bg-gray-300')} />
          Live
        </button>

        <span className="ml-auto text-[11px] text-gray-400">
          {pageMode && page
            ? `P${partition} · offset ${formatNum(page.startOffset)}–${formatNum(Math.max(page.startOffset, page.startOffset + visible.length - 1))} / end ${formatNum(page.endOffset)}`
            : `${visible.length}개 메시지`}
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
          {(pageMode ? pageLoading : msgLoading) ? (
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
                  <span className="font-mono text-[11px] text-gray-400">{formatMessageSize(messageSizeBytes(m))}</span>
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
                        <div className="mb-2 text-[11px] text-gray-400">source 메타데이터는 API 응답에 없어 표시하지 않습니다.</div>
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

        {/* (#509) 페이징 컨트롤 — 특정 파티션 선택 시. 과거~최신 전체를 페이지 단위로 열람 */}
        {pageMode && page && (
          <div className="flex items-center gap-2 border-t border-gray-100 bg-gray-50/60 px-4 py-2">
            <button
              onClick={goOlder}
              disabled={!page.hasOlder || pageLoading}
              className={cn('flex items-center gap-1 rounded border px-2.5 py-1 text-[11.5px] font-medium transition-colors',
                page.hasOlder && !pageLoading ? 'border-gray-200 text-gray-600 hover:bg-white' : 'cursor-not-allowed border-gray-100 text-gray-300')}
            >
              <Icon name="chevron-left" size={12} />과거
            </button>
            <button
              onClick={goNewer}
              disabled={!page.hasNewer || pageLoading}
              className={cn('flex items-center gap-1 rounded border px-2.5 py-1 text-[11.5px] font-medium transition-colors',
                page.hasNewer && !pageLoading ? 'border-gray-200 text-gray-600 hover:bg-white' : 'cursor-not-allowed border-gray-100 text-gray-300')}
            >
              최신<Icon name="chevron-right" size={12} />
            </button>
            <button
              onClick={goLatest}
              disabled={!page.hasNewer || pageLoading}
              className={cn('rounded border px-2.5 py-1 text-[11.5px] font-medium transition-colors',
                page.hasNewer && !pageLoading ? 'border-gray-200 text-gray-600 hover:bg-white' : 'cursor-not-allowed border-gray-100 text-gray-300')}
            >
              맨 끝(최신)
            </button>
            <span className="ml-auto font-mono text-[11px] text-gray-400">
              begin {formatNum(page.beginOffset)} · end {formatNum(page.endOffset)}
            </span>
          </div>
        )}
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
    return <Panel title="Connection details"><div className="px-4 py-10 text-center text-[13px] text-[#c0392b]">{error}</div></Panel>
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
  // 커넥터/토픽 헤더(#304 실연결) + 컬럼 단위 매핑(원복, #508). EDA(fan-out)는 sink 컬럼이 없다.
  const [mapping, setMapping] = useState<TableMappingResponse | null>(null)
  const [cols, setCols] = useState<SchemaColumn[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [colError, setColError] = useState(false)

  const isDirect = edge.pattern === 'direct'

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

  // 컬럼 정보는 source DB 실 스키마에서(추가 백엔드 없이 databaseSchema 재사용).
  useEffect(() => {
    if (!wsId) return
    let cancelled = false
    setCols(null)
    setColError(false)
    api
      .databaseSchema(wsId, edge.source)
      .then((res) => {
        if (cancelled) return
        const t = res.tables.find(
          (tb) => tb.name === edge.table?.name && (!edge.table?.schema || tb.schema === edge.table?.schema),
        )
        setCols(t?.columns ?? [])
      })
      .catch(() => { if (!cancelled) setColError(true) })
    return () => { cancelled = true }
  }, [wsId, edge.source, edge.table?.name, edge.table?.schema])

  const titleTable = edge.table ? `${edge.table.schema}.${edge.table.name}` : 'pipeline'
  const topicName = mapping?.mappings?.[0]?.kafkaTopic || edge.topic || '—'
  const sinkTable = mapping?.mappings?.[0]?.sinkTable || edge.table?.name || '—'

  return (
    <Panel title={`Table mapping · ${titleTable}`}>
      {!wsId ? (
        <div className="px-4 py-10 text-center text-[13px] text-gray-400">워크스페이스를 먼저 선택하세요.</div>
      ) : error ? (
        <div className="px-4 py-10 text-center text-[13px] text-[#c0392b]">{error}</div>
      ) : mapping === null ? (
        <div className="px-4 py-10 text-center text-[13px] text-gray-400">불러오는 중…</div>
      ) : (
        <>
          {/* 커넥터/토픽 헤더(실데이터) */}
          <div className="grid gap-2 border-b border-gray-100 px-4 py-3 text-[12px] sm:grid-cols-2 lg:grid-cols-4">
            <div>
              <span className="text-gray-400">Source connector</span>
              <div className="mt-0.5 font-mono text-gray-700">{mapping.sourceConnector || '—'}</div>
            </div>
            <div>
              <span className="text-gray-400">Kafka topic</span>
              <div className="mt-0.5 truncate font-mono text-gray-600" title={topicName}>{topicName}</div>
            </div>
            <div>
              <span className="text-gray-400">Sink connector</span>
              <div className="mt-0.5 font-mono text-gray-700">{isDirect ? (mapping.sinkConnector || '—') : '— (fan-out)'}</div>
            </div>
            <div>
              <span className="text-gray-400">Sink table</span>
              <div className="mt-0.5 font-mono text-gray-500">{isDirect ? sinkTable : '—'}</div>
            </div>
          </div>

          {/* 컬럼 단위 매핑(원복) — 어떤 컬럼이 동기화되는지 */}
          {colError ? (
            <div className="px-4 py-10 text-center text-[13px] text-gray-400">스키마를 불러오지 못했습니다</div>
          ) : cols === null ? (
            <div className="px-4 py-10 text-center text-[13px] text-gray-400">컬럼 정보를 불러오는 중…</div>
          ) : cols.length === 0 ? (
            <div className="px-4 py-10 text-center text-[13px] text-gray-400">컬럼 정보를 찾을 수 없습니다</div>
          ) : (
            <table className="w-full text-[12.5px]">
              <thead>
                <tr className="border-b border-gray-100 text-left text-[11px] uppercase tracking-wide text-gray-400">
                  <th className="px-4 py-2 font-semibold">Source column</th>
                  <th className="px-4 py-2 font-semibold">{isDirect ? 'Sink column' : 'Kafka field'}</th>
                  <th className="px-4 py-2 font-semibold">Type</th>
                  <th className="px-4 py-2 font-semibold">Flags</th>
                </tr>
              </thead>
              <tbody>
                {cols.map((c) => (
                  <tr key={c.name} className="border-b border-gray-50">
                    <td className="px-4 py-2.5 font-mono font-medium text-gray-800">{c.name}</td>
                    {/* CDC(direct)는 동일 컬럼명으로 복제 → 대상 컬럼명 동일. EDA는 토픽 필드명. */}
                    <td className="px-4 py-2.5 font-mono text-gray-600">{c.name}</td>
                    <td className="px-4 py-2.5 font-mono text-gray-500">{c.type}</td>
                    <td className="px-4 py-2.5">
                      <div className="flex gap-1.5">
                        {c.primaryKey && <span className="rounded bg-[#ededed] px-1.5 py-0.5 text-[10px] font-semibold text-[#6b6b73]">PK</span>}
                        {!c.nullable && <span className="rounded bg-gray-100 px-1.5 py-0.5 text-[10px] font-semibold text-gray-500">NOT NULL</span>}
                        {c.indexed && <span className="rounded bg-[#ededed] px-1.5 py-0.5 text-[10px] font-semibold text-[#6b6b73]">INDEX</span>}
                      </div>
                    </td>
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

  const axis = { fontSize: 10, fill: '#9a9a9a' }
  const chartH = Math.max(140, partitions.length * 28)

  return (
    <div className="grid grid-cols-[1fr_200px] gap-0 border-b border-gray-100 bg-gray-50/40">
      {/* partition balance bar chart */}
      <div className="border-r border-gray-100 px-4 py-3">
        <div className="mb-1.5 flex items-center gap-2">
          <span className="text-[11px] font-semibold uppercase tracking-wide text-gray-400">Partition Balance</span>
          {isSkewed && (
            <span className="flex items-center gap-1 rounded-full border border-[#ececec] bg-[#ededed] px-2 py-0.5 text-[10px] font-semibold text-[#6b6b73]">
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
              ? <span className="font-medium text-[#6b6b73]">균등 분배</span>
              : <span className="font-medium text-[#6b6b73]">불균등 분배</span>}
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
        tone === 'warn' ? 'text-[#6b6b73]' : tone === 'good' ? 'text-[#6b6b73]' : 'text-gray-900')}>
        {value}
      </div>
    </div>
  )
}

function Kv({ label, value, tone }: { label: string; value: string; tone?: 'warn' }) {
  return (
    <div className="flex items-center gap-1.5 text-[12.5px]">
      <span className="text-gray-400">{label}</span>
      <span className={cn('font-semibold', tone === 'warn' ? 'text-[#6b6b73]' : 'text-gray-700')}>{value}</span>
    </div>
  )
}
