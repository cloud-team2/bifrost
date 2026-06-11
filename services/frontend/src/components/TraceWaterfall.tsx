import type { TraceSummaryResponse } from '../lib/api'
import { waterfallBars } from '../lib/traceWaterfall'

/** 분산 trace 요약을 source→sink span waterfall로 렌더(#498). full 모드. */
export function TraceWaterfall({ trace }: { trace: TraceSummaryResponse }) {
  if (!trace.traceId) {
    return (
      <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-gray-200 bg-white py-16">
        <p className="text-[13px] text-gray-400">{trace.note ?? '표시할 trace가 없습니다'}</p>
      </div>
    )
  }
  const bars = waterfallBars(trace.spans)
  if (bars.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-gray-200 bg-white py-16">
        <p className="text-[13px] text-gray-400">{trace.note ?? '이 trace에 표시할 span이 없습니다'}</p>
      </div>
    )
  }
  const isError = trace.status === 'error'
  return (
    <div className="rounded-xl border border-gray-200 bg-white p-4">
      <div className="mb-3 flex items-center gap-2 text-[13px]">
        <span className="font-mono text-gray-500">{trace.traceId.slice(0, 12)}</span>
        <span className={isError ? 'text-rose-500' : 'text-emerald-600'}>{trace.status}</span>
        <span className="text-gray-400">· {trace.durationMs}ms</span>
      </div>
      <div className="space-y-1.5">
        {bars.map((b, i) => (
          <div key={i} className="flex items-center gap-2 text-[12px]">
            <span className="w-40 shrink-0 truncate text-gray-600">{b.span.name}</span>
            <div className="relative h-4 flex-1 rounded bg-gray-50">
              <div className={`absolute inset-y-0 left-0 rounded ${b.isError ? 'bg-rose-400' : 'bg-emerald-400'}`} style={{ width: `${b.widthPct}%` }} />
            </div>
            <span className="w-14 shrink-0 text-right tabular-nums text-gray-400">{b.span.durationMs}ms</span>
          </div>
        ))}
      </div>
      {bars.filter((b) => b.isError && b.span.error).map((b, i) => (
        <p key={i} className="mt-2 text-[12px] text-rose-500">{b.span.name}: {b.span.error}</p>
      ))}
    </div>
  )
}
