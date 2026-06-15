import { describe, expect, it } from 'vitest'
import { datasourceToNode } from './mappers'
import type { DatabaseResponse } from './api'

function db(overrides: Partial<DatabaseResponse>): DatabaseResponse {
  return {
    id: 'ds-1',
    name: 'maria-sink',
    engine: 'MARIADB',
    host: 'h',
    port: 3307,
    dbName: 'testdb',
    username: 'u',
    password: '',
    cdcReadinessStatus: null,
    sinkReadinessStatus: null,
    connectionStatus: 'HEALTHY',
    connectionError: null,
    connectionCheckedAt: null,
    roles: [],
    createdAt: '2026-06-15',
    ...overrides,
  }
}

describe('datasourceToNode status (#734)', () => {
  it('연결 끊김(UNREACHABLE)은 error', () => {
    expect(datasourceToNode(db({ connectionStatus: 'UNREACHABLE' })).status).toBe('error')
  })

  it('sink 전용 DB: CDC(소스) BLOCKED여도 error가 아니라 warning', () => {
    // sink는 REPLICATION/RELOAD(소스용)가 없어 cdc=BLOCKED지만 sink로는 정상 → 거짓 error 금지
    const node = datasourceToNode(db({ cdcReadinessStatus: 'BLOCKED', sinkReadinessStatus: 'OK' }))
    expect(node.status).toBe('warning')
  })

  it('CDC OK는 healthy', () => {
    expect(datasourceToNode(db({ cdcReadinessStatus: 'OK' })).status).toBe('healthy')
  })

  it('CDC WARNING은 warning', () => {
    expect(datasourceToNode(db({ cdcReadinessStatus: 'WARNING' })).status).toBe('warning')
  })
})
