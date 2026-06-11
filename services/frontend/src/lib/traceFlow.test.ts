import { describe, it, expect } from 'vitest'
import { buildFlowStages } from './traceFlow'
import type { TraceSpanResponse } from './api'

const span = (name: string, durationMs: number, status = 'ok'): TraceSpanResponse => ({
  name,
  service: 'platform-connect',
  durationMs,
  status,
  error: status === 'error' ? 'boom' : null,
})

describe('buildFlowStages', () => {
  it('CDC(publish+process) → source·publish·topic·consume·sink 5단계', () => {
    const stages = buildFlowStages([
      span('cdc.table.demo.public.orders publish', 9),
      span('cdc.table.demo.public.orders process', 12),
    ])
    expect(stages.map((s) => s.id)).toEqual(['source', 'publish', 'topic', 'consume', 'sink'])
    expect(stages.find((s) => s.id === 'publish')?.durationMs).toBe(9)
    expect(stages.find((s) => s.id === 'consume')?.durationMs).toBe(12)
    expect(stages.every((s) => s.status === 'ok' || s.status === 'neutral')).toBe(true)
  })

  it('EDA(publish만) → sink 없이 source·publish·topic 3단계', () => {
    const stages = buildFlowStages([span('cdc.table.demo.public.orders publish', 7)])
    expect(stages.map((s) => s.id)).toEqual(['source', 'publish', 'topic'])
  })

  it('error span은 해당 단계와 엔드포인트에 error 상태 전파', () => {
    const stages = buildFlowStages([
      span('orders publish', 9),
      span('orders process', 12, 'error'),
    ])
    expect(stages.find((s) => s.id === 'consume')?.status).toBe('error')
    expect(stages.find((s) => s.id === 'sink')?.status).toBe('error')
  })

  it('span 없음 → 빈 배열(컴포넌트가 빈상태 처리)', () => {
    expect(buildFlowStages([])).toEqual([])
  })

  it('이름이 규칙과 다르면 순서로 추정(첫=publish, 둘째=consume)', () => {
    const stages = buildFlowStages([span('alpha', 3), span('beta', 4)])
    expect(stages.find((s) => s.id === 'publish')?.durationMs).toBe(3)
    expect(stages.find((s) => s.id === 'consume')?.durationMs).toBe(4)
  })
})
