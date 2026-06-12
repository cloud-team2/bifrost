import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import {
  GenericToolResultPanel,
  RcaPreviewCard,
  RemediationCard,
  SlashCommandOptionContent,
  progressCounts,
  remediationCandidatesFromPayload,
  type RcaPreviewMsg,
  type RemediationMsg,
} from './AgentRunPanel'
import type { SlashToolCommand } from '../../lib/slashCommands'

describe('SlashCommandOptionContent', () => {
  it('keeps slash usage hints visible without exposing internal tool names', () => {
    const command: SlashToolCommand = {
      slug: 'connectors-status',
      label: '/connectors-status',
      toolName: 'get_connector_status',
      path: '/internal/ops/projects/{project_id}/kafka/connectors/{connector_name}/status',
      description: 'Connector status',
      pathParams: ['connector_name'],
      argParams: ['connector_name'],
      usage: '/connectors-status <connector_name>',
    }

    const html = renderToStaticMarkup(<SlashCommandOptionContent command={command} />)

    expect(html).toContain('/connectors-status')
    expect(html).toContain('&lt;connector_name&gt;')
    expect(html).toContain('Connector status')
    expect(html).not.toContain('get_connector_status')
  })
})

describe('GenericToolResultPanel', () => {
  it('renders unknown tool arrays as readable fields and badges instead of raw JSON', () => {
    const html = renderToStaticMarkup(
      <GenericToolResultPanel
        result={{
          total: 1,
          alerts: [{
            severity: 'critical',
            status: 'open',
            summary: 'Consumer lag is above threshold',
            occurred_at: '2026-06-12T00:00:00Z',
          }],
        }}
      />,
    )

    expect(html).toContain('alerts')
    expect(html).toContain('critical')
    expect(html).toContain('open')
    expect(html).toContain('Consumer lag is above threshold')
    expect(html).toContain('occurred at')
    expect(html).not.toContain('&quot;severity&quot;')
    expect(html).not.toContain('{')
  })
})
describe('incident analysis cards', () => {
  it('renders RCA preview payload as a card', () => {
    const msg: RcaPreviewMsg = {
      id: 1,
      kind: 'rcaPreview',
      runId: 'run-1',
      rootCauseId: 'CONNECTOR_TASK_FAILED',
      confidence: 0.82,
      verified: false,
      message: '[검증 전 preview] 원인 후보: CONNECTOR_TASK_FAILED',
    }

    const html = renderToStaticMarkup(<RcaPreviewCard msg={msg} />)

    expect(html).toContain('RCA')
    expect(html).toContain('CONNECTOR_TASK_FAILED')
    expect(html).toContain('82%')
  })

  it('extracts remediation candidates from nested SSE payload shapes', () => {
    const candidates = remediationCandidatesFromPayload({
      remediation: {
        action_candidates: [
          {
            action_id: 'restart-connector',
            action_name: 'Connector 재시작',
            root_cause_id: 'CONNECTOR_TASK_FAILED',
            risk: 'low',
            reason: 'task failed',
            expected_effect: 'task resumes',
            tool_name: 'restart_connector',
          },
        ],
      },
    })

    expect(candidates).toHaveLength(1)
    expect(candidates[0]).toMatchObject({
      actionId: 'restart-connector',
      actionName: 'Connector 재시작',
      rootCauseId: 'CONNECTOR_TASK_FAILED',
      toolName: 'restart_connector',
    })
  })

  it('renders remediation candidates as a card', () => {
    const msg: RemediationMsg = {
      id: 2,
      kind: 'remediation',
      runId: 'run-1',
      message: '후보 1건',
      candidates: [
        {
          actionId: 'restart-connector',
          actionType: 'runtime_tool',
          actionName: 'Connector 재시작',
          rootCauseId: 'CONNECTOR_TASK_FAILED',
          risk: 'low',
          reason: 'task failed',
          expectedEffect: 'task resumes',
          rollbackPlan: null,
          estimatedDuration: '1m',
          toolName: 'restart_connector',
        },
      ],
    }

    const html = renderToStaticMarkup(<RemediationCard msg={msg} />)

    expect(html).toContain('권장 조치')
    expect(html).toContain('Connector 재시작')
    expect(html).toContain('restart_connector')
  })
})

describe('progressCounts (#604)', () => {
  const stageItem = (runId: string, stage: string, state: 'done' | 'running' | 'waiting') => ({
    key: `${runId}:stage:${stage}`,
    kind: 'stage' as const,
    eventType: 'agent_completed',
    agent: stage,
    label: stage,
    text: '',
    state,
    summary: null,
  })
  const requiredFlow = ['correlation', 'planner', 'retrieval', 'classifier', 'rca', 'verifier', 'report']

  it('fixes the denominator to required_flow length from the start', () => {
    // 초반: stage 2개만 도착해도 분모는 7로 고정된다.
    const counts = progressCounts({
      requiredFlow,
      items: [stageItem('run-1', 'correlation', 'done'), stageItem('run-1', 'planner', 'running')],
    })
    expect(counts).toEqual({ done: 1, total: 7 })
  })

  it('excludes non-stage items (run/router) from the count', () => {
    const counts = progressCounts({
      requiredFlow,
      items: [
        { ...stageItem('run-1', 'run', 'done'), key: 'run-1:run' },
        stageItem('run-1', 'router', 'done'),
        stageItem('run-1', 'correlation', 'done'),
      ],
    })
    expect(counts).toEqual({ done: 1, total: 7 })
  })

  it('falls back to arrived-item counting when required_flow is absent (legacy backend)', () => {
    const counts = progressCounts({
      requiredFlow: null,
      items: [stageItem('run-1', 'correlation', 'done'), stageItem('run-1', 'planner', 'running')],
    })
    expect(counts).toEqual({ done: 1, total: 2 })
  })
})
