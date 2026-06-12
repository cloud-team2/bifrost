import { describe, it, expect } from 'vitest'
import { buildLatencyBreakdown } from './traceFlow'
import type { TraceSpanResponse, TraceSummaryResponse } from './api'

const span = (name: string, durationMicros: number, status = 'ok'): TraceSpanResponse => ({
  name,
  service: 'platform-connect',
  durationMs: Math.round(durationMicros / 1000),
  durationMicros,
  status,
  error: status === 'error' ? 'boom' : null,
})

const trace = (durationMs: number, spans: TraceSpanResponse[], status = 'ok'): TraceSummaryResponse => ({
  traceId: 't1',
  pipelineId: 'p1',
  status,
  durationMs,
  spans,
  note: null,
})

describe('buildLatencyBreakdown', () => {
  it('CDC(publish+process) → Debezium·transit·Sink + 미측정 추정(µs)', () => {
    const { segments, totalMicros, hasSink } = buildLatencyBreakdown(
      trace(60, [span('cdc.table.demo.public.orders publish', 9000), span('cdc.table.demo.public.orders process', 12000)]),
    )
    expect(segments.map((s) => s.key)).toEqual(['publish', 'transit', 'consume'])
    expect(segments.find((s) => s.key === 'publish')?.micros).toBe(9000)
    expect(segments.find((s) => s.key === 'consume')?.micros).toBe(12000)
    expect(segments.find((s) => s.key === 'transit')?.micros).toBe(39000) // 60000 - 9000 - 12000
    expect(segments.find((s) => s.key === 'transit')?.measured).toBe(false)
    expect(hasSink).toBe(true)
    expect(totalMicros).toBe(60000)
  })

  it('sub-ms span도 0이 안 됨(µs 보존)', () => {
    const { segments } = buildLatencyBreakdown(trace(8, [span('orders publish', 340), span('orders process', 510)]))
    expect(segments.find((s) => s.key === 'publish')?.micros).toBe(340)
    expect(segments.find((s) => s.key === 'consume')?.micros).toBe(510)
    expect(segments.find((s) => s.key === 'transit')?.micros).toBe(7150) // 8000 - 340 - 510
  })

  it('EDA(publish만) → sink 없이 Debezium + transit', () => {
    const { segments, hasSink } = buildLatencyBreakdown(trace(20, [span('orders publish', 7000)]))
    expect(segments.map((s) => s.key)).toEqual(['publish', 'transit'])
    expect(hasSink).toBe(false)
  })

  it('측정합이 총시간 이상이면 transit 생략', () => {
    const { segments } = buildLatencyBreakdown(trace(15, [span('orders publish', 9000), span('orders process', 12000)]))
    expect(segments.map((s) => s.key)).toEqual(['publish', 'consume'])
  })

  it('error span은 해당 세그먼트 status=error', () => {
    const { segments } = buildLatencyBreakdown(trace(60, [span('orders publish', 9000), span('orders process', 12000, 'error')]))
    expect(segments.find((s) => s.key === 'consume')?.status).toBe('error')
  })

  it('span 없음 → 빈 세그먼트', () => {
    expect(buildLatencyBreakdown(trace(0, [])).segments).toEqual([])
  })

  it('이름이 규칙과 다르면 순서로 추정(첫=publish, 둘째=consume)', () => {
    const { segments } = buildLatencyBreakdown(trace(50, [span('alpha', 3000), span('beta', 4000)]))
    expect(segments.find((s) => s.key === 'publish')?.micros).toBe(3000)
    expect(segments.find((s) => s.key === 'consume')?.micros).toBe(4000)
  })
})
