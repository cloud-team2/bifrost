import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { RcaFeedbackBar, rcaVerdictKo } from './RcaFeedbackBar'
import type { RcaFeedbackResponse } from '../lib/api'

const feedback = (over: Partial<RcaFeedbackResponse>): RcaFeedbackResponse => ({
  id: 'f1',
  incidentId: 'inc-1',
  runId: null,
  rcaRootCauseId: null,
  rcaConfidence: null,
  verdict: 'ACCEPTED',
  correctedRootCauseId: null,
  triggerLabel: null,
  symptomLabel: null,
  operator: null,
  createdAt: null,
  ...over,
})

describe('rcaVerdictKo (#964)', () => {
  it('maps verdicts to Korean', () => {
    expect(rcaVerdictKo('ACCEPTED')).toBe('원인 맞음')
    expect(rcaVerdictKo('rejected')).toBe('원인 아님')
    expect(rcaVerdictKo('CORRECTED')).toBe('원인 수정')
  })
})

describe('RcaFeedbackBar (#964)', () => {
  it('renders accept/reject/correct controls when no feedback exists', () => {
    const html = renderToStaticMarkup(
      <RcaFeedbackBar wsId="ws-1" incidentId="inc-1" existing={[]} />,
    )
    expect(html).toContain('이 분석이 맞나요?')
    expect(html).toContain('원인 맞음')
    expect(html).toContain('원인 아님')
    expect(html).toContain('원인 수정')
    expect(html).not.toContain('최근 평가')
  })

  it('shows the latest verdict and corrected root cause when feedback exists', () => {
    const html = renderToStaticMarkup(
      <RcaFeedbackBar
        wsId="ws-1"
        incidentId="inc-1"
        existing={[feedback({ verdict: 'CORRECTED', correctedRootCauseId: 'SINK_DB_CONNECTION_TIMEOUT' })]}
      />,
    )
    expect(html).toContain('최근 평가')
    expect(html).toContain('원인 수정')
    expect(html).toContain('SINK_DB_CONNECTION_TIMEOUT')
  })
})
