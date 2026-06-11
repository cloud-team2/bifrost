import { Fragment } from 'react'
import type { TraceSummaryResponse } from '../lib/api'
import { buildFlowStages, type FlowStage } from '../lib/traceFlow'

/** 분산 trace를 source→Debezium→topic→sink flow 단계로 렌더(#565). 직관적 흐름뷰. */
export function TraceFlow({ trace }: { trace: TraceSummaryResponse }) {
  const stages = buildFlowStages(trace.spans)
  const isError = trace.status === 'error'
  const errored = trace.spans.filter((s) => s.status === 'error' && s.error)

  return (
    <div className="rounded-xl border border-gray-200 bg-white p-4">
      <div className="mb-4 flex flex-wrap items-center gap-x-3 gap-y-1 text-[12px]">
        {trace.traceId && <span className="font-mono text-gray-500">{trace.traceId.slice(0, 16)}</span>}
        <span className={isError ? 'text-rose-500' : 'text-emerald-600'}>
          {isError ? '오류' : '정상'}
        </span>
        <span className="text-gray-400">· 총 {trace.durationMs}ms</span>
      </div>

      <div className="flex items-center gap-1 overflow-x-auto pb-1">
        {stages.map((s, i) => (
          <Fragment key={s.id}>
            {i > 0 && <FlowArrow />}
            <StageCard stage={s} />
          </Fragment>
        ))}
      </div>

      {errored.map((s, i) => (
        <p key={i} className="mt-3 text-[12px] text-rose-500">
          {s.name}: {s.error}
        </p>
      ))}
    </div>
  )
}

function StageCard({ stage }: { stage: FlowStage }) {
  const tone =
    stage.status === 'error'
      ? 'border-rose-200 bg-rose-50'
      : stage.kind === 'span'
        ? 'border-gray-200 bg-white'
        : 'border-gray-100 bg-gray-50'
  return (
    <div className={`flex min-w-[84px] flex-col items-center gap-1 rounded-lg border px-3 py-2.5 ${tone}`}>
      <span className="text-[11px] font-medium text-gray-600">{stage.label}</span>
      {stage.durationMs !== null ? (
        <span className="text-[12px] tabular-nums text-gray-500">{stage.durationMs}ms</span>
      ) : (
        <span className="text-[11px] text-gray-300">—</span>
      )}
      <StatusDot status={stage.status} />
    </div>
  )
}

function StatusDot({ status }: { status: FlowStage['status'] }) {
  const color =
    status === 'error' ? 'bg-rose-400' : status === 'ok' ? 'bg-emerald-400' : 'bg-gray-300'
  return <span className={`h-1.5 w-1.5 rounded-full ${color}`} />
}

function FlowArrow() {
  return <span className="shrink-0 px-0.5 text-gray-300">→</span>
}
