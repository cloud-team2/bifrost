export function cn(...parts: Array<string | false | null | undefined>): string {
  return parts.filter(Boolean).join(' ')
}

export function timeAgo(ms: number): string {
  const diff = Date.now() - ms
  const min = Math.floor(diff / 60_000)
  if (min < 1) return 'just now'
  if (min < 60) return `${min} minute${min === 1 ? '' : 's'} ago`
  const hr = Math.floor(min / 60)
  if (hr < 24) return `${hr} hour${hr === 1 ? '' : 's'} ago`
  const day = Math.floor(hr / 24)
  return `${day} day${day === 1 ? '' : 's'} ago`
}

export function formatNum(n: number): string {
  return n.toLocaleString('en-US')
}

export function clamp(n: number, lo: number, hi: number): number {
  return Math.min(hi, Math.max(lo, n))
}

/** trace span 시간 표시(#632): 1ms 미만은 µs, 그 이상은 소수 ms(트레일링 .0 없이). */
export function fmtDuration(micros: number): string {
  if (!micros || micros <= 0) return '0'
  if (micros < 1000) return `${Math.round(micros)}µs`
  const ms = micros / 1000
  return ms < 10 ? `${Math.round(ms * 10) / 10}ms` : `${Math.round(ms)}ms`
}
