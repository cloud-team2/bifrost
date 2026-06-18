import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import {
  GenericToolResultPanel,
  RcaPreviewCard,
  RemediationCard,
  SlashCommandOptionContent,
  progressCounts,
  remediationCandidatesFromPayload,
  slashMissingArgsFeedback,
  slashSelectionSubmissionText,
  type RcaPreviewMsg,
  type RemediationMsg,
} from './AgentRunPanel'
import { routeAgentInput } from '../../lib/agentInputRouting'
import type { SlashToolCommand } from '../../lib/slashCommands'

describe('SlashCommandOptionContent', () => {
  const connectorStatusCommand: SlashToolCommand = {
    slug: 'connectors-status',
    label: '/connectors-status',
    toolName: 'get_connector_status',
    path: '/internal/ops/projects/{project_id}/kafka/connectors/{connector_name}/status',
    description: 'Connector status',
    pathParams: ['connector_name'],
    argParams: ['connector_name'],
    argEnums: {},
    usage: '/connectors-status <connector_name>',
    group: 'cluster',
    labelKo: '커넥터 상태',
  }

  it('shows the command name and description without arg-syntax hints or internal tool names', () => {
    const html = renderToStaticMarkup(<SlashCommandOptionContent command={connectorStatusCommand} />)

    expect(html).toContain('/connectors-status')
    // (#680) 인자 누락은 대화형 안내로 일원화 — 메뉴에 usage 문법(<connector_name>)을 노출하지 않는다
    expect(html).not.toContain('&lt;connector_name&gt;')
    expect(html).toContain('Connector status')
    expect(html).not.toContain('get_connector_status')
  })

  it('submits selected required-argument slash commands through the normal send path', () => {
    const exactMissingRoute = routeAgentInput('/connectors-status', {
      slashCommands: true,
      slashLoading: false,
      slashError: null,
      commands: [connectorStatusCommand],
    })
    const partialRoute = routeAgentInput('/con', {
      slashCommands: true,
      slashLoading: false,
      slashError: null,
      commands: [connectorStatusCommand],
    })

    expect(slashSelectionSubmissionText('/connectors-status', exactMissingRoute, connectorStatusCommand))
      .toBe('/connectors-status')
    expect(slashSelectionSubmissionText('/con', partialRoute, connectorStatusCommand))
      .toBe('/connectors-status')
  })

  it('keeps missing-argument slash submissions visible as user text without executing tools', () => {
    const route = routeAgentInput('/connectors-status', {
      slashCommands: true,
      slashLoading: false,
      slashError: null,
      commands: [connectorStatusCommand],
    })

    expect(route.kind).toBe('slash_missing_args')
    if (route.kind !== 'slash_missing_args') return
    const feedback = slashMissingArgsFeedback('/connectors-status', route)

    expect(feedback).toEqual({
      userText: '/connectors-status',
      assistantText: '찾아보고싶은 connector_name을 알려주세요',
      input: '/connectors-status ',
    })
    expect(feedback.assistantText).not.toContain('사용법')
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

    expect(html).toContain('근본 원인 분석')
    expect(html).not.toContain('>RCA<')
    expect(html).toContain('CONNECTOR_TASK_FAILED') // 원인 후보 메시지 본문에 포함
    expect(html).toContain('신뢰도 82%')
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
