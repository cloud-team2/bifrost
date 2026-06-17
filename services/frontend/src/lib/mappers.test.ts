import { describe, expect, it } from 'vitest'
import { datasourceToNode, dbDisplayStatus } from './mappers'
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

describe('datasourceToNode status (#807: error=연결 끊김만)', () => {
  it('연결 끊김(UNREACHABLE)은 error', () => {
    expect(datasourceToNode(db({ connectionStatus: 'UNREACHABLE' })).status).toBe('error')
  })

  it('CDC(소스) BLOCKED는 error가 아니라 warning', () => {
    const node = datasourceToNode(db({ cdcReadinessStatus: 'BLOCKED', sinkReadinessStatus: 'OK' }))
    expect(node.status).toBe('warning')
  })

  it('sink readiness BLOCKED도 warning (목록도 sink 반영)', () => {
    const node = datasourceToNode(db({ cdcReadinessStatus: 'OK', sinkReadinessStatus: 'BLOCKED' }))
    expect(node.status).toBe('warning')
  })

  it('CDC/sink WARNING은 warning', () => {
    expect(datasourceToNode(db({ cdcReadinessStatus: 'WARNING' })).status).toBe('warning')
    expect(datasourceToNode(db({ sinkReadinessStatus: 'WARNING' })).status).toBe('warning')
  })

  it('전부 OK는 healthy', () => {
    expect(datasourceToNode(db({ cdcReadinessStatus: 'OK', sinkReadinessStatus: 'OK' })).status).toBe('healthy')
  })

  it('미점검(null)이고 연결 정상이면 healthy(connected)', () => {
    expect(datasourceToNode(db({ cdcReadinessStatus: null, sinkReadinessStatus: null })).status).toBe('healthy')
  })
})

describe('dbDisplayStatus 단일 규칙(#807)', () => {
  it('UNREACHABLE이면 readiness 무관 error', () => {
    expect(dbDisplayStatus('UNREACHABLE', 'OK', 'OK')).toBe('error')
    expect(dbDisplayStatus('UNREACHABLE', 'BLOCKED', null)).toBe('error')
  })
  it('연결 정상 + readiness WARNING/BLOCKED는 warning', () => {
    expect(dbDisplayStatus('HEALTHY', 'BLOCKED', 'OK')).toBe('warning')
    expect(dbDisplayStatus('HEALTHY', 'OK', 'WARNING')).toBe('warning')
  })
  it('연결 정상 + 전부 OK/null은 healthy', () => {
    expect(dbDisplayStatus('HEALTHY', 'OK', 'OK')).toBe('healthy')
    expect(dbDisplayStatus(null, null, null)).toBe('healthy')
  })
})
