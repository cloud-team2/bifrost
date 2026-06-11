import { describe, expect, it } from 'vitest'
import { buildRunCandidate, reportActions } from './Alerts'
import type { IncidentReportResponse } from '../lib/api'
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

  it('does not run approval-backed restart actions without the Spring approval bridge', () => {
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

    expect(buildRunCandidate(action, [pipeline()])).toBeNull()
  })

  it('does not infer a connector target from topology when report params are absent', () => {
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

    expect(buildRunCandidate(action, [pipeline()])).toBeNull()
  })
})
