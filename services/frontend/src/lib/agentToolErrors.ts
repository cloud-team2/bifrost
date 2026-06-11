import { ApiError } from './api'

const GUIDANCE_ERROR_CODES = new Set(['RESOURCE_NOT_FOUND', 'VALIDATION_FAILED'])
const POLICY_DENIED_CODE = 'POLICY_DENIED'

export type AgentToolErrorFeedback = {
  kind: 'guidance' | 'blocked'
  message: string
}

export function isAgentToolGuidanceError(error: unknown): boolean {
  return agentToolErrorFeedback(error)?.kind === 'guidance'
}

export function agentToolGuidanceMessage(error: unknown): string | null {
  const feedback = agentToolErrorFeedback(error)
  return feedback?.kind === 'guidance' ? feedback.message : null
}

export function agentToolErrorFeedback(error: unknown): AgentToolErrorFeedback | null {
  const apiError = asAgentToolApiError(error)
  const message = apiError?.message.trim()
  if (!apiError || !message) return null
  if (apiError.status >= 400 && apiError.status < 500 && GUIDANCE_ERROR_CODES.has(apiError.code)) {
    return { kind: 'guidance', message }
  }
  if (apiError.code === POLICY_DENIED_CODE) return { kind: 'blocked', message }
  return null
}

function asAgentToolApiError(error: unknown): Pick<ApiError, 'status' | 'code' | 'message'> | null {
  if (error instanceof ApiError) return error
  if (error == null || typeof error !== 'object') return null
  const record = error as Record<string, unknown>
  return typeof record.status === 'number' &&
    typeof record.code === 'string' &&
    typeof record.message === 'string'
    ? { status: record.status, code: record.code, message: record.message }
    : null
}
