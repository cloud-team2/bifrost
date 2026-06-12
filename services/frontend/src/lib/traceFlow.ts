import type { TraceSpanResponse, TraceSummaryResponse } from './api'

/** 지연 분해 막대(#614/#632)용 세그먼트 상태. */
export type FlowStatus = 'ok' | 'error' | 'neutral'

export interface LatencySegment {
  key: 'publish' | 'transit' | 'consume'
  label: string
  service: string | null
  /** 마이크로초(#632). 1ms 미만 구간도 0이 안 되도록 µs로 다룬다. */
  micros: number
  /** false면 직접 측정값이 아니라 총시간에서 추정한 구간(topic·전파). */
  measured: boolean
  status: FlowStatus
}

export interface LatencyBreakdown {
  segments: LatencySegment[]
  totalMicros: number
  hasSink: boolean
}

const PUBLISH_RE = /publish|produce|send/i
const CONSUME_RE = /process|consume|receive/i

function statusOf(s?: TraceSpanResponse): FlowStatus {
  if (!s) return 'neutral'
  return s.status === 'error' ? 'error' : 'ok'
}

/**
 * 데이터플레인 trace를 "지연 분해" 막대용 세그먼트로 환산한다(#614, #632 µs 정밀도).
 * publish(소스 produce)/consume(싱크 consume) span을 이름 규칙으로 식별하고, 규칙에 안 맞으면 순서로 추정한다.
 * 측정 구간(Debezium/Sink)은 µs 정밀, 그 사이 미측정 구간(topic·전파)은 총시간에서 측정합을 빼 추정한다.
 * (총시간은 Tempo search가 ms 정밀이라 µs로 환산한다.)
 */
export function buildLatencyBreakdown(trace: TraceSummaryResponse): LatencyBreakdown {
  const spans = trace.spans
  const publish = spans.find((s) => PUBLISH_RE.test(s.name))
  const consume = spans.find((s) => s !== publish && CONSUME_RE.test(s.name))
  const rest = spans.filter((s) => s !== publish && s !== consume)
  const pub = publish ?? rest.shift()
  const con = consume ?? rest.shift()

  const totalMicros = Math.max(0, Math.round(trace.durationMs * 1000))
  const segments: LatencySegment[] = []
  if (pub) {
    segments.push({ key: 'publish', label: 'Debezium', service: pub.service, micros: pub.durationMicros, measured: true, status: statusOf(pub) })
  }
  const measured = (pub?.durationMicros ?? 0) + (con?.durationMicros ?? 0)
  const transit = Math.max(0, totalMicros - measured)
  if (pub && transit > 0) {
    segments.push({ key: 'transit', label: 'topic·전파', service: null, micros: transit, measured: false, status: 'neutral' })
  }
  if (con) {
    segments.push({ key: 'consume', label: 'Sink', service: con.service, micros: con.durationMicros, measured: true, status: statusOf(con) })
  }

  return { segments, totalMicros, hasSink: !!con }
}
