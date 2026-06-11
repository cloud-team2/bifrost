import { describe, expect, it } from 'vitest'
import { pipelineModalSteps, tableReadinessStatus } from './CreatePipelineModal'
import type { SchemaTable } from '../../lib/api'

function table(primaryKey = true): SchemaTable {
  return {
    schema: 'public',
    name: primaryKey ? 'orders' : 'events',
    columns: [
      { name: 'id', type: 'bigint', nullable: false, primaryKey, indexed: primaryKey },
      { name: 'payload', type: 'jsonb', nullable: true, primaryKey: false, indexed: false },
    ],
  }
}

describe('CreatePipelineModal helpers', () => {
  it('orders direct pipeline steps as table before sink', () => {
    expect(pipelineModalSteps('direct')).toEqual(['연결 방식', 'Source DB', '테이블', 'Sink DB', '확인'])
  })

  it('omits sink selection for fan-out pipelines', () => {
    expect(pipelineModalSteps('fan-out')).toEqual(['연결 방식', 'Source DB', '테이블', '확인'])
  })

  it('derives per-table readiness badges from real schema and source readiness data', () => {
    expect(tableReadinessStatus(table(true), 'OK')).toBe('OK')
    expect(tableReadinessStatus(table(false), 'OK')).toBe('WARNING')
    expect(tableReadinessStatus(table(true), 'BLOCKED')).toBe('BLOCKED')
    expect(tableReadinessStatus(table(true), null)).toBeNull()
    expect(tableReadinessStatus({ ...table(true), columns: [] }, null)).toBeNull()
  })
})
