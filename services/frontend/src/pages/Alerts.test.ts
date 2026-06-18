import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { buildEvents, buildRunCandidate, EventDetailScreen, reportActions } from './Alerts'
import type { IncidentReportResponse, IncidentResponse } from '../lib/api'
import type { Edge } from '../data/types'

function report(body: unknown): IncidentReportResponse {
  return {
    id: 'report-1',
    runId: 'run-1',
    incidentId: 'incident-1',
    rootCauseId: null,
    confidence: null,
    verified: false,
    body,
    createdAt: null,
  }
}

function pipeline(overrides: Partial<Edge> = {}): Edge {
  return {
    id: 'pipeline-1',
    name: 'Orders CDC',
    pattern: 'direct',
    source: 'source-db',
    sink: 'sink-db',
    topic: 'orders',
    sourceConnector: 'orders-source',
    sinkConnector: 'orders-sink',
    status: 'active',
    partitions: 3,
    ...overrides,
  }
}

function incident(overrides: Partial<IncidentResponse> = {}): IncidentResponse {
  return {
    id: 'incident-1',
    tenantId: 'workspace-1',
    groupingKey: 'pipeline-1:lag',
    severity: 'WARN',
    status: 'OPEN',
    title: 'Orders lag',
    rca: null,
    sourceType: 'pipeline',
    sourceId: 'pipeline-1',
    openedAt: '2026-06-11T01:00:00.000Z',
    resolvedAt: null,
    ...overrides,
  }
}

describe('incident report actions', () => {
  it('reads report action_candidates without treating approved_actions as recommendations', () => {
    const actions = reportActions([
      report({
        action_candidates: [
          {
            action_id: 'act-1',
            action_type: 'runtime_tool',
            action_name: 'restart_connector_task',
            risk: 'medium',
            reason: 'connector failed',
            tool_name: 'restart_connector_task',
          },
        ],
        approved_actions: [
          {
            action_id: 'act-approved',
            status: 'approved',
          },
        ],
      }),
    ])

    expect(actions).toHaveLength(1)
    expect(actions[0].actionId).toBe('act-1')
  })

  it('does not show action_execution report candidates as new recommendations', () => {
    const actions = reportActions([
      report({
        mode: 'action_execution',
        action_candidates: [
          {
            action_id: 'act-executed',
            action_type: 'runtime_tool',
            action_name: 'resume_connector',
            risk: 'low',
            tool_name: 'resume_connector',
            tool_params: { connector_name: 'orders-source' },
          },
        ],
      }),
    ])

    expect(actions).toHaveLength(0)
  })

  it('builds a runnable candidate from explicit low-risk report tool_params', () => {
    const [action] = reportActions([
      report({
        action_candidates: [
          {
            action_id: 'act-2',
            action_type: 'runtime_tool',
            action_name: 'resume_connector',
            risk: 'low',
            reason: 'connector recovered',
            tool_name: 'resume_connector',
            tool_params: { connector_name: 'orders-source' },
          },
        ],
      }),
    ])

    expect(buildRunCandidate(action, [])).toEqual(expect.objectContaining({
      action_id: 'act-2',
      tool_name: 'resume_connector',
      tool_params: { connector_name: 'orders-source' },
    }))
  })

  it('builds an approval-gated restart candidate mapped to restart_connector (#593)', () => {
    const [action] = reportActions([
      report({
        action_candidates: [
          {
            action_id: 'act-3',
            action_type: 'runtime_tool',
            action_name: 'restart_connector_task',
            risk: 'medium',
            reason: 'generic connector failure',
            tool_name: 'restart_connector_task',
            tool_params: { connector_name: 'orders-source' },
          },
        ],
      }),
    ])

    expect(buildRunCandidate(action, [pipeline()])).toEqual(expect.objectContaining({
      action_id: 'act-3',
      tool_name: 'restart_connector',
      tool_params: { connector_name: 'orders-source' },
    }))
  })

  it('builds an approval-gated consumer group restart candidate (#648)', () => {
    const [action] = reportActions([
      report({
        action_candidates: [
          {
            action_id: 'act-consumer-group',
            action_type: 'runtime_tool',
            action_name: 'restart_consumer_group',
            risk: 'high',
            reason: 'consumer group is stalled',
            tool_name: 'restart_consumer_group',
            tool_params: { consumer_group: 'orders-consumer' },
          },
        ],
      }),
    ])

    expect(buildRunCandidate(action, [pipeline()])).toEqual(expect.objectContaining({
      action_id: 'act-consumer-group',
      tool_name: 'restart_consumer_group',
      tool_params: { consumer_group: 'orders-consumer' },
    }))
  })

  it('infers a connector target from topology when report params are absent', () => {
    const [action] = reportActions([
      report({
        action_candidates: [
          {
            action_id: 'act-4',
            action_type: 'runtime_tool',
            action_name: 'resume_connector',
            root_cause_id: 'SINK_DB_CONNECTION_TIMEOUT',
            risk: 'low',
            reason: 'sink recovered',
            tool_name: 'resume_connector',
          },
        ],
      }),
    ])

    expect(buildRunCandidate(action, [pipeline()])).toEqual(expect.objectContaining({
      action_id: 'act-4',
      action_name: 'resume_connector',
      action_type: 'runtime_tool',
      root_cause_id: 'SINK_DB_CONNECTION_TIMEOUT',
      risk: 'low',
      reason: 'sink recovered',
      tool_name: 'resume_connector',
      tool_params: { connector_name: 'orders-source' },
    }))
  })

  it('enables an incident Run candidate for remediation report actions without explicit params (#671)', () => {
    const [action] = reportActions([
      report({
        mode: 'incident_analysis',
        action_candidates: [
          {
            action_id: 'act-run-visible',
            action_type: 'runtime_tool',
            action_name: 'restart_connector',
            root_cause_id: 'CONNECTOR_TASK_FAILED',
            risk: 'medium',
            reason: 'failed task can be restarted',
            tool_name: 'restart_connector',
          },
        ],
      }),
    ])

    expect(buildRunCandidate(action, [pipeline()])).toEqual(expect.objectContaining({
      action_id: 'act-run-visible',
      action_type: 'runtime_tool',
      tool_name: 'restart_connector',
      tool_params: { connector_name: 'orders-source' },
    }))
  })
})

describe('alert event stream', () => {
  it('keeps API-backed pipeline/resource event fields available for event details', () => {
    const events = buildEvents(
      [
        {
          id: 'event-1',
          pipelineId: 'pipeline-1',
          incidentId: 'incident-1',
          level: 'WARN',
          type: 'PIPELINE_LAG',
          message: 'consumer lag above threshold',
          createdAt: '2026-06-11T01:00:00.000Z',
        },
      ],
      [
        {
          eventType: 'PARTITION_REASSIGNMENT',
          resource: 'orders-0',
          detail: 'replicas changed',
          occurredAt: '2026-06-11T01:01:00.000Z',
        },
      ],
    )

    expect(events).toHaveLength(2)
    expect(events[0]).toEqual(expect.objectContaining({
      source: 'resource',
      label: 'PARTITION_REASSIGNMENT',
      resourceKey: 'orders-0',
      message: '[orders-0] replicas changed',
    }))
    expect(events[1]).toEqual(expect.objectContaining({
      source: 'pipeline',
      level: 'warning',
      pipelineId: 'pipeline-1',
      incidentId: 'incident-1',
      label: 'PIPELINE_LAG',
    }))
  })

  it('keeps resource event identity stable across refresh timestamps', () => {
    const [first] = buildEvents([], [
      {
        eventType: 'PARTITION_REASSIGNMENT',
        resource: 'orders-0',
        detail: 'replicas=[0,1,2] addingReplicas=[]',
        occurredAt: '2026-06-11T01:00:00.000Z',
      },
    ])
    const [second] = buildEvents([], [
      {
        eventType: 'PARTITION_REASSIGNMENT',
        resource: 'orders-0',
        detail: 'replicas=[0,1,2] addingReplicas=[]',
        occurredAt: '2026-06-11T01:00:10.000Z',
      },
    ])

    expect(second.id).toBe(first.id)
  })

  it('disambiguates duplicate resource event rows without using volatile timestamps', () => {
    const events = buildEvents([], [
      {
        eventType: 'PARTITION_REASSIGNMENT',
        resource: 'orders-0',
        detail: 'replicas=[0,1,2] addingReplicas=[]',
        occurredAt: '2026-06-11T01:00:00.000Z',
      },
      {
        eventType: 'PARTITION_REASSIGNMENT',
        resource: 'orders-0',
        detail: 'replicas=[0,1,2] addingReplicas=[]',
        occurredAt: '2026-06-11T01:00:00.000Z',
      },
    ])

    expect(events.map((event) => event.id)).toEqual([
      'resource:PARTITION_REASSIGNMENT:orders-0:replicas=[0,1,2] addingReplicas=[]',
      'resource:PARTITION_REASSIGNMENT:orders-0:replicas=[0,1,2] addingReplicas=[]#2',
    ])
  })

  it('renders event detail fields from loaded API rows', () => {
    const [event] = buildEvents(
      [
        {
          id: 'event-1',
          pipelineId: 'pipeline-1',
          incidentId: 'incident-1',
          level: 'WARN',
          type: 'PIPELINE_LAG',
          message: 'consumer lag above threshold',
          createdAt: '2026-06-11T01:00:00.000Z',
        },
      ],
      [],
    )

    const html = renderToStaticMarkup(createElement(EventDetailScreen, {
      event,
      pipeline: pipeline(),
      onBack: () => {},
      onOpenPipeline: () => {},
    }))

    expect(html).toContain('PIPELINE_LAG')
    expect(html).toContain('consumer lag above threshold')
    expect(html).toContain('Orders CDC')
    expect(html).toContain('event-1')
  })

  it('renders resource event detail fields from loaded API rows', () => {
    const [event] = buildEvents(
      [],
      [
        {
          eventType: 'PARTITION_REASSIGNMENT',
          resource: 'orders-0',
          detail: 'replicas=[0,1,2] addingReplicas=[]',
          occurredAt: '2026-06-11T01:00:00.000Z',
        },
      ],
    )

    const html = renderToStaticMarkup(createElement(EventDetailScreen, {
      event,
      pipeline: null,
      onBack: () => {},
      onOpenPipeline: () => {},
    }))

    expect(html).toContain('PARTITION_REASSIGNMENT')
    expect(html).toContain('[orders-0] replicas=[0,1,2] addingReplicas=[]')
    expect(html).toContain('orders-0')
    expect(html).toContain('resource:PARTITION_REASSIGNMENT:orders-0:replicas=[0,1,2] addingReplicas=[]')
  })
})
