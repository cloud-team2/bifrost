export interface ChatEvidence {
  evidenceId: string | null
  evidenceType: string | null
  traceId: string | null
  pipelineId: string | null
}

export interface TraceLinkedEvidence extends ChatEvidence {
  evidenceType: 'trace'
  traceId: string
  pipelineId: string
}

export function hasTraceEvidenceLink(evidence: ChatEvidence): evidence is TraceLinkedEvidence {
  return evidence.evidenceType === 'trace' && !!evidence.traceId && !!evidence.pipelineId
}

function evidenceDedupeKey(evidence: ChatEvidence): string | null {
  const evidenceId = evidence.evidenceId?.trim()
  return evidenceId ? evidenceId : null
}

export function shouldAppendEvidenceCard(existingEvidence: readonly ChatEvidence[], evidence: ChatEvidence): boolean {
  if (!hasTraceEvidenceLink(evidence)) return false
  const dedupeKey = evidenceDedupeKey(evidence)
  return !dedupeKey || !existingEvidence.some((existing) => evidenceDedupeKey(existing) === dedupeKey)
}
