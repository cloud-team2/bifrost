import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, type CdcReadinessResponse, type DatabaseMetricsResponse } from '../../lib/api'
import {
  cdcStatusToNodeStatus,
  databaseStatusFromReadiness,
  databaseStatusFromReadinessResources,
  loadDatabaseReadiness,
  liveDatabaseMetrics,
  resourceFromSettled,
  rescanDatabaseDetailResources,
  worstNodeStatus,
} from './DatabaseDetail'

describe('DatabaseDetail helpers', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('maps readiness status to database status without treating unknown data as healthy', () => {
    expect(cdcStatusToNodeStatus('OK')).toBe('healthy')
    expect(cdcStatusToNodeStatus('WARNING')).toBe('warning')
    expect(cdcStatusToNodeStatus('BLOCKED')).toBe('warning') // #807: readiness 문제는 warning
    expect(cdcStatusToNodeStatus(null)).toBeNull()
  })

  it('keeps unreachable connection status ahead of fresh readiness', () => {
    expect(databaseStatusFromReadiness({ status: 'healthy', connectionStatus: 'UNREACHABLE' }, 'OK')).toBe('error')
    expect(databaseStatusFromReadiness({ status: 'warning', connectionStatus: 'HEALTHY' }, 'OK')).toBe('healthy')
    expect(databaseStatusFromReadiness({ status: 'warning', connectionStatus: null }, null)).toBe('warning')
  })

  it('aggregates source and sink readiness into the worst visible database status', () => {
    const ok: CdcReadinessResponse = { overallStatus: 'OK', checks: [] }
    const blocked: CdcReadinessResponse = { overallStatus: 'BLOCKED', checks: [] }

    expect(worstNodeStatus(['healthy', 'warning', 'error'])).toBe('error')
    expect(databaseStatusFromReadinessResources(
      { status: 'healthy', connectionStatus: 'HEALTHY' },
      { data: ok, loading: false, error: null, loaded: true },
      { data: blocked, loading: false, error: null, loaded: true },
    )).toBe('warning') // #807: sink BLOCKED도 warning (연결 끊김만 error)
    expect(databaseStatusFromReadinessResources(
      { status: 'healthy', connectionStatus: 'HEALTHY' },
      { data: ok, loading: false, error: null, loaded: true },
      { data: null, loading: false, error: 'sink failed', loaded: true },
    )).toBe('warning')
  })

  it('treats backend metric placeholders as absent data', () => {
    const placeholder: DatabaseMetricsResponse = {
      tps: 0,
      queryResponseMs: 0,
      queryResponseP95Ms: null,
      activeConnections: 0,
      stub: true,
    }
    const live: DatabaseMetricsResponse = {
      tps: 0,
      queryResponseMs: 0,
      queryResponseP95Ms: 0,
      activeConnections: 0,
      stub: false,
    }

    expect(liveDatabaseMetrics(null)).toBeNull()
    expect(liveDatabaseMetrics(placeholder)).toBeNull()
    expect(liveDatabaseMetrics(live)).toBe(live)
  })

  it('preserves fulfilled readiness results and converts rejected sections to errors', () => {
    const readiness: CdcReadinessResponse = {
      overallStatus: 'BLOCKED',
      checks: [
        { name: 'wal_level = logical', status: 'BLOCKED', actual: 'replica', expected: 'logical', hint: 'enable logical WAL' },
      ],
    }

    expect(resourceFromSettled({ status: 'fulfilled', value: readiness }, 'failed')).toEqual({
      data: readiness,
      loading: false,
      error: null,
      loaded: true,
    })
    expect(resourceFromSettled({ status: 'rejected', reason: new Error('sink failed') }, 'failed')).toEqual({
      data: null,
      loading: false,
      error: 'sink failed',
      loaded: true,
    })
    expect(resourceFromSettled({ status: 'rejected', reason: 'no message' }, 'failed')).toEqual({
      data: null,
      loading: false,
      error: 'failed',
      loaded: true,
    })
  })

  it('loads source and sink readiness independently', async () => {
    const source: CdcReadinessResponse = { overallStatus: 'OK', checks: [] }
    const sinkError = new Error('sink failed')
    const client = {
      cdcReadiness: vi.fn().mockResolvedValue(source),
      sinkReadiness: vi.fn().mockRejectedValue(sinkError),
    }

    const withSink = loadDatabaseReadiness(client, 'ws-1', 'db-1', true)
    expect(client.cdcReadiness).toHaveBeenCalledWith('ws-1', 'db-1')
    expect(client.sinkReadiness).toHaveBeenCalledWith('ws-1', 'db-1')
    await expect(withSink.source).resolves.toBe(source)
    await expect(withSink.sink).rejects.toBe(sinkError)

    client.cdcReadiness.mockClear()
    client.sinkReadiness.mockClear()
    const withoutSink = loadDatabaseReadiness(client, 'ws-1', 'db-1', false)
    expect(client.cdcReadiness).toHaveBeenCalledWith('ws-1', 'db-1')
    expect(client.sinkReadiness).not.toHaveBeenCalled()
    await expect(withoutSink.sink).resolves.toBeNull()
  })

  it('rescans all detail resources and reports partial failure', async () => {
    const success = vi.fn().mockResolvedValue('ok')
    const failure = vi.fn().mockRejectedValue(new Error('failed'))

    await expect(rescanDatabaseDetailResources([success, failure])).resolves.toEqual({ failed: true })
    expect(success).toHaveBeenCalledTimes(1)
    expect(failure).toHaveBeenCalledTimes(1)
  })

  it('calls the database metrics endpoint with the selected workspace and database id', async () => {
    const body: DatabaseMetricsResponse = {
      tps: 12.5,
      queryResponseMs: 4,
      queryResponseP95Ms: 7,
      activeConnections: 3,
      stub: false,
    }
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(body), {
      status: 200,
      statusText: 'OK',
      headers: { 'Content-Type': 'application/json' },
    }))
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('localStorage', {
      getItem: vi.fn(() => null),
      setItem: vi.fn(),
      removeItem: vi.fn(),
    })

    await expect(api.databaseMetrics('ws-1', 'db-1')).resolves.toEqual(body)
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/workspaces/ws-1/databases/db-1/metrics',
      expect.objectContaining({ method: 'GET' }),
    )
  })
})
