import { describe, expect, it } from 'vitest'
import { hasTraceEvidenceLink, shouldAppendEvidenceCard } from './agentEvidence'

describe('agent evidence chat cards', () => {
  it('hides internal non-trace evidence from chat', () => {
    expect(hasTraceEvidenceLink({
      evidenceId: 'ev-tool-1',
      evidenceType: 'tool_result',
      traceId: null,
      pipelineId: null,
    })).toBe(false)
  })

  it('requires trace evidence to have a trace deep link', () => {
    expect(hasTraceEvidenceLink({
      evidenceId: 'ev-trace-1',
      evidenceType: 'trace',
      traceId: 'trace-1',
      pipelineId: 'pipeline-1',
    })).toBe(true)

    expect(hasTraceEvidenceLink({
      evidenceId: 'ev-trace-2',
      evidenceType: 'trace',
      traceId: 'trace-2',
      pipelineId: null,
    })).toBe(false)

    expect(hasTraceEvidenceLink({
      evidenceId: 'ev-trace-3',
      evidenceType: 'trace',
      traceId: null,
      pipelineId: 'pipeline-1',
    })).toBe(false)
  })

  it('allows trace evidence without a non-empty evidence_id', () => {
    expect(shouldAppendEvidenceCard([], {
      evidenceId: ' ',
      evidenceType: 'trace',
      traceId: 'trace-1',
      pipelineId: 'pipeline-1',
    })).toBe(true)
  })

  it('skips duplicate displayable evidence by evidence_id', () => {
    const existing = [{
      evidenceId: 'evidence-1',
      evidenceType: 'trace',
      traceId: 'trace-1',
      pipelineId: 'pipeline-1',
    }]

    expect(shouldAppendEvidenceCard(existing, {
      evidenceId: ' evidence-1 ',
      evidenceType: 'trace',
      traceId: 'trace-2',
      pipelineId: 'pipeline-1',
    })).toBe(false)

    expect(shouldAppendEvidenceCard(existing, {
      evidenceId: 'evidence-2',
      evidenceType: 'trace',
      traceId: 'trace-2',
      pipelineId: 'pipeline-1',
    })).toBe(true)
  })
})
