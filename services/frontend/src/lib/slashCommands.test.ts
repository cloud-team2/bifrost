import { describe, expect, it } from 'vitest'
import {
  buildSlashCommands,
  missingSlashArgs,
  parseSlashCommand,
  slashCommandParams,
} from './slashCommands'

const catalog = [
  {
    name: 'get_consumer_groups',
    method: 'GET',
    risk: 'read_only',
    path: '/internal/ops/projects/{project_id}/kafka/consumer-groups',
    params_schema: {},
  },
  {
    name: 'list_project_pipelines',
    method: 'GET',
    risk: 'read_only',
    path: '/internal/ops/projects/{project_id}/pipelines',
    params_schema: {},
  },
  {
    name: 'list_pipelines',
    method: 'GET',
    risk: 'read_only',
    path: '/internal/ops/projects/{project_id}/pipelines/status',
    params_schema: {},
  },
  {
    name: 'list_connectors',
    method: 'GET',
    risk: 'read_only',
    path: '/internal/ops/projects/{project_id}/kafka/connectors/status',
    params_schema: {},
  },
  {
    name: 'analyze_event_log',
    method: 'GET',
    risk: 'read_only',
    path: '/internal/ops/projects/{project_id}/observability/events/summary',
    params_schema: {},
  },
  {
    name: 'search_logs',
    method: 'POST',
    risk: 'read_only',
    path: '/internal/ops/projects/{project_id}/observability/logs/search',
    params_schema: { required: ['query'] },
  },
  {
    name: 'get_connector_status',
    method: 'GET',
    risk: 'read_only',
    path: '/internal/ops/projects/{project_id}/kafka/connectors/{connector_name}/status',
    params_schema: { required: ['connector_name'] },
  },
  {
    name: 'pause_connector',
    method: 'POST',
    risk: 'medium',
    path: '/internal/ops/projects/{project_id}/connectors/{connector_name}/pause',
    params_schema: { required: ['connector_name'] },
  },
]

describe('slashCommands', () => {
  it('derives project-scope slash commands from real read tool paths', () => {
    const commands = buildSlashCommands(catalog)
    const labels = commands.map((command) => command.label)

    expect(labels).toContain('/consumer-groups')
    expect(labels).toContain('/pipelines')
    expect(labels).toContain('/pipelines-list')
    expect(labels).toContain('/connectors')
    expect(labels).toContain('/events')
    expect(labels).not.toContain('/pause-connector')
  })

  it('reserves /pipelines for pipeline status, not project pipeline list', () => {
    const commands = buildSlashCommands(catalog)

    expect(commands.find((command) => command.label === '/pipelines')?.toolName).toBe('list_pipelines')
    expect(commands.find((command) => command.label === '/pipelines-list')?.toolName).toBe('list_project_pipelines')
  })

  it('keeps read-only POST tools and exposes required params in usage', () => {
    const commands = buildSlashCommands(catalog)
    const logs = commands.find((command) => command.toolName === 'search_logs')

    expect(logs?.label).toBe('/logs-search')
    expect(logs?.usage).toBe('/logs-search <query>')
  })

  it('parses commands, maps args to params, and reports missing args', () => {
    const commands = buildSlashCommands(catalog)
    const parsed = parseSlashCommand('/connectors-status orders-sink', commands)

    expect(parsed?.command.toolName).toBe('get_connector_status')
    expect(missingSlashArgs(parsed!.command, parsed!.args)).toEqual([])
    expect(slashCommandParams(parsed!.command, parsed!.args)).toEqual({ connector_name: 'orders-sink' })
  })

  it('reports missing required args without inventing values', () => {
    const commands = buildSlashCommands(catalog)
    const command = commands.find((item) => item.toolName === 'get_connector_status')!

    expect(missingSlashArgs(command, [])).toEqual(['connector_name'])
  })

  it('prefers catalog description, then fallback map, then humanized name (#599)', () => {
    const commands = buildSlashCommands(
      [
        {
          name: 'get_traces',
          description: '지정 Connector의 최근 trace 이벤트를 조회합니다.',
          method: 'GET',
          risk: 'read_only',
          path: '/internal/ops/projects/{project_id}/connectors/{connector_name}/traces',
          params_schema: {},
        },
        {
          name: 'list_connectors',
          description: '',
          method: 'GET',
          risk: 'read_only',
          path: '/internal/ops/projects/{project_id}/kafka/connectors/status',
          params_schema: {},
        },
        {
          name: 'get_alerts',
          method: 'GET',
          risk: 'read_only',
          path: '/internal/ops/projects/{project_id}/observability/alerts',
          params_schema: {},
        },
      ],
      { list_connectors: 'Kafka Connector 상태 및 Task 정보를 조회합니다.' },
    )

    expect(commands.find((c) => c.toolName === 'get_traces')?.description)
      .toBe('지정 Connector의 최근 trace 이벤트를 조회합니다.')
    expect(commands.find((c) => c.toolName === 'list_connectors')?.description)
      .toBe('Kafka Connector 상태 및 Task 정보를 조회합니다.')
    expect(commands.find((c) => c.toolName === 'get_alerts')?.description)
      .toBe('alerts')
  })
})
