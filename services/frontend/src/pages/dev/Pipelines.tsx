import { useEffect, useState } from 'react'
import { Icon } from '../../components/Icon'
import { TechIcon, nodeKind } from '../../components/TechIcon'
import { PageHead, StatusBadge } from '../../components/blocks'
import { useApp } from '../../store/AppStore'
import { CreatePipelineModal } from '../modals/CreatePipelineModal'
import { nodeName, pipelineLabel } from '../../data/helpers'
import type { Edge } from '../../data/types'
import { api, type ConsumerGroupInfo } from '../../lib/api'
import { cn } from '../../lib/format'

type Tab = 'all' | 'active' | 'issue'
type ConsumerGroupLoad = { loading: boolean; error: boolean; groups: ConsumerGroupInfo[] }

const TAB_LABELS: Record<Tab, string> = { all: '전체', active: '활성', issue: '이슈' }

export function Pipelines() {
  const app = useApp()
  const [tab, setTab] = useState<Tab>('all')
  const [modal, setModal] = useState(false)

  const all = app.edges.filter((e) => app.currentProject?.pipelineIds.includes(e.id))
  const active = all.filter((e) => e.status === 'active')
  const issue = all.filter((e) => e.status === 'error' || e.status === 'lag')
  const openIncidents = app.incidents.filter((i) => i.status.toUpperCase() !== 'RESOLVED')
  const fanOutPipelineIds = all
    .filter((e) => e.pattern === 'fan-out')
    .map((e) => e.id)
    .sort()
    .join('\0')

  const counts: Record<Tab, number> = { all: all.length, active: active.length, issue: issue.length }
  const [consumerGroups, setConsumerGroups] = useState<Record<string, ConsumerGroupLoad>>({})

  useEffect(() => {
    const wsId = app.currentProject?.id
    const ids = fanOutPipelineIds ? fanOutPipelineIds.split('\0') : []
    if (!wsId || ids.length === 0) {
      setConsumerGroups({})
      return
    }

    let cancelled = false
    setConsumerGroups((prev) =>
      Object.fromEntries(ids.map((id) => [
        id,
        { loading: true, error: false, groups: prev[id]?.groups ?? [] },
      ])),
    )

    Promise.all(
      ids.map((id) =>
        api
          .pipelineConsumerGroups(wsId, id)
          .then((groups) => [id, { loading: false, error: false, groups }] as const)
          .catch(() => [id, { loading: false, error: true, groups: [] }] as const),
      ),
    ).then((entries) => {
      if (!cancelled) setConsumerGroups(Object.fromEntries(entries))
    })

    return () => { cancelled = true }
  }, [app.currentProject?.id, fanOutPipelineIds])

  const filtered = (() => {
    const base = tab === 'active' ? active : tab === 'issue' ? issue : all
    return [...base].sort((a, b) => {
      const priority = (e: Edge) =>
        e.status === 'error' ? 0 : e.status === 'lag' ? 1 : e.status === 'creating' ? 2 : 3
      return priority(a) - priority(b)
    })
  })()

  return (
    <div className="px-6 py-5">
      <PageHead
        title="Pipelines"
        actions={
          <button
            onClick={() => setModal(true)}
            className="flex items-center gap-1.5 rounded-md bg-brand-600 px-3.5 py-2 text-sm font-medium text-white hover:bg-brand-700"
          >
            <Icon name="plus" size={15} />
            Pipeline 연결
          </button>
        }
      />

      <div className="mt-4 flex gap-1 border-b border-gray-200">
        {(['all', 'active', 'issue'] as Tab[]).map((t) => (
          <button
            key={t}
            onClick={() => setTab(t)}
            className={cn(
              '-mb-px border-b-2 px-3 pb-2 text-[13px] font-medium transition-colors',
              tab === t ? 'border-brand-600 text-brand-700' : 'border-transparent text-gray-500 hover:text-gray-700',
            )}
          >
            {TAB_LABELS[t]}
            <span
              className={cn(
                'ml-1.5 rounded-full px-1.5 py-0.5 text-[10.5px] font-semibold',
                tab === t ? 'bg-brand-100 text-brand-700' : 'bg-gray-100 text-gray-500',
                t === 'issue' && counts[t] > 0 ? 'bg-amber-100 text-amber-700' : '',
              )}
            >
              {counts[t]}
            </span>
          </button>
        ))}
        <button
          onClick={() => app.setView('alerts')}
          className="ml-2 -mb-px flex items-center gap-1.5 border-b-2 border-transparent px-3 pb-2 text-[13px] font-medium text-gray-500 transition-colors hover:text-gray-700"
        >
          <Icon name="bell" size={13} />
          인시던트
          <span
            className={cn(
              'rounded-full px-1.5 py-0.5 text-[10.5px] font-semibold',
              openIncidents.length > 0 ? 'bg-rose-100 text-rose-700' : 'bg-gray-100 text-gray-500',
            )}
          >
            {openIncidents.length}
          </span>
        </button>
      </div>

      <div className="mt-4 space-y-2.5">
        {filtered.map((e) => (
          <PipelineCard key={e.id} edge={e} consumerGroups={consumerGroups[e.id]} />
        ))}
        {filtered.length === 0 && (
          <div className="rounded-xl border border-dashed border-gray-200 py-16 text-center text-sm text-gray-400">
            {tab === 'issue' ? '이슈가 있는 Pipeline이 없습니다.' : '아직 Pipeline이 없습니다.'}
          </div>
        )}
      </div>

      <CreatePipelineModal open={modal} onClose={() => setModal(false)} />
    </div>
  )
}

function PipelineCard({ edge, consumerGroups }: { edge: Edge; consumerGroups?: ConsumerGroupLoad }) {
  const app = useApp()
  const source = app.nodes.find((n) => n.id === edge.source)
  const sink = edge.sink ? app.nodes.find((n) => n.id === edge.sink) : null
  const groups = consumerGroups?.groups ?? []

  const lineColor =
    edge.status === 'error'
      ? 'border-rose-400'
      : edge.status === 'lag'
        ? 'border-amber-400'
        : edge.status === 'paused'
          ? 'border-gray-300'
          : 'border-emerald-400'

  const arrowColor =
    edge.status === 'error'
      ? 'text-rose-400'
      : edge.status === 'lag'
        ? 'text-amber-400'
        : edge.status === 'paused'
          ? 'text-gray-300'
          : 'text-emerald-400'

  return (
    <button
      onClick={() => app.openPipeline(edge.id)}
      className="grid w-full grid-cols-[200px_1fr_48px_1fr_100px] items-center gap-x-4 rounded-xl border border-gray-200 bg-white px-5 py-4 text-left transition-shadow hover:shadow-[0_2px_14px_rgba(0,0,0,0.07)]"
    >
      {/* 1. alias */}
      <div className="min-w-0">
        <div className="truncate text-[14px] font-semibold text-gray-900">
          {pipelineLabel(edge)}
        </div>
        <div className="truncate font-mono text-[11px] text-gray-400">{edge.name}</div>
      </div>

      {/* 2. source */}
      <div className="flex min-w-0 items-center gap-2.5">
        {source && <TechIcon kind={nodeKind(source)} size={36} />}
        <div className="min-w-0">
          <div className="truncate text-[12.5px] font-semibold text-gray-900">
            {source ? nodeName(source) : '—'}
          </div>
          <div className="font-mono text-[10.5px] text-gray-400">
            {edge.table?.schema}.{edge.table?.name}
          </div>
        </div>
      </div>

      {/* 3. arrow */}
      <div className="flex items-center justify-center gap-0.5">
        <span className={cn('h-px w-5 border-t-2 border-dashed', lineColor)} />
        <Icon name="arrow-right" size={14} className={cn('shrink-0', arrowColor)} />
      </div>

      {/* 4. destination */}
      {edge.pattern === 'direct' && sink ? (
        <div className="flex min-w-0 items-center gap-2.5">
          <TechIcon kind={nodeKind(sink)} size={36} />
          <div className="min-w-0">
            <div className="truncate text-[12.5px] font-semibold text-gray-900">{nodeName(sink)}</div>
            <div className="text-[10.5px] text-gray-400">싱크 DB</div>
          </div>
        </div>
      ) : (
        <div className="flex min-w-0 items-center gap-2.5">
          <div className="flex min-w-0 flex-wrap gap-1.5">
            {consumerGroups?.loading ? (
              <span className="rounded-lg bg-gray-100 px-2 py-1 text-[11px] text-gray-400">
                consumers 조회 중
              </span>
            ) : consumerGroups?.error ? (
              <span className="rounded-lg bg-rose-50 px-2 py-1 text-[11px] text-rose-500">
                consumer 조회 실패
              </span>
            ) : groups.length > 0 ? (
              groups.slice(0, 2).map((g) => (
                <span key={g.name} className="max-w-[130px] truncate rounded-lg bg-violet-50 px-2 py-1 font-mono text-[11px] text-violet-700">
                  {g.name}
                </span>
              ))
            ) : (
              <span className="rounded-lg bg-gray-100 px-2 py-1 text-[11px] text-gray-400">
                consumers 없음
              </span>
            )}
          </div>
          {groups.length > 0 && !consumerGroups?.loading && !consumerGroups?.error && (
            <span className="shrink-0 text-[12px] text-gray-500">{groups.length}개 그룹</span>
          )}
        </div>
      )}

      {/* 5. status */}
      <div className="flex justify-end">
        <StatusBadge status={edge.status} />
      </div>
    </button>
  )
}
