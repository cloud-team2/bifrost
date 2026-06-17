import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import {
  ConnectorDetailPanel,
  connectorStatusSummary,
  semanticToken,
  toolLabelKo,
} from './AgentRunPanel'

describe('toolLabelKo', () => {
  it('maps command-tab tools to Korean labels (no raw English tool name)', () => {
    expect(toolLabelKo('get_connector_status')).toBe('커넥터 상태 조회')
    expect(toolLabelKo('list_project_pipelines')).toBe('파이프라인 목록 조회')
    expect(toolLabelKo('get_incident_summary')).toBe('인시던트 요약')
  })

  it('falls back to a humanized name for unknown tools', () => {
    expect(toolLabelKo('foo_bar')).toBe('foo bar')
  })
})

describe('semanticToken', () => {
  it('classifies safe / warn / danger / neutral', () => {
    expect(semanticToken('RUNNING')).toBe('safe')
    expect(semanticToken('low')).toBe('safe')
    expect(semanticToken('FAILED')).toBe('danger')
    expect(semanticToken('high')).toBe('danger')
    expect(semanticToken('PAUSED')).toBe('warn')
    expect(semanticToken('lag')).toBe('warn')
    expect(semanticToken('mystery')).toBe('neutral')
  })
})

describe('connectorStatusSummary', () => {
  it('summarizes a healthy connector', () => {
    const summary = connectorStatusSummary({
      connector_name: '3d75f6fa-df8b-4e5d-a485-b65b37bd637b-source',
      state: 'RUNNING',
      tasks: [{ task_id: 0, state: 'RUNNING' }],
    })
    expect(summary).toMatchObject({
      stateLabel: '실행 중',
      stateToken: 'safe',
      total: 1,
      running: 1,
      failed: 0,
      lastError: null,
    })
  })

  it('summarizes a failed connector and surfaces the trace', () => {
    const summary = connectorStatusSummary({
      connector_name: 'orders-sink',
      state: 'FAILED',
      tasks: [
        { task_id: 0, state: 'RUNNING' },
        { task_id: 1, state: 'FAILED', trace: 'ConnectException: slot active' },
      ],
    })
    expect(summary).toMatchObject({
      stateLabel: '실패',
      stateToken: 'danger',
      total: 2,
      running: 1,
      failed: 1,
      lastError: 'ConnectException: slot active',
    })
  })

  it('returns null for non-object results', () => {
    expect(connectorStatusSummary(null)).toBeNull()
    expect(connectorStatusSummary('nope')).toBeNull()
  })
})

describe('ConnectorDetailPanel rendering', () => {
  it('renders Korean state and task summary without raw English field labels', () => {
    const html = renderToStaticMarkup(
      <ConnectorDetailPanel
        result={{ connector_name: 'orders-source', state: 'RUNNING', tasks: [{ task_id: 0, state: 'RUNNING' }] }}
      />,
    )
    expect(html).toContain('실행 중')
    expect(html).toContain('태스크 1/1 정상')
    expect(html).not.toContain('CONNECTOR')
    expect(html).not.toContain('TRACE')
  })

  it('shows a collapsible error cause only when a trace exists', () => {
    const html = renderToStaticMarkup(
      <ConnectorDetailPanel
        result={{
          connector_name: 'orders-sink',
          state: 'FAILED',
          tasks: [{ task_id: 1, state: 'FAILED', trace: 'boom' }],
        }}
      />,
    )
    expect(html).toContain('실패')
    expect(html).toContain('오류 원인 보기')
    expect(html).toContain('boom')
  })
})
