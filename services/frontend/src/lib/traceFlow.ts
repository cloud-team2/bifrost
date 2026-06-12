import type { TraceSpanResponse, TraceSummaryResponse } from './api'

/** 지연 분해 막대(#614)용 세그먼트 상태. */
export type FlowStatus = 'ok' | 'error' | 'neutral'

export interface LatencySegment {
  key: 'publish' | 'transit' | 'consume'
  label: string
  service: string | null
  ms: number
  /** false면 직접 측정값이 아니라 총시간에서 추정한 구간(topic·전파). */
  measured: boolean
  status: FlowStatus
}

export interface LatencyBreakdown {
  segments: LatencySegment[]
  totalMs: number
  hasSink: boolean
}

const PUBLISH_RE = /publish|produce|send/i
const CONSUME_RE = /process|consume|receive/i

function statusOf(s?: TraceSpanResponse): FlowStatus {
  if (!s) return 'neutral'
  return s.status === 'error' ? 'error' : 'ok'
}

/**
 * 데이터플레인 trace를 "지연 분해" 막대용 세그먼트로 환산한다(#614).
 * publish(소스 produce)/consume(싱크 consume) span을 이름 규칙으로 식별하고, 규칙에 안 맞으면 순서로 추정한다.
 * 측정 구간(Debezium/Sink) 사이의 미측정 구간(topic·전파)은 총시간에서 측정합을 빼 추정한다.
 * span 시작시각이 없어 실제 waterfall은 만들 수 없으므로 "어디서 시간이 걸리나"만 보여준다.
 */
export function buildLatencyBreakdown(trace: TraceSummaryResponse): LatencyBreakdown {
  const spans = trace.spans
  const publish = spans.find((s) => PUBLISH_RE.test(s.name))
  const consume = spans.find((s) => s !== publish && CONSUME_RE.test(s.name))
  const rest = spans.filter((s) => s !== publish && s !== consume)
  const pub = publish ?? rest.shift()
  const con = consume ?? rest.shift()

  const segments: LatencySegment[] = []
  if (pub) {
    segments.push({ key: 'publish', label: 'Debezium', service: pub.service, ms: pub.durationMs, measured: true, status: statusOf(pub) })
  }
  const measured = (pub?.durationMs ?? 0) + (con?.durationMs ?? 0)
  const transitMs = Math.max(0, Math.round(trace.durationMs - measured))
  if (pub && transitMs > 0) {
    segments.push({ key: 'transit', label: 'topic·전파', service: null, ms: transitMs, measured: false, status: 'neutral' })
  }
  if (con) {
    segments.push({ key: 'consume', label: 'Sink', service: con.service, ms: con.durationMs, measured: true, status: statusOf(con) })
  }

  return { segments, totalMs: trace.durationMs, hasSink: !!con }
}
