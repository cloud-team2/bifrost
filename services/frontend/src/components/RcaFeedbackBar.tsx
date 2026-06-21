import { useState } from 'react'
import { api, ApiError, type RcaFeedbackResponse, type RcaFeedbackVerdict } from '../lib/api'
import { Icon } from './Icon'

// #964 수정 시 고르는 root cause 후보(8-layer 카탈로그의 대표 id). 자유 입력도 허용(datalist).
const ROOT_CAUSE_OPTIONS = [
  'CONNECTOR_TASK_FAILED',
  'SINK_DB_CONNECTION_TIMEOUT',
  'SOURCE_DB_CONNECTION_TIMEOUT',
  'SOURCE_NETWORK_REACHABILITY',
  'CONSUMER_LAG_SPIKE',
  'SCHEMA_MISMATCH',
  'SINK_AUTH_EXPIRED',
  'SOURCE_AUTH_EXPIRED',
  'SINK_CONSTRAINT_VIOLATION',
  'SINK_WRITE_LATENCY',
  'BROKER_RESOURCE_PRESSURE',
  'PIPELINE_CONFIG_INVALID',
  'TOPIC_INGRESS_SPIKE',
  'CONSUMER_REBALANCE_LOOP',
]

export function rcaVerdictKo(verdict: string): string {
  switch ((verdict ?? '').toUpperCase()) {
    case 'ACCEPTED':
      return '원인 맞음'
    case 'REJECTED':
      return '원인 아님'
    case 'CORRECTED':
      return '원인 수정'
    default:
      return verdict
  }
}

/**
 * #964 RCA 결과에 대한 운영자 피드백(맞음/아님/수정). 축적되면 평가·캘리브레이션용 gold set 이 된다.
 */
export function RcaFeedbackBar({
  wsId,
  incidentId,
  rcaRootCauseId,
  rcaConfidence,
  runId,
  existing,
  onSubmitted,
}: {
  wsId: string
  incidentId: string
  rcaRootCauseId?: string | null
  rcaConfidence?: number | null
  runId?: string | null
  existing: RcaFeedbackResponse[]
  onSubmitted?: () => void
}) {
  const latest = existing[0] ?? null
  const [correcting, setCorrecting] = useState(false)
  const [corrected, setCorrected] = useState('')
  const [submitting, setSubmitting] = useState<RcaFeedbackVerdict | null>(null)
  const [error, setError] = useState<string | null>(null)

  const submit = async (verdict: RcaFeedbackVerdict, correctedRootCauseId?: string) => {
    if (submitting) return
    setSubmitting(verdict)
    setError(null)
    try {
      await api.submitIncidentRcaFeedback(wsId, incidentId, {
        verdict,
        runId: runId ?? null,
        rcaRootCauseId: rcaRootCauseId ?? null,
        rcaConfidence: rcaConfidence ?? null,
        correctedRootCauseId: correctedRootCauseId ?? null,
      })
      setCorrecting(false)
      setCorrected('')
      onSubmitted?.()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '피드백을 저장하지 못했습니다')
    } finally {
      setSubmitting(null)
    }
  }

  const btn = 'inline-flex items-center gap-1 rounded-md border px-2.5 py-1 text-[11.5px] font-medium disabled:opacity-50'

  return (
    <div className="mt-3 rounded-lg border border-gray-100 bg-gray-50 px-3 py-2.5">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-[11.5px] font-semibold text-gray-600">이 분석이 맞나요?</span>
        {latest && (
          <span className="rounded bg-white px-1.5 py-0.5 text-[10.5px] text-gray-500">
            최근 평가 · <b className="text-gray-700">{rcaVerdictKo(latest.verdict)}</b>
            {latest.correctedRootCauseId ? ` → ${latest.correctedRootCauseId}` : ''}
          </span>
        )}
        <div className="flex items-center gap-1.5">
          <button
            onClick={() => submit('accepted')}
            disabled={!!submitting}
            className={`${btn} border-[#b6e0c2] bg-white text-[#157f4a] hover:bg-[#f1faf4]`}
          >
            <Icon name="check" size={11} /> 원인 맞음
          </button>
          <button
            onClick={() => submit('rejected')}
            disabled={!!submitting}
            className={`${btn} border-[#e3b5ad] bg-white text-[#c0392b] hover:bg-[#fcf3f2]`}
          >
            원인 아님
          </button>
          <button
            onClick={() => setCorrecting((v) => !v)}
            disabled={!!submitting}
            className={`${btn} border-gray-200 bg-white text-gray-600 hover:bg-white`}
          >
            원인 수정
          </button>
        </div>
      </div>

      {correcting && (
        <div className="mt-2 flex flex-wrap items-center gap-2">
          <input
            list="rca-root-cause-options"
            value={corrected}
            onChange={(e) => setCorrected(e.target.value)}
            placeholder="올바른 root cause (예: SINK_DB_CONNECTION_TIMEOUT)"
            className="min-w-[260px] flex-1 rounded-md border border-gray-200 px-2 py-1 font-mono text-[11px] text-gray-700 focus:border-gray-400 focus:outline-none"
          />
          <datalist id="rca-root-cause-options">
            {ROOT_CAUSE_OPTIONS.map((id) => (
              <option key={id} value={id} />
            ))}
          </datalist>
          <button
            onClick={() => submit('corrected', corrected.trim())}
            disabled={!corrected.trim() || !!submitting}
            className={`${btn} border-transparent bg-[#0d0d0d] text-white hover:bg-black`}
          >
            저장
          </button>
        </div>
      )}

      {error && <div className="mt-1.5 text-[11px] text-[#c0392b]">{error}</div>}
      <div className="mt-1.5 text-[10px] text-gray-400">피드백은 RCA 정확도 평가·보정(gold set)에 사용됩니다.</div>
    </div>
  )
}
