import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import {
  AlertsPanel,
  ConnectorDetailPanel,
  EventSummaryPanel,
  GenericToolResultPanel,
  PipelineStatusPanel,
  connectorStatusSummary,
  pipelineStatusKo,
  semanticToken,
  severityKo,
  statusCounts,
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

describe('GenericToolResultPanel', () => {
  it('uses Korean field labels and hides empty fields', () => {
    const html = renderToStaticMarkup(
      <GenericToolResultPanel result={{ lag: 0, worker_id: null, trace: '' }} />,
    )
    expect(html).toContain('지연') // lag → 지연 (value 0 kept)
    expect(html).not.toContain('worker') // worker_id is null → hidden
    expect(html).not.toContain('워커')
    expect(html).not.toContain('TRACE') // no raw English labels
  })
})

describe('pipelineStatusKo / statusCounts', () => {
  it('maps pipeline statuses to Korean', () => {
    expect(pipelineStatusKo('running')).toBe('정상')
    expect(pipelineStatusKo('lag')).toBe('지연')
    expect(pipelineStatusKo('error')).toBe('오류')
  })

  it('counts tokens by bucket', () => {
    expect(statusCounts(['safe', 'safe', 'warn', 'danger'])).toMatchObject({ safe: 2, warn: 1, danger: 1 })
  })
})

describe('PipelineStatusPanel', () => {
  it('renders a Korean status list with counts and no English column headers', () => {
    const html = renderToStaticMarkup(
      <PipelineStatusPanel
        result={{
          pipelines: [
            { id: 'a', name: '주문 파이프라인', status: 'running' },
            { id: 'b', name: '결제 파이프라인', status: 'lag', lag: 12400 },
            { id: 'c', name: '재고 파이프라인', status: 'error' },
          ],
        }}
      />,
    )
    expect(html).toContain('전체 3개')
    expect(html).toContain('정상 1')
    expect(html).toContain('지연 1')
    expect(html).toContain('오류 1')
    expect(html).toContain('주문 파이프라인')
    expect(html).not.toMatch(/>id<|>name<|>status<|>lag</) // no raw table headers
  })
})

describe('EventSummaryPanel', () => {
  it('renders incidents in Korean (미해결/긴급/경고) without English labels', () => {
    const html = renderToStaticMarkup(
      <EventSummaryPanel
        result={{
          open_incidents: 2,
          critical_incidents: 1,
          critical: [{ incident_id: 'A1B2', title: '주문 파이프라인 지연', severity: 'CRITICAL' }],
          warnings: [{ event_id: 'C3D4', message: '회원 DB 커넥터 재시작 반복' }],
        }}
      />,
    )
    expect(html).toContain('미해결')
    expect(html).toContain('긴급')
    expect(html).toContain('경고')
    expect(html).toContain('주문 파이프라인 지연')
    expect(html).not.toContain('critical')
    expect(html).not.toContain('warnings')
  })
})

describe('severityKo / AlertsPanel', () => {
  it('maps severities to Korean', () => {
    expect(severityKo('CRITICAL')).toBe('긴급')
    expect(severityKo('warning')).toBe('경고')
    expect(severityKo('info')).toBe('정보')
  })

  it('renders alerts as a Korean list, clickable when incident_id exists', () => {
    const opened: string[] = []
    const html = renderToStaticMarkup(
      <AlertsPanel
        result={{
          alerts: [
            { alert_id: 'a1', severity: 'CRITICAL', status: 'open', summary: '주문 파이프라인 지연', incident_id: 'INC-1' },
            { alert_id: 'a2', severity: 'WARNING', status: 'open', summary: '재시작 반복' },
          ],
        }}
        onOpenIncident={(id) => opened.push(id)}
      />,
    )
    expect(html).toContain('긴급')
    expect(html).toContain('경고')
    expect(html).toContain('주문 파이프라인 지연')
    expect(html).toContain('<button') // incident_id 있는 항목은 클릭 가능
    expect(html).not.toContain('CRITICAL')
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
