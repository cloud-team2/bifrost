import { describe, expect, it } from 'vitest'
import { formatMessageSize, messageSizeBytes } from './messageMetrics'
import type { KafkaMessageRecord } from './api'

function message(sizeBytes: number | null): KafkaMessageRecord {
  return {
    partition: 0,
    offset: 1,
    tsMs: 0,
    key: null,
    sizeBytes,
    op: null,
    before: null,
    after: null,
  }
}

describe('messageMetrics', () => {
  it('uses API-provided Kafka record sizes only when present', () => {
    expect(messageSizeBytes(message(512))).toBe(512)
    expect(messageSizeBytes(message(null))).toBeNull()
    expect(messageSizeBytes(message(-1))).toBeNull()
  })

  it('formats message sizes for table cells', () => {
    expect(formatMessageSize(null)).toBe('-')
    expect(formatMessageSize(42)).toBe('42 B')
    expect(formatMessageSize(1536)).toBe('1.5 KB')
    expect(formatMessageSize(2_500_000)).toBe('2.5 MB')
  })
})
