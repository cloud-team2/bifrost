import { describe, expect, it } from 'vitest'
import {
  buildPipelineCreateInput,
  isPipelineWizardIntent,
  pipelineWizardStartCandidate,
  readinessAllowsCreate,
  readinessBlocked,
  suggestPipelineName,
  type PipelineWizardSelections,
} from './pipelineWizard'
import type { CdcReadinessResponse, DatabaseResponse } from '../../lib/api'

const okReadiness: CdcReadinessResponse = {
  overallStatus: 'OK',
  checks: [{ name: 'wal_level', status: 'OK', actual: 'logical', expected: 'logical', hint: null }],
}

const blockedReadiness: CdcReadinessResponse = {
  overallStatus: 'BLOCKED',
  checks: [{ name: 'slot', status: 'BLOCKED', actual: 'missing', expected: 'present', hint: 'replication slot 필요' }],
}

const sourceDb = {
  id: 'db-source',
  name: 'orders',
  engine: 'postgresql',
  host: 'orders-db',
  port: 5432,
  dbName: 'orders',
  username: 'app',
  password: '',
  cdcReadinessStatus: 'OK',
  sinkReadinessStatus: 'OK',
  connectionStatus: 'HEALTHY',
  connectionError: null,
  connectionCheckedAt: null,
  roles: ['source'],
  createdAt: '2026-06-01T00:00:00Z',
} satisfies DatabaseResponse

const sinkDb = {
  ...sourceDb,
  id: 'db-sink',
  name: 'warehouse',
  dbName: 'warehouse',
} satisfies DatabaseResponse

describe('pipeline wizard helpers', () => {
  it('detects pipeline creation intent without treating read-only queries as creation', () => {
    expect(isPipelineWizardIntent('파이프라인 생성해줘')).toBe(true)
    expect(isPipelineWizardIntent('파이프라인 연결해줘')).toBe(true)
    expect(isPipelineWizardIntent('create a new pipeline')).toBe(true)
    expect(isPipelineWizardIntent('파이프라인 상태 조회')).toBe(false)
    expect(isPipelineWizardIntent('파이프라인 연결 상태 확인')).toBe(false)
    expect(isPipelineWizardIntent('DB 연결 상태 봐줘')).toBe(false)
  })

  it('matches the chat send gate for starting the wizard', () => {
    expect(pipelineWizardStartCandidate('  파이프라인 생성해줘  ', { running: false })).toBe('파이프라인 생성해줘')
    expect(pipelineWizardStartCandidate('파이프라인 생성해줘', { running: true })).toBeNull()
    expect(pipelineWizardStartCandidate('/pipelines', { running: false })).toBeNull()
  })

  it('builds the existing createPipeline API payload for direct pipelines', () => {
    const selections: PipelineWizardSelections = {
      pattern: 'direct',
      sourceDbId: 'db-source',
      sinkDbId: 'db-sink',
      table: { schema: 'public', name: 'orders' },
      name: '  order mirror  ',
    }

    expect(buildPipelineCreateInput(selections)).toEqual({
      name: 'order mirror',
      alias: null,
      pattern: 'direct',
      sourceDbId: 'db-source',
      sinkDbId: 'db-sink',
      schema: 'public',
      table: 'orders',
    })
  })

  it('includes a trimmed Korean alias when provided', () => {
    const selections: PipelineWizardSelections = {
      pattern: 'fan-out',
      sourceDbId: 'db-source',
      sinkDbId: null,
      table: { schema: 'public', name: 'orders' },
      name: 'orders-events',
      alias: '  주문 이벤트  ',
    }
    expect(buildPipelineCreateInput(selections).alias).toBe('주문 이벤트')
  })

  it('omits sinkDbId for fan-out payloads', () => {
    const selections: PipelineWizardSelections = {
      pattern: 'fan-out',
      sourceDbId: 'db-source',
      sinkDbId: 'db-sink',
      table: { schema: 'public', name: 'orders' },
      name: 'orders events',
    }

    expect(buildPipelineCreateInput(selections).sinkDbId).toBeNull()
  })

  it('blocks creation only when actual readiness says BLOCKED', () => {
    expect(readinessBlocked(blockedReadiness)).toBe(true)
    expect(readinessAllowsCreate(okReadiness, null, 'fan-out')).toBe(true)
    expect(readinessAllowsCreate(okReadiness, okReadiness, 'direct')).toBe(true)
    expect(readinessAllowsCreate(blockedReadiness, okReadiness, 'direct')).toBe(false)
    expect(readinessAllowsCreate(okReadiness, blockedReadiness, 'direct')).toBe(false)
  })

  it('suggests deterministic names from selected API results', () => {
    expect(suggestPipelineName(sourceDb, { schema: 'public', name: 'orders' }, 'direct', sinkDb)).toBe(
      'orders.orders to warehouse',
    )
    expect(suggestPipelineName(sourceDb, { schema: 'public', name: 'orders' }, 'fan-out', null)).toBe(
      'orders.orders fan-out',
    )
  })
})
