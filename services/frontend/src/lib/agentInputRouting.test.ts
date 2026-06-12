import { describe, expect, it } from 'vitest'
import { routeAgentInput } from './agentInputRouting'
import { buildSlashCommands } from './slashCommands'

const commands = buildSlashCommands([
  {
    name: 'list_pipelines',
    method: 'GET',
    risk: 'read_only',
    path: '/internal/ops/projects/{project_id}/pipelines/status',
    params_schema: {},
  },
  {
    name: 'get_connector_status',
    method: 'GET',
    risk: 'read_only',
    path: '/internal/ops/projects/{project_id}/kafka/connectors/{connector_name}/status',
    params_schema: { required: ['connector_name'] },
  },
  {
    name: 'query_topic_partition_lag',
    method: 'GET',
    risk: 'read_only',
    path: '/internal/ops/projects/{project_id}/kafka/topics/{topic_name}/partitions/{partition_id}/lag',
    params_schema: { required: ['topic_name', 'partition_id'] },
  },
  {
    name: 'get_pipeline_status',
    method: 'GET',
    risk: 'read_only',
    path: '/internal/ops/projects/{project_id}/pipelines/{pipeline_id}/status',
    params_schema: { required: ['pipeline_id'] },
  },
])

describe('routeAgentInput', () => {
  it('keeps non-slash text on the agent run path', () => {
    expect(routeAgentInput('show pipeline health', {
      slashCommands: true,
      slashLoading: false,
      slashError: null,
      commands,
    })).toEqual({ kind: 'free_text', message: 'show pipeline health' })
  })

  it('blocks slash submission while the catalog is still loading', () => {
    expect(routeAgentInput('/pipelines', {
      slashCommands: true,
      slashLoading: true,
      slashError: null,
      commands: [],
    })).toEqual({ kind: 'slash_loading', message: 'tool catalog를 불러오는 중입니다.' })
  })

  it('routes complete slash commands to deterministic execution', () => {
    const routed = routeAgentInput('/pipelines', {
      slashCommands: true,
      slashLoading: false,
      slashError: null,
      commands,
    })

    expect(routed.kind).toBe('slash_execute')
    if (routed.kind === 'slash_execute') {
      expect(routed.parsed.command.toolName).toBe('list_pipelines')
    }
  })

  it('keeps required-argument commands in the input until args are provided', () => {
    const routed = routeAgentInput('/connectors-status', {
      slashCommands: true,
      slashLoading: false,
      slashError: null,
      commands,
    })

    expect(routed).toEqual({
      kind: 'slash_missing_args',
      message: '찾아보고싶은 connector_name을 알려주세요',
      input: '/connectors-status ',
    })
  })

  it('asks for the first missing arg when several slash args are missing', () => {
    const routed = routeAgentInput('/topics-partitions-lag', {
      slashCommands: true,
      slashLoading: false,
      slashError: null,
      commands,
    })

    expect(routed).toEqual({
      kind: 'slash_missing_args',
      message: '찾아보고싶은 topic_name을 알려주세요',
      input: '/topics-partitions-lag ',
    })
  })

  it('preserves already provided slash args while asking for the remaining args', () => {
    const routed = routeAgentInput('/topics-partitions-lag orders', {
      slashCommands: true,
      slashLoading: false,
      slashError: null,
      commands,
    })

    expect(routed).toEqual({
      kind: 'slash_missing_args',
      message: '조회할 partition_id를 알려주세요',
      input: '/topics-partitions-lag orders ',
    })
  })

  it('uses lookup wording for id-shaped slash args', () => {
    const routed = routeAgentInput('/pipelines-status', {
      slashCommands: true,
      slashLoading: false,
      slashError: null,
      commands,
    })

    expect(routed).toEqual({
      kind: 'slash_missing_args',
      message: '조회할 pipeline_id를 알려주세요',
      input: '/pipelines-status ',
    })
  })

  it('executes required-argument commands once args are present', () => {
    const routed = routeAgentInput('/connectors-status orders-sink', {
      slashCommands: true,
      slashLoading: false,
      slashError: null,
      commands,
    })

    expect(routed.kind).toBe('slash_execute')
    if (routed.kind === 'slash_execute') {
      expect(routed.parsed.command.toolName).toBe('get_connector_status')
      expect(routed.parsed.args).toEqual(['orders-sink'])
    }
  })
})
