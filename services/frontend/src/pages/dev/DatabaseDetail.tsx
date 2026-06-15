import { useCallback, useEffect, useRef, useState } from 'react'
import { Icon } from '../../components/Icon'
import { TechIcon, nodeKind } from '../../components/TechIcon'
import { MetricCard, Panel, StatusBadge } from '../../components/blocks'
import { useToast } from '../../components/Toast'
import { useApp } from '../../store/AppStore'
import { dbPipelines, nodeName, pipelineLabel } from '../../data/helpers'
import type { Node } from '../../data/types'
import { cn } from '../../lib/format'
import { api, type CdcCheck, type CdcReadinessResponse, type DatabaseMetricsResponse, type SchemaTable } from '../../lib/api'

interface ResourceState<T> {
  data: T | null
  loading: boolean
  error: string | null
  loaded: boolean
}

export function DatabaseDetail() {
  const app = useApp()
  const node = app.nodes.find((n) => n.id === app.selectedDatabaseId)
  if (!node) return <div className="px-6 py-10 text-sm text-gray-500">Database not found.</div>
  return <DatabaseDetailContent key={`${app.currentProject?.id ?? 'none'}:${node.id}`} node={node} />
}

function DatabaseDetailContent({ node }: { node: Node }) {
  const app = useApp()
  const toast = useToast()
  const [tab, setTab] = useState('Overview')
  const [rescanning, setRescanning] = useState(false)
  const tabs = ['Overview', 'Schema', '연결 검사', 'Metrics', 'Pipelines']

  const pipelines = dbPipelines(node.id, app.edges)
  const wsId = app.currentProject?.id ?? null
  const readiness = useDatabaseReadiness(wsId, node.id)
  const schema = useDatabaseSchema(wsId, node.id)
  const metrics = useDatabaseMetrics(wsId, node.id)
  const liveMetrics = liveDatabaseMetrics(metrics.data)
  const displayStatus = databaseStatusFromReadinessResources(node, readiness.source, readiness.sink)

  const handleRescan = async () => {
    if (!wsId) {
      toast('워크스페이스를 먼저 선택하세요.')
      return
    }
    setRescanning(true)
    try {
      let failed = (await rescanDatabaseDetailResources([
        readiness.reload,
        schema.reload,
        metrics.reload,
      ])).failed
      try {
        await app.refreshDatabaseNode(node.id)
      } catch {
        failed = true
      }
      toast(failed ? '재검사가 완료됐지만 일부 API를 불러오지 못했습니다.' : 'Database capabilities re-scanned.')
    } finally {
      setRescanning(false)
    }
  }

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
            <StatusBadge status={displayStatus} label={displayStatus === 'healthy' ? 'connected' : displayStatus} />
          </div>
          <div className="mt-0.5 font-mono text-[12px] text-gray-400">{node.host}</div>
        </div>
        <div className="flex-1" />
        <button
          onClick={handleRescan}
          disabled={rescanning}
          className="flex items-center gap-1.5 rounded-md border border-gray-300 px-2.5 py-1.5 text-[12.5px] font-medium text-gray-700 hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-60"
        >
          <Icon name="refresh" size={13} />
          {rescanning ? 'Scanning…' : 'Re-scan'}
        </button>
        <button
          onClick={async () => {
            if (pipelines.length > 0) {
              toast('파이프라인을 먼저 삭제하세요.')
              return
            }
            if (!confirm(`"${nodeName(node)}" DB를 삭제하시겠습니까?`)) return
            await app.deleteDatabase(node.id)
          }}
          className="flex items-center gap-1.5 rounded-md border border-[#c0392b] px-2.5 py-1.5 text-[12.5px] font-medium text-[#c0392b] hover:bg-[#fcf3f2]"
        >
          <Icon name="trash" size={13} />
          삭제
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
            <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-4">
              <MetricCard label="Status" value={<StatusBadge status={displayStatus} />} />
              <MetricCard
                label="TPS"
                value={metricValue(metrics, liveMetrics?.tps)}
                sub={metricSub(metrics, liveMetrics ? 'transactions/sec' : undefined)}
              />
              <MetricCard
                label="Query response"
                value={metricValue(metrics, liveMetrics?.queryResponseMs)}
                sub={liveMetrics ? 'ms avg' : metricSub(metrics)}
                tone={liveMetrics && liveMetrics.queryResponseMs > 1000 ? 'warn' : 'default'}
              />
              <MetricCard
                label="Tables"
                value={schemaTableValue(schema)}
                sub={schemaTableSub(schema)}
              />
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
                          isEda ? 'bg-[#ededed] text-[#6b6b73]' : 'bg-[#ededed] text-[#6b6b73]',
                        )}>
                          {isEda ? 'EDA' : 'CDC'}
                        </span>
                        <span className={cn(
                          'shrink-0 rounded px-1.5 py-0.5 text-[9.5px] font-bold uppercase',
                          isSource ? 'bg-[#ededed] text-[#6b6b73]' : 'bg-[#ededed] text-[#6b6b73]',
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

        {tab === 'Schema' && <SchemaTab state={schema} />}

        {tab === '연결 검사' && (
          <ConnectionCheckTab state={readiness} />
        )}

        {tab === 'Metrics' && (
          <MetricsTab state={metrics} />
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
                      e.source === node.id ? 'bg-[#ededed] text-[#6b6b73]' : 'bg-[#ededed] text-[#6b6b73]',
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


type ReadinessPayload = {
  source: CdcReadinessResponse | null
  sink: CdcReadinessResponse | null
}

type ReadinessState = {
  source: ResourceState<CdcReadinessResponse>
  sink: ResourceState<CdcReadinessResponse>
  reload: () => Promise<ReadinessPayload>
}

type ReloadableResourceState<T> = ResourceState<T> & {
  reload: () => Promise<T>
}

type DatabaseReadinessClient = Pick<typeof api, 'cdcReadiness' | 'sinkReadiness'>

interface DatabaseReadinessLoadResult {
  source: Promise<CdcReadinessResponse>
  sink: Promise<CdcReadinessResponse | null>
}

function emptyResource<T>(): ResourceState<T> {
  return { data: null, loading: false, error: null, loaded: false }
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message.trim() ? error.message : fallback
}

export function cdcStatusToNodeStatus(
  status: CdcReadinessResponse['overallStatus'] | null | undefined,
): Node['status'] | null {
  if (status === 'OK') return 'healthy'
  if (status === 'WARNING') return 'warning'
  if (status === 'BLOCKED') return 'error'
  return null
}

export function databaseStatusFromReadiness(
  node: Pick<Node, 'status' | 'connectionStatus'>,
  readinessStatus: CdcReadinessResponse['overallStatus'] | null | undefined,
): Node['status'] {
  if (node.connectionStatus === 'UNREACHABLE') return 'error'
  return cdcStatusToNodeStatus(readinessStatus) ?? node.status
}

const NODE_STATUS_RANK: Record<Node['status'], number> = {
  healthy: 0,
  warning: 1,
  error: 2,
}

export function worstNodeStatus(statuses: Array<Node['status'] | null | undefined>): Node['status'] | null {
  return statuses.reduce<Node['status'] | null>((worst, status) => {
    if (!status) return worst
    if (!worst || NODE_STATUS_RANK[status] > NODE_STATUS_RANK[worst]) return status
    return worst
  }, null)
}

export function readinessResourceStatus(
  state: ResourceState<CdcReadinessResponse> | null | undefined,
): Node['status'] | null {
  if (!state) return null
  if (state.data) return cdcStatusToNodeStatus(state.data.overallStatus)
  if (state.error) return 'warning'
  return null
}

export function databaseStatusFromReadinessResources(
  node: Pick<Node, 'status' | 'connectionStatus'>,
  source: ResourceState<CdcReadinessResponse>,
  sink: ResourceState<CdcReadinessResponse> | null,
): Node['status'] {
  if (node.connectionStatus === 'UNREACHABLE') return 'error'
  return worstNodeStatus([
    readinessResourceStatus(source),
    readinessResourceStatus(sink),
  ]) ?? node.status
}

export function liveDatabaseMetrics(metrics: DatabaseMetricsResponse | null | undefined): DatabaseMetricsResponse | null {
  return metrics && !metrics.stub ? metrics : null
}

export function resourceFromSettled<T>(
  result: PromiseSettledResult<T>,
  fallbackMessage: string,
): ResourceState<T> {
  if (result.status === 'fulfilled') {
    return { data: result.value, loading: false, error: null, loaded: true }
  }
  return {
    data: null,
    loading: false,
    error: errorMessage(result.reason, fallbackMessage),
    loaded: true,
  }
}

export function loadDatabaseReadiness(
  client: DatabaseReadinessClient,
  wsId: string,
  dbId: string,
  includeSink: boolean,
): DatabaseReadinessLoadResult {
  return {
    source: client.cdcReadiness(wsId, dbId),
    sink: includeSink ? client.sinkReadiness(wsId, dbId) : Promise.resolve(null),
  }
}

export async function rescanDatabaseDetailResources(reloaders: Array<() => Promise<unknown>>) {
  const results = await Promise.allSettled(reloaders.map((reload) => reload()))
  return { failed: results.some((result) => result.status === 'rejected') }
}

function useDatabaseMetrics(wsId: string | null, dbId: string): ReloadableResourceState<DatabaseMetricsResponse> {
  const [state, setState] = useState<ResourceState<DatabaseMetricsResponse>>(() => emptyResource())
  const requestId = useRef(0)
  const reload = useCallback(async () => {
    const currentRequestId = ++requestId.current
    if (!wsId) {
      const error = new Error('워크스페이스가 선택되지 않았습니다.')
      setState({ data: null, loading: false, error: error.message, loaded: true })
      throw error
    }
    setState((prev) => ({ ...prev, loading: true, error: null }))
    try {
      const data = await api.databaseMetrics(wsId, dbId)
      if (requestId.current === currentRequestId) {
        setState({ data, loading: false, error: null, loaded: true })
      }
      return data
    } catch (error) {
      const message = errorMessage(error, 'DB metrics를 불러오지 못했습니다.')
      if (requestId.current === currentRequestId) {
        setState({ data: null, loading: false, error: message, loaded: true })
      }
      throw error instanceof Error ? error : new Error(message)
    }
  }, [wsId, dbId])

  useEffect(() => {
    void reload().catch(() => undefined)
  }, [reload])
  useEffect(() => () => {
    requestId.current += 1
  }, [])

  return { ...state, reload }
}

function useDatabaseSchema(wsId: string | null, dbId: string): ReloadableResourceState<{ tables: SchemaTable[] }> {
  const [state, setState] = useState<ResourceState<{ tables: SchemaTable[] }>>(() => emptyResource())
  const requestId = useRef(0)
  const reload = useCallback(async () => {
    const currentRequestId = ++requestId.current
    if (!wsId) {
      const error = new Error('워크스페이스가 선택되지 않았습니다.')
      setState({ data: null, loading: false, error: error.message, loaded: true })
      throw error
    }
    setState((prev) => ({ ...prev, loading: true, error: null }))
    try {
      const data = await api.databaseSchema(wsId, dbId)
      if (requestId.current === currentRequestId) {
        setState({ data, loading: false, error: null, loaded: true })
      }
      return data
    } catch (error) {
      const message = errorMessage(error, '스키마를 불러오지 못했습니다.')
      if (requestId.current === currentRequestId) {
        setState({ data: null, loading: false, error: message, loaded: true })
      }
      throw error instanceof Error ? error : new Error(message)
    }
  }, [wsId, dbId])

  useEffect(() => {
    void reload().catch(() => undefined)
  }, [reload])
  useEffect(() => () => {
    requestId.current += 1
  }, [])

  return { ...state, reload }
}

function useDatabaseReadiness(wsId: string | null, dbId: string): ReadinessState {
  const [source, setSource] = useState<ResourceState<CdcReadinessResponse>>(() => emptyResource())
  const [sink, setSink] = useState<ResourceState<CdcReadinessResponse>>(() => emptyResource())
  const requestId = useRef(0)
  const reload = useCallback(async () => {
    const currentRequestId = ++requestId.current
    if (!wsId) {
      const error = new Error('워크스페이스가 선택되지 않았습니다.')
      setSource({ data: null, loading: false, error: error.message, loaded: true })
      setSink({ data: null, loading: false, error: error.message, loaded: true })
      throw error
    }
    setSource((prev) => ({ ...prev, loading: true, error: null }))
    setSink((prev) => ({ ...prev, loading: true, error: null }))

    const { source: sourceRequest, sink: sinkRequest } = loadDatabaseReadiness(api, wsId, dbId, true)
    const sourceTracked = sourceRequest.then(
      (data) => {
        if (requestId.current === currentRequestId) {
          setSource({ data, loading: false, error: null, loaded: true })
        }
        return data
      },
      (error: unknown) => {
        if (requestId.current === currentRequestId) {
          setSource(resourceFromSettled({ status: 'rejected', reason: error }, 'Source 연결 검사를 불러오지 못했습니다.'))
        }
        throw error
      },
    )
    const sinkTracked = sinkRequest.then(
      (data) => {
        if (requestId.current === currentRequestId) {
          setSink(data ? { data, loading: false, error: null, loaded: true } : emptyResource())
        }
        return data
      },
      (error: unknown) => {
        if (requestId.current === currentRequestId) {
          setSink(resourceFromSettled({ status: 'rejected', reason: error }, 'Sink 연결 검사를 불러오지 못했습니다.'))
        }
        throw error
      },
    )

    const [sourceResult, sinkResult] = await Promise.allSettled([sourceTracked, sinkTracked])

    const data = {
      source: sourceResult.status === 'fulfilled' ? sourceResult.value : null,
      sink: sinkResult.status === 'fulfilled' ? sinkResult.value : null,
    }
    const failures = sourceResult.status === 'rejected' || sinkResult.status === 'rejected'
    if (failures) throw new Error('연결 검사 일부를 불러오지 못했습니다.')
    return data
  }, [wsId, dbId])

  useEffect(() => {
    void reload().catch(() => undefined)
  }, [reload])
  useEffect(() => () => {
    requestId.current += 1
  }, [])

  return { source, sink, reload }
}

function formatMetric(value: number) {
  if (!Number.isFinite(value)) return '—'
  return new Intl.NumberFormat('en-US', { maximumFractionDigits: value < 10 ? 2 : 1 }).format(value)
}

function metricValue(state: ResourceState<DatabaseMetricsResponse>, value: number | undefined) {
  if (state.loading && !state.loaded) return '…'
  return value == null ? '—' : formatMetric(value)
}

function metricSub(state: ResourceState<DatabaseMetricsResponse>, liveLabel?: string) {
  if (state.loading && !state.loaded) return '불러오는 중'
  if (state.error) return '불러오기 실패'
  if (state.data?.stub) return '수집된 지표 없음'
  return liveLabel
}

function schemaTableValue(state: ResourceState<{ tables: SchemaTable[] }>) {
  if (state.loading && !state.loaded) return '…'
  if (state.error || !state.data) return '—'
  return state.data.tables.length
}

function schemaTableSub(state: ResourceState<{ tables: SchemaTable[] }>) {
  if (state.loading && !state.loaded) return '스키마 불러오는 중'
  if (state.error) return '스키마 조회 실패'
  if (state.data) {
    const columnCount = state.data.tables.reduce((sum, table) => sum + table.columns.length, 0)
    return `${columnCount} columns`
  }
  return undefined
}

/* ---- 친화적 Capability Check 라벨 매핑: 표시명만 보정하고 실제 detail은 API 응답을 쓴다. ---- */
const FRIENDLY_LABELS: Record<string, string> = {
  'wal_level = logical': '변경 감지 활성화',
  'REPLICATION privilege': '연결 권한',
  'Replication slot capacity': '연결 슬롯 여유 공간',
  'REPLICA IDENTITY FULL': '변경 데이터 완전성',
  'binlog_format = ROW': '변경 감지 방식',
  'REPLICATION SLAVE privilege': '연결 권한',
  'GTID mode': '안전한 장애 복구',
  'binlog retention': '변경 이력 보존 기간',
}

function ReadinessSection({
  title,
  subtitle,
  readiness,
}: {
  title: string
  subtitle: string
  readiness: CdcReadinessResponse
}) {
  const [showDetail, setShowDetail] = useState(false)
  const tone = readinessTone(readiness.overallStatus)
  const blockedCount = readiness.checks.filter((c) => c.status === 'BLOCKED').length
  const warningCount = readiness.checks.filter((c) => c.status === 'WARNING').length

  return (
    <div className="space-y-2">
      <div
        className={cn(
          'flex items-center gap-3 rounded-xl border px-5 py-4',
          tone.box,
        )}
      >
        <Icon
          name={readiness.overallStatus === 'OK' ? 'check' : 'alert'}
          size={20}
          strokeWidth={readiness.overallStatus === 'OK' ? 3 : 2}
          className={tone.icon}
        />
        <div className="flex-1">
          <div className={cn('text-[14px] font-semibold', tone.title)}>
            {title}
          </div>
          <div className={cn('text-[12px]', tone.text)}>
            {readiness.overallStatus === 'OK'
              ? subtitle
              : blockedCount > 0
                ? `${blockedCount}개 항목 해결 필요`
                : `${warningCount}개 항목 확인 권장`}
          </div>
        </div>
        <ReadinessBadge status={readiness.overallStatus} />
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
          {readiness.checks.map((c, index) => {
            const friendly = FRIENDLY_LABELS[c.name] ?? c.name
            const checkTone = readinessTone(c.status)
            return (
              <div key={`${c.name}-${index}`} className="flex gap-3 px-4 py-3">
                <Icon
                  name={c.status === 'OK' ? 'check' : c.status === 'WARNING' ? 'alert' : 'x'}
                  size={14}
                  strokeWidth={c.status === 'OK' ? 3 : 2}
                  className={cn('mt-0.5 shrink-0', checkTone.icon)}
                />
                <div className="flex-1">
                  <div className="flex items-center gap-2">
                    <span className="text-[13px] font-medium text-gray-800">{friendly}</span>
                    <ReadinessBadge status={c.status} />
                  </div>
                  <div className="mt-1 text-[12px] text-gray-500">{readinessDetail(c)}</div>
                </div>
                <span className="mt-0.5 shrink-0 font-mono text-[10px] text-gray-300">{c.name}</span>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}

function readinessTone(status: CdcReadinessResponse['overallStatus']) {
  if (status === 'OK') {
    return {
      box: 'border-[#ececec] bg-[#ededed]',
      badge: 'bg-[#ededed]',
      dot: 'bg-[#c8c8c8]',
      icon: 'text-[#6b6b73]',
      title: 'text-[#6b6b73]',
      text: 'text-[#6b6b73]',
    }
  }
  if (status === 'WARNING') {
    return {
      box: 'border-[#ececec] bg-[#ededed]',
      badge: 'bg-[#ededed]',
      dot: 'bg-[#c8c8c8]',
      icon: 'text-[#6b6b73]',
      title: 'text-[#6b6b73]',
      text: 'text-[#6b6b73]',
    }
  }
  return {
    box: 'border-[#c0392b] bg-[#fcf3f2]',
    badge: 'bg-[#fcf3f2]',
    dot: 'bg-[#c0392b]',
    icon: 'text-[#c0392b]',
    title: 'text-[#c0392b]',
    text: 'text-[#c0392b]',
  }
}

function ReadinessBadge({ status }: { status: CdcReadinessResponse['overallStatus'] }) {
  const tone = readinessTone(status)
  return (
    <span className={cn(
      'inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-[10.5px] font-semibold uppercase tracking-wide',
      tone.badge,
      tone.text,
    )}>
      <span className={cn('h-1.5 w-1.5 rounded-full', tone.dot)} />
      {status}
    </span>
  )
}

function readinessDetail(check: CdcCheck) {
  const details: string[] = []
  if (check.hint?.trim()) details.push(check.hint.trim())
  const actual = check.actual?.trim()
  const expected = check.expected?.trim()
  if (actual || expected) details.push(`actual: ${actual || '—'} / expected: ${expected || '—'}`)
  return details.length > 0 ? details.join(' ') : 'API returned no detail.'
}

function ReadinessStateSection({
  title,
  subtitle,
  state,
}: {
  title: string
  subtitle: string
  state: ResourceState<CdcReadinessResponse>
}) {
  if (state.data) return <ReadinessSection title={title} subtitle={subtitle} readiness={state.data} />
  const message = state.loading && !state.loaded
    ? '연결 검사를 불러오는 중…'
    : state.error ?? '조회된 연결 검사 결과가 없습니다.'
  return (
    <div className="rounded-xl border border-gray-200 bg-white px-5 py-4">
      <div className="text-[14px] font-semibold text-gray-800">{title}</div>
      <div className={cn('mt-1 text-[12px]', state.error ? 'text-[#c0392b]' : 'text-gray-400')}>
        {message}
      </div>
    </div>
  )
}

function ConnectionCheckTab({ state }: { state: ReadinessState }) {
  return (
    <div className="space-y-4">
      <ReadinessStateSection
        title="Source 연결 검사"
        subtitle="이 DB를 변경 감지(CDC) Source로 사용할 수 있습니다."
        state={state.source}
      />
      <ReadinessStateSection
        title="Sink 연결 검사"
        subtitle="이 DB를 동기화 대상(Sink)으로 사용할 수 있습니다."
        state={state.sink}
      />
    </div>
  )
}

function MetricsTab({ state }: { state: ResourceState<DatabaseMetricsResponse> }) {
  const metrics = liveDatabaseMetrics(state.data)

  if (state.loading && !state.loaded) {
    return (
      <Panel title="Database metrics">
        <div className="px-4 py-8 text-center text-[12.5px] text-gray-400">Metrics를 불러오는 중…</div>
      </Panel>
    )
  }
  if (state.error) {
    return (
      <Panel title="Database metrics">
        <div className="px-4 py-8 text-center text-[12.5px] text-[#c0392b]">{state.error}</div>
      </Panel>
    )
  }
  if (!metrics) {
    return (
      <Panel title="Database metrics">
        <div className="px-4 py-8 text-center text-[12.5px] text-gray-400">수집된 DB metrics가 없습니다.</div>
      </Panel>
    )
  }

  return (
    <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
      <MetricCard label="TPS" value={formatMetric(metrics.tps)} sub="transactions/sec" />
      <MetricCard
        label="Query response"
        value={formatMetric(metrics.queryResponseMs)}
        sub="ms avg"
        tone={metrics.queryResponseMs > 1000 ? 'warn' : 'good'}
      />
      <MetricCard label="Active connections" value={formatMetric(metrics.activeConnections)} />
    </div>
  )
}

function SchemaTab({ state }: { state: ResourceState<{ tables: SchemaTable[] }> }) {
  const [open, setOpen] = useState<string | null>(null)
  const tables = state.data?.tables ?? []

  useEffect(() => {
    const first = state.data?.tables[0]
    setOpen(first ? `${first.schema}.${first.name}` : null)
  }, [state.data])

  if (state.loading && !state.loaded) {
    return (
      <Panel title="Tables">
        <div className="px-4 py-8 text-center text-[12.5px] text-gray-400">스키마를 불러오는 중…</div>
      </Panel>
    )
  }
  if (state.error) {
    return (
      <Panel title="Tables">
        <div className="px-4 py-8 text-center text-[12.5px] text-[#c0392b]">{state.error}</div>
      </Panel>
    )
  }
  if (tables.length === 0) {
    return (
      <Panel title="Tables">
        <div className="px-4 py-8 text-center text-[12.5px] text-gray-400">조회된 테이블이 없습니다.</div>
      </Panel>
    )
  }

  return (
    <Panel title="Tables">
      <div className="divide-y divide-gray-50">
        {tables.map((t) => {
          const key = `${t.schema}.${t.name}`
          return (
            <div key={key}>
              <button
                onClick={() => setOpen(open === key ? null : key)}
                className="flex w-full items-center gap-3 px-4 py-2.5 text-left hover:bg-gray-50"
              >
                <Icon name={open === key ? 'chevron-down' : 'chevron-right'} size={14} className="text-gray-400" />
                <Icon name="table" size={15} className="text-gray-400" />
                <span className="font-mono text-[13px] font-medium text-gray-800">{t.schema}.{t.name}</span>
                <div className="flex-1" />
                <span className="text-[12px] text-gray-400">{t.columns.length} cols</span>
              </button>
              {open === key && (
                <div className="bg-gray-50/60 px-4 py-2">
                  {t.columns.map((c) => (
                    <div key={c.name} className="flex items-center gap-2 py-1 pl-9 text-[12px]">
                      <span className="font-mono font-medium text-gray-700">{c.name}</span>
                      <span className="font-mono text-gray-400">{c.type}</span>
                      {c.primaryKey && (
                        <span className="rounded bg-[#ededed] px-1.5 py-0.5 text-[10px] font-semibold text-[#6b6b73]">PK</span>
                      )}
                      {c.indexed && !c.primaryKey && (
                        <span className="rounded bg-[#ededed] px-1.5 py-0.5 text-[10px] font-semibold text-[#6b6b73]">IDX</span>
                      )}
                      {!c.nullable && (
                        <span className="rounded bg-gray-100 px-1.5 py-0.5 text-[10px] font-medium text-gray-500">NOT NULL</span>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </div>
          )
        })}
      </div>
    </Panel>
  )
}
