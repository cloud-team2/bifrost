import { describe, it, expect } from 'vitest'
import { waterfallBars } from './traceWaterfall'
import type { TraceSpanResponse } from './api'

const spans: TraceSpanResponse[] = [
  { name: 'source-poll', service: 'platform-connect', durationMs: 5, status: 'ok', error: null },
  { name: 'sink-put', service: 'platform-connect', durationMs: 15, status: 'error', error: 'type mismatch' },
]

describe('waterfallBars', () => {
  it('가장 긴 span 기준으로 너비 %를 낸다', () => {
    const bars = waterfallBars(spans)
    expect(bars[0].widthPct).toBe(100 / 3)
    expect(bars[1].widthPct).toBe(100)
  })
  it('error span을 표시한다', () => {
    expect(waterfallBars(spans)[1].isError).toBe(true)
  })
  it('빈 입력은 빈 배열', () => {
    expect(waterfallBars([])).toEqual([])
  })
})
