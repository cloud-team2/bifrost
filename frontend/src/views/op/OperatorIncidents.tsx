import { Icon } from '../../components/Icon'
import { PageHead, Panel, StatusBadge } from '../../components/blocks'
import { useToast } from '../../components/Toast'
import { useApp } from '../../store/AppStore'
import { pipelineLabel } from '../../data/helpers'
import type { IncidentReport } from '../../data/types'
import { cn } from '../../lib/format'

const SEV: Record<string, string> = {
  critical: 'bg-rose-500',
  warning: 'bg-amber-500',
  info: 'bg-sky-500',
}
const RISK: Record<string, string> = {
  low: 'bg-emerald-50 text-emerald-700',
  medium: 'bg-amber-50 text-amber-700',
  high: 'bg-rose-50 text-rose-700',
}

export function OperatorIncidents() {
  const app = useApp()
  const pipelineIds = app.currentProject?.pipelineIds ?? []
  const visibleIncidents = app.incidents.filter(
    (i) =>
      i.affectedPipelines.length === 0 ||
      i.affectedPipelines.some((pid) => pipelineIds.includes(pid)),
  )
  const selected = visibleIncidents.find((i) => i.id === app.opSelectedIncidentId) ?? visibleIncidents[0]

  return (
    <div className="px-6 py-5">
      <PageHead title="Incidents" />

      <div className="mt-4 grid grid-cols-[320px_1fr] gap-4">
        {/* list */}
        <div className="space-y-2">
          {visibleIncidents.map((inc) => (
            <button
              key={inc.id}
              onClick={() => app.openIncident(inc.id)}
              className={cn(
                'w-full rounded-xl border bg-white p-3.5 text-left transition-colors',
                selected?.id === inc.id ? 'border-brand-400 ring-1 ring-brand-200' : 'border-gray-200 hover:border-gray-300',
              )}
            >
              <div className="flex items-center gap-2">
                <span className={cn('h-2 w-2 rounded-full', SEV[inc.severity])} />
                <span className="flex-1 truncate text-[13px] font-semibold text-gray-900">{inc.title}</span>
              </div>
              <div className="mt-2 flex items-center justify-between">
                <span className="font-mono text-[11px] text-gray-400">{inc.createdAt}</span>
                <StatusBadge status={inc.status} />
              </div>
            </button>
          ))}
        </div>

        {/* detail */}
        {selected && <IncidentDetail incident={selected} />}
      </div>
    </div>
  )
}

function IncidentDetail({ incident }: { incident: IncidentReport }) {
  const app = useApp()
  const toast = useToast()

  return (
    <div className="space-y-4">
      <div className="rounded-xl border border-gray-200 bg-white p-5">
        <div className="flex items-center gap-2.5">
          <span className={cn('h-2.5 w-2.5 rounded-full', SEV[incident.severity])} />
          <h2 className="text-[16px] font-semibold text-gray-900">{incident.title}</h2>
          <StatusBadge status={incident.status} />
          <span className="ml-auto font-mono text-[11px] text-gray-400">updated {incident.updatedAt}</span>
        </div>

        <div className="mt-3 rounded-lg border border-gray-200 bg-gray-50 px-3.5 py-2.5">
          <p className="text-[12.5px] leading-relaxed text-gray-600">{incident.summary}</p>
        </div>

        <div className="mt-3">
          <div className="text-[11px] font-bold uppercase tracking-wide text-gray-400">Root cause</div>
          <p className="mt-1 text-[13px] leading-relaxed text-gray-600">{incident.rootCause}</p>
        </div>

        <div className="mt-3 flex flex-wrap gap-x-8 gap-y-2 text-[12.5px]">
          <div>
            <span className="text-gray-400">Affected pipelines: </span>
            {incident.affectedPipelines.length === 0 ? (
              <span className="text-gray-500">none</span>
            ) : (
              incident.affectedPipelines.map((pid) => {
                const p = app.edges.find((e) => e.id === pid)
                return (
                  <button
                    key={pid}
                    onClick={() => p && app.openPipeline(p.id)}
                    className="mr-1.5 font-medium text-brand-600 hover:underline"
                  >
                    {p ? pipelineLabel(p) : pid}
                  </button>
                )
              })
            )}
          </div>
          <div>
            <span className="text-gray-400">Teams: </span>
            <span className="font-medium text-gray-700">{incident.affectedTeams.join(', ') || '—'}</span>
          </div>
        </div>
      </div>

      {incident.aiActions.length > 0 && (
        <Panel title="권장 조치">
          <div className="divide-y divide-gray-50">
            {incident.aiActions.map((a) => (
              <div key={a.id} className="flex items-start gap-3 px-4 py-3">
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2">
                    <span className="text-[13px] font-medium text-gray-800">{a.label}</span>
                    <span className={cn('rounded px-1.5 py-0.5 text-[9.5px] font-bold uppercase', RISK[a.risk])}>
                      {a.risk} risk
                    </span>
                    <span className="text-[11px] text-gray-400">{a.estimatedTime}</span>
                  </div>
                  <p className="mt-0.5 text-[12px] text-gray-500">{a.detail}</p>
                </div>
                <button
                  onClick={() => {
                    app.dispatchAgentTask({
                      incidentId: incident.id,
                      actionId: a.id,
                      incidentTitle: incident.title,
                      label: a.label,
                      detail: a.detail,
                      risk: a.risk,
                      estimatedTime: a.estimatedTime,
                    })
                    toast(`'${a.label}' 조치를 AI 에이전트에서 실행합니다`)
                  }}
                  className="flex shrink-0 items-center gap-1.5 rounded-md bg-brand-600 px-3 py-1.5 text-[12px] font-semibold text-white hover:bg-brand-700"
                >
                  <Icon name="play" size={11} />
                  Run
                </button>
              </div>
            ))}
          </div>
        </Panel>
      )}

      <Panel title="Action timeline">
        <div className="space-y-0 px-4 py-3">
          {incident.actionLog.map((log, i) => (
            <div key={i} className="flex gap-3 pb-3 last:pb-0">
              <div className="flex flex-col items-center">
                <span className="h-2 w-2 rounded-full bg-brand-500" />
                {i < incident.actionLog.length - 1 && <span className="w-px flex-1 bg-gray-200" />}
              </div>
              <div className="-mt-0.5 pb-1">
                <div className="text-[12.5px] text-gray-700">{log.action}</div>
                <div className="text-[11px] text-gray-400">
                  {log.time} · {log.actor}
                </div>
              </div>
            </div>
          ))}
        </div>
      </Panel>
    </div>
  )
}
