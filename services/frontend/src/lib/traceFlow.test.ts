import { describe, it, expect } from 'vitest'
import { buildLatencyBreakdown } from './traceFlow'
import type { TraceSpanResponse, TraceSummaryResponse } from './api'

const span = (name: string, durationMs: number, status = 'ok'): TraceSpanResponse => ({
  name,
  service: 'platform-connect',
  durationMs,
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
  it('CDC(publish+process) → Debezium·transit·Sink + 미측정 추정', () => {
    const { segments, totalMs, hasSink } = buildLatencyBreakdown(
      trace(60, [span('cdc.table.demo.public.orders publish', 9), span('cdc.table.demo.public.orders process', 12)]),
    )
    expect(segments.map((s) => s.key)).toEqual(['publish', 'transit', 'consume'])
    expect(segments.find((s) => s.key === 'publish')?.ms).toBe(9)
    expect(segments.find((s) => s.key === 'consume')?.ms).toBe(12)
    expect(segments.find((s) => s.key === 'transit')?.ms).toBe(39) // 60 - 9 - 12
    expect(segments.find((s) => s.key === 'transit')?.measured).toBe(false)
    expect(hasSink).toBe(true)
    expect(totalMs).toBe(60)
  })

  it('EDA(publish만) → sink 없이 Debezium + transit', () => {
    const { segments, hasSink } = buildLatencyBreakdown(trace(20, [span('cdc.table.demo.public.orders publish', 7)]))
    expect(segments.map((s) => s.key)).toEqual(['publish', 'transit'])
    expect(hasSink).toBe(false)
  })

  it('측정합이 총시간 이상이면 transit 생략', () => {
    const { segments } = buildLatencyBreakdown(trace(15, [span('orders publish', 9), span('orders process', 12)]))
    expect(segments.map((s) => s.key)).toEqual(['publish', 'consume'])
  })

  it('error span은 해당 세그먼트 status=error', () => {
    const { segments } = buildLatencyBreakdown(trace(60, [span('orders publish', 9), span('orders process', 12, 'error')]))
    expect(segments.find((s) => s.key === 'consume')?.status).toBe('error')
  })

  it('span 없음 → 빈 세그먼트', () => {
    expect(buildLatencyBreakdown(trace(0, [])).segments).toEqual([])
  })

  it('이름이 규칙과 다르면 순서로 추정(첫=publish, 둘째=consume)', () => {
    const { segments } = buildLatencyBreakdown(trace(50, [span('alpha', 3), span('beta', 4)]))
    expect(segments.find((s) => s.key === 'publish')?.ms).toBe(3)
    expect(segments.find((s) => s.key === 'consume')?.ms).toBe(4)
  })
})
