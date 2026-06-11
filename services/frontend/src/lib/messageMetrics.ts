import type { KafkaMessageRecord } from './api'

export function messageSizeBytes(message: KafkaMessageRecord): number | null {
  const size = message.sizeBytes
  return typeof size === 'number' && Number.isFinite(size) && size >= 0 ? size : null
}

export function formatMessageSize(bytes: number | null): string {
  if (bytes == null) return '-'
  if (bytes < 1000) return `${bytes} B`
  if (bytes < 1_000_000) return `${(bytes / 1000).toFixed(1)} KB`
  return `${(bytes / 1_000_000).toFixed(1)} MB`
}
