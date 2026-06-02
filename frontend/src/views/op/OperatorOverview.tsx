import { Icon } from '../../components/Icon'
import { MetricCard, PageHead, Panel, StatusBadge } from '../../components/blocks'
import { useApp, CLUSTER } from '../../store/AppStore'
import { pipelineLabel } from '../../data/helpers'
import { cn } from '../../lib/format'

export function OperatorOverview() {
  const app = useApp()
  const proj = app.currentProject
  const pipelineIds = proj?.pipelineIds ?? []

  const projectEdges = app.edges.filter((e) => pipelineIds.includes(e.id))
  const projectIncidents = app.incidents.filter(
    (i) =>
      i.affectedPipelines.length === 0 ||
      i.affectedPipelines.some((pid) => pipelineIds.includes(pid)),
  )
  const openInc = projectIncidents.filter((i) => i.status !== 'resolved')
  const issueEdges = projectEdges.filter((e) => e.status === 'lag' || e.status === 'error')
  const activeEdges = projectEdges.filter((e) => e.status === 'active')

  return (
    <div className="px-6 py-5">
      <PageHead title={proj?.name ?? '운영 현황'} />

      <div className="mt-4 grid grid-cols-4 gap-4">
        <MetricCard label="전체 Pipeline" value={projectEdges.length} icon="route" />
        <MetricCard
          label="정상 운영"
          value={activeEdges.length}
          icon="route"
          tone={activeEdges.length === projectEdges.length && projectEdges.length > 0 ? 'good' : undefined}
        />
        <MetricCard
          label="이슈"
          value={issueEdges.length}
          icon="alert"
          tone={issueEdges.length ? 'warn' : 'good'}
        />
        <MetricCard
          label="미해결 인시던트"
          value={openInc.length}
          icon="alert"
          tone={openInc.length ? 'bad' : 'good'}
        />
      </div>

      <div className="mt-5 grid grid-cols-[1fr_340px] gap-4">
        <div>
          <h2 className="mb-2 text-[13px] font-semibold text-gray-700">Pipeline 목록</h2>
          {projectEdges.length === 0 ? (
            <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-gray-200 bg-white py-12 text-center">
              <Icon name="route" size={24} className="mb-2 text-gray-300" />
              <p className="text-[13px] text-gray-400">이 프로젝트에 Pipeline이 없습니다</p>
            </div>
          ) : (
            <div className="space-y-2">
              {projectEdges.map((e) => {
                const hasIssue = e.status === 'lag' || e.status === 'error'
                const dotColor =
                  e.status === 'active'
                    ? 'bg-emerald-500'
                    : e.status === 'lag'
                      ? 'bg-amber-500'
                      : e.status === 'error'
                        ? 'bg-rose-500'
                        : 'bg-gray-300'
                return (
                  <button
                    key={e.id}
                    onClick={() => app.openPipeline(e.id)}
                    className="flex w-full items-center gap-3 rounded-xl border border-gray-200 bg-white px-4 py-3 text-left transition-shadow hover:shadow-md"
                  >
                    <span className={cn('h-2.5 w-2.5 shrink-0 rounded-full', dotColor)} />
                    <div className="min-w-0 flex-1">
                      <div className="truncate text-[13.5px] font-semibold text-gray-900">
                        {pipelineLabel(e)}
                      </div>
                      <div className="font-mono text-[11px] text-gray-400">{e.topic}</div>
                    </div>
                    <StatusBadge status={e.status} />
                    {hasIssue && (
                      <span className="shrink-0 text-[11px] font-medium text-amber-600">
                        lag {e.metrics?.lag?.toLocaleString()}
                      </span>
                    )}
                  </button>
                )
              })}
            </div>
          )}
        </div>

        <Panel
          title="최근 인시던트"
          right={
            <button
              onClick={() => app.setView('alerts')}
              className="text-[12px] font-medium text-brand-600 hover:underline"
            >
              전체 보기
            </button>
          }
        >
          <div className="divide-y divide-gray-50">
            {openInc.length === 0 ? (
              <div className="px-4 py-6 text-center text-[12.5px] text-emerald-600">
                미해결 인시던트 없음
              </div>
            ) : (
              openInc.slice(0, 4).map((inc) => (
                <button
                  key={inc.id}
                  onClick={() => app.openIncident(inc.id)}
                  className="flex w-full items-start gap-2.5 px-4 py-3 text-left hover:bg-gray-50"
                >
                  <span
                    className={cn(
                      'mt-1 h-2 w-2 shrink-0 rounded-full',
                      inc.severity === 'critical'
                        ? 'bg-rose-500'
                        : inc.severity === 'warning'
                          ? 'bg-amber-500'
                          : 'bg-sky-500',
                    )}
                  />
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-[12.5px] font-medium text-gray-800">{inc.title}</div>
                    <div className="text-[11px] text-gray-400">{inc.createdAt}</div>
                  </div>
                  <StatusBadge status={inc.status} />
                </button>
              ))
            )}
          </div>
        </Panel>
      </div>
    </div>
  )
}
