import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import {
  AlertsPanel,
  ClusterInfoPanel,
  ConnectorDetailPanel,
  EventSummaryPanel,
  GenericToolResultPanel,
  PipelineStatusPanel,
  connectorStatusSummary,
  parseClusterInfo,
  pipelineStatusKo,
  semanticToken,
  severityKo,
  slashCommandUserText,
  statusCounts,
  toolLabelKo,
} from './AgentRunPanel'
import type { SlashToolCommand } from '../../lib/slashCommands'

describe('slashCommandUserText (#848)', () => {
  const command: SlashToolCommand = {
    slug: 'incidents-summary',
    label: '/incidents-summary',
    toolName: 'get_incident_summary',
    path: '/internal/ops/projects/{project_id}/incidents/{incident_id}/summary',
    description: '',
    pathParams: ['incident_id'],
    argParams: ['incident_id'],
    usage: '/incidents-summary <incident_id>',
    group: 'incident',
    labelKo: '인시던트 요약',
    argEnums: {},
  }

  it('renders a natural Korean request sentence without the raw slash command or id', () => {
    const text = slashCommandUserText(command)
    expect(text).toBe('인시던트 요약 조회해줘')
    expect(text).not.toContain('/incidents-summary')
    expect(text).not.toContain('da3ca175fb05')
  })

  it('uses the frontend toolLabelKo (not backend label_ko) and ends in 조회해줘', () => {
    // 백엔드 label_ko는 "커넥터 상태"/"컨슈머 lag"처럼 짧고 영어가 섞여 있어도,
    // 프론트 toolLabelKo로 일관되게 "…조회해줘"가 된다.
    expect(slashCommandUserText({ ...command, labelKo: '커넥터 상태', toolName: 'get_connector_status' })).toBe(
      '커넥터 상태 조회해줘',
    )
    expect(slashCommandUserText({ ...command, labelKo: '컨슈머 lag', toolName: 'get_consumer_lag' })).toBe(
      '컨슈머 지연 조회해줘',
    )
    expect(slashCommandUserText({ ...command, labelKo: '지표 조회', toolName: 'get_metrics' })).toBe('지표 조회해줘')
  })
})

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

describe('EventSummaryPanel single-incident fallback (get_incident_summary)', () => {
  it('renders a single incident summary in Korean when no overview counts', () => {
    const html = renderToStaticMarkup(
      <EventSummaryPanel
        result={{ incident_id: 'INC-9', status: 'open', severity: 'CRITICAL', summary: '복제 슬롯 중복', root_cause_summary: '슬롯 중복 활성화' }}
      />,
    )
    expect(html).toContain('미해결') // status open → 미해결
    expect(html).toContain('복제 슬롯 중복')
    expect(html).toContain('근본 원인')
    expect(html).not.toContain('open')
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
    // #844: 오류 원인에 복사 버튼 제공
    expect(html).toContain('복사')
  })

  it('reads camelCase connectorState from the real by_alias payload (#845)', () => {
    // ai-service가 by_alias=True로 직렬화하면 커넥터 state는 connectorState, task의 id는 id로 온다.
    const html = renderToStaticMarkup(
      <ConnectorDetailPanel
        result={{
          connectorName: 'orders-sink',
          connectorState: 'RUNNING',
          tasks: [
            { id: 0, state: 'RUNNING' },
            { id: 1, state: 'RUNNING' },
            { id: 2, state: 'RUNNING' },
          ],
        }}
      />,
    )
    expect(html).toContain('실행 중')
    expect(html).toContain('태스크 3/3 정상')
    expect(html).not.toContain('UNKNOWN')
  })
})

describe('parseClusterInfo (#837)', () => {
  const sample = {
    clusterId: 'kafka-1',
    controllerId: 2,
    brokerCount: 3,
    brokers: [
      { id: 1, host: 'broker-1', port: 9092, controller: false },
      { id: 2, host: 'broker-2', port: 9092, controller: true },
      { id: 3, host: 'broker-3', port: 9092, controller: false },
    ],
    topics: [
      { name: 'orders', partitionCount: 6 },
      { name: 'payments', partitionCount: 4 },
    ],
  }

  it('parses broker count and broker list', () => {
    const data = parseClusterInfo(sample)
    expect(data?.brokerCount).toBe(3)
    expect(data?.brokers).toHaveLength(3)
    expect(data?.clusterId).toBe('kafka-1')
  })

  it('detects the controller broker', () => {
    const data = parseClusterInfo(sample)
    const controller = data?.brokers.find((broker) => broker.controller)
    expect(controller?.id).toBe(2)
  })

  it('computes topic totals (count + summed partitions)', () => {
    const data = parseClusterInfo(sample)
    expect(data?.topics).toHaveLength(2)
    const partitionTotal = data!.topics.reduce((sum, topic) => sum + topic.partitionCount, 0)
    expect(partitionTotal).toBe(10)
  })

  it('accepts snake_case partition_count and returns null for non-objects', () => {
    const data = parseClusterInfo({ topics: [{ name: 't', partition_count: 5 }] })
    expect(data?.topics[0]?.partitionCount).toBe(5)
    expect(parseClusterInfo(null)).toBeNull()
  })
})

describe('ClusterInfoPanel rendering (#837)', () => {
  it('renders broker summary, controller, topic totals, and topic drilldown', () => {
    const html = renderToStaticMarkup(
      <ClusterInfoPanel
        result={{
          clusterId: 'kafka-1',
          controllerId: 2,
          brokerCount: 2,
          brokers: [
            { id: 1, host: 'broker-1', port: 9092, controller: false },
            { id: 2, host: 'broker-2', port: 9092, controller: true },
          ],
          topics: [{ name: 'orders', partitionCount: 6 }],
        }}
      />,
    )
    expect(html).toContain('브로커 2대 정상')
    expect(html).toContain('컨트롤러')
    expect(html).toContain('토픽 전체 보기')
    expect(html).toContain('orders')
  })

  it('falls back to a neutral status when there are no brokers', () => {
    const html = renderToStaticMarkup(
      <ClusterInfoPanel result={{ brokerCount: 0, brokers: [], topics: [] }} />,
    )
    expect(html).toContain('브로커 정보 없음')
  })
})
