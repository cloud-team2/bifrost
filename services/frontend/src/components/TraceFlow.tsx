import type { TraceSummaryResponse } from '../lib/api'
import { buildLatencyBreakdown, type LatencySegment } from '../lib/traceFlow'
import { cn } from '../lib/format'

const HATCH = 'repeating-linear-gradient(45deg,#f3f4f6,#f3f4f6 5px,#e9eaed 5px,#e9eaed 10px)'

/** 데이터플레인 trace를 "지연 분해" 막대로 렌더(#614). 어디서 시간이 걸리는지 한눈에. */
export function TraceFlow({ trace }: { trace: TraceSummaryResponse }) {
  const { segments, totalMs, hasSink } = buildLatencyBreakdown(trace)
  const isError = trace.status === 'error'
  const errored = trace.spans.filter((s) => s.status === 'error' && s.error)

  return (
    <div className="rounded-xl border border-gray-200 bg-white p-4">
      <div className="mb-3 flex flex-wrap items-center gap-x-3 gap-y-1 text-[12px]">
        {trace.traceId && <span className="font-mono text-gray-500">{trace.traceId.slice(0, 16)}</span>}
        <span className={isError ? 'text-rose-500' : 'text-emerald-600'}>{isError ? '오류' : '정상'}</span>
        <span className="text-gray-400">· 총 {totalMs}ms</span>
      </div>

      <div className="mb-1.5 flex items-center justify-between text-[10.5px] text-gray-400">
        <span>Source DB</span>
        <span>{hasSink ? 'Sink DB' : 'Topic'}</span>
      </div>

      <div className="flex h-7 w-full overflow-hidden rounded-md">
        {segments.map((s) => (
          <div
            key={s.key}
            style={{ flexGrow: s.ms, flexBasis: 0, minWidth: 46, backgroundImage: s.measured || s.status === 'error' ? undefined : HATCH }}
            className={cn('flex items-center justify-center px-1 text-[10px] font-medium', segClass(s))}
          >
            <span className="truncate">{s.label}</span>
          </div>
        ))}
      </div>

      <div className="mt-1 flex w-full">
        {segments.map((s) => (
          <div key={s.key} style={{ flexGrow: s.ms, flexBasis: 0, minWidth: 46 }} className="px-1 text-center text-[10px] text-gray-400">
            <span className="truncate">{s.measured ? `${s.ms}ms` : '미측정'}</span>
          </div>
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

function segClass(s: LatencySegment): string {
  if (s.status === 'error') return 'bg-rose-500 text-white'
  if (!s.measured) return 'text-gray-400'
  return 'bg-gray-500 text-white'
}
