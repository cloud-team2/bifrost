import type { TraceSpanResponse } from './api'

export interface WaterfallBar {
  span: TraceSpanResponse
  widthPct: number
  isError: boolean
}

/** span 목록을 최장 span 기준 너비 %로 환산한다(렌더는 컴포넌트가 담당, 로직만 분리). */
export function waterfallBars(spans: TraceSpanResponse[]): WaterfallBar[] {
  if (spans.length === 0) return []
  const max = Math.max(...spans.map((s) => s.durationMs), 1)
  return spans.map((s) => ({ span: s, widthPct: (s.durationMs * 100) / max, isError: s.status === 'error' }))
}
