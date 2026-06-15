import type { TraceSummaryResponse } from '../lib/api'
import { buildLatencyBreakdown, type LatencySegment } from '../lib/traceFlow'
import { cn, fmtDuration } from '../lib/format'

const HATCH = 'repeating-linear-gradient(45deg,#f3f4f6,#f3f4f6 5px,#e9eaed 5px,#e9eaed 10px)'

/** 데이터플레인 trace를 "지연 분해" 막대로 렌더(#614, #632). end-to-end 시간과 어디서 시간이 걸리는지 한눈에. */
export function TraceFlow({ trace }: { trace: TraceSummaryResponse }) {
  const { segments, totalMicros, hasSink } = buildLatencyBreakdown(trace)
  const isError = trace.status === 'error'
  const errored = trace.spans.filter((s) => s.status === 'error' && s.error)

  return (
    <div className="rounded-xl border border-gray-200 bg-white p-4">
      <div className="mb-3 flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <span className="text-[15px] font-semibold text-gray-900">end-to-end {fmtDuration(totalMicros)}</span>
        <span className={cn('text-[12px]', isError ? 'text-[#c0392b]' : 'text-[#6b6b73]')}>{isError ? '오류' : '정상'}</span>
        {trace.traceId && <span className="font-mono text-[11px] text-gray-400">{trace.traceId.slice(0, 16)}</span>}
      </div>

      <div className="mb-1.5 flex items-center justify-between text-[10.5px] text-gray-400">
        <span>Source DB</span>
        <span>{hasSink ? 'Sink DB' : 'Topic'}</span>
      </div>

      <div className="flex h-7 w-full overflow-hidden rounded-md">
        {segments.map((s) => (
          <div
            key={s.key}
            style={{ flexGrow: Math.max(s.micros, 1), flexBasis: 0, minWidth: 84, backgroundImage: s.measured || s.status === 'error' ? undefined : HATCH }}
            className={cn('flex items-center justify-center px-1.5 text-[10px] font-medium', segClass(s))}
          >
            <span className="truncate">{s.label}</span>
          </div>
        ))}
      </div>

      <div className="mt-1 flex w-full">
        {segments.map((s) => (
          <div key={s.key} style={{ flexGrow: Math.max(s.micros, 1), flexBasis: 0, minWidth: 84 }} className="px-1.5 text-center text-[10px] text-gray-400">
            <span className="truncate">{s.measured ? fmtDuration(s.micros) : `~${fmtDuration(s.micros)}`}</span>
          </div>
        ))}
      </div>

      {/* 막대 설명(#705): 무엇을 보여주는지 + topic·전파가 추정값임을 명시 */}
      <p className="mt-2.5 text-[10.5px] leading-relaxed text-gray-400">
        하나의 분산 trace를 소스→싱크 <b className="font-medium text-gray-500">구간별 소요시간</b>으로 분해한 막대입니다(폭 = 소요시간 비례).
        {' '}<b className="font-medium text-gray-500">topic·전파</b>(빗금)는 직접 측정값이 아니라 end-to-end에서 측정 구간(Debezium·Sink)을 뺀 <b className="font-medium text-gray-500">추정값</b>입니다.
      </p>

      {errored.map((s, i) => (
        <p key={i} className="mt-3 text-[12px] text-[#c0392b]">
          {s.name}: {s.error}
        </p>
      ))}
    </div>
  )
}

function segClass(s: LatencySegment): string {
  if (s.status === 'error') return 'bg-[#c0392b] text-white'
  if (!s.measured) return 'text-gray-400'
  return 'bg-gray-500 text-white'
}
