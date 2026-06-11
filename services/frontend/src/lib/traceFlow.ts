import type { TraceSpanResponse } from './api'

/** Flow 단계뷰(#565)용 단계 모델. 데이터플레인 trace를 source→topic→sink 흐름으로 환산. */
export type FlowStatus = 'ok' | 'error' | 'neutral'

export interface FlowStage {
  id: 'source' | 'publish' | 'topic' | 'consume' | 'sink'
  label: string
  kind: 'endpoint' | 'span' // endpoint=개념 노드(span 없음), span=실제 측정 구간
  durationMs: number | null
  status: FlowStatus
}

const PUBLISH_RE = /publish|produce|send/i
const CONSUME_RE = /process|consume|receive/i

function statusOf(s?: TraceSpanResponse): FlowStatus {
  if (!s) return 'neutral'
  return s.status === 'error' ? 'error' : 'ok'
}

/**
 * span 목록을 flow 단계로 환산한다. publish(소스 produce) / consume(싱크 consume) span을
 * 이름 규칙으로 식별하고, 규칙에 안 맞으면 순서로 추정한다. span이 없으면 빈 배열.
 */
export function buildFlowStages(spans: TraceSpanResponse[]): FlowStage[] {
  if (spans.length === 0) return []

  const publish = spans.find((s) => PUBLISH_RE.test(s.name))
  const consume = spans.find((s) => s !== publish && CONSUME_RE.test(s.name))
  const rest = spans.filter((s) => s !== publish && s !== consume)
  const pub = publish ?? rest.shift()
  const con = consume ?? rest.shift()

  const stages: FlowStage[] = [
    { id: 'source', label: 'Source DB', kind: 'endpoint', durationMs: null, status: statusOf(pub) },
  ]
  if (pub) {
    stages.push({ id: 'publish', label: 'Debezium', kind: 'span', durationMs: pub.durationMs, status: statusOf(pub) })
  }
  stages.push({ id: 'topic', label: 'Topic', kind: 'endpoint', durationMs: null, status: 'neutral' })
  if (con) {
    stages.push({ id: 'consume', label: 'Sink', kind: 'span', durationMs: con.durationMs, status: statusOf(con) })
    stages.push({ id: 'sink', label: 'Sink DB', kind: 'endpoint', durationMs: null, status: statusOf(con) })
  }
  return stages
}
