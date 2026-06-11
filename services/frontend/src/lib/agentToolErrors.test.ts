import { describe, expect, it } from 'vitest'
import { ApiError, extractApiErrorPayload } from './api'
import { agentToolErrorFeedback, agentToolGuidanceMessage, isAgentToolGuidanceError } from './agentToolErrors'

describe('agent tool errors', () => {
  it('extracts real FastAPI envelope messages from HTTP errors', () => {
    const payload = extractApiErrorPayload({
      ok: false,
      request_id: 'req_123',
      error: {
        code: 'RESOURCE_NOT_FOUND',
        message: '커넥터 이름을 알려주세요.',
        retryable: false,
      },
    }, 400, 'Bad Request')

    expect(payload).toEqual({
      code: 'RESOURCE_NOT_FOUND',
      message: '커넥터 이름을 알려주세요.',
      details: [],
    })
  })

  it('falls back to real FastAPI detail when envelope messages are blank', () => {
    const payload = extractApiErrorPayload({
      ok: false,
      detail: '요청 본문을 확인하세요.',
      error: { code: 'VALIDATION_FAILED', message: '   ' },
    }, 400, 'Bad Request')

    expect(payload.message).toBe('요청 본문을 확인하세요.')
  })

  it('falls back to status text and then numeric status when no response message exists', () => {
    expect(extractApiErrorPayload({ ok: false }, 400, 'Bad Request').message).toBe('Bad Request')
    expect(extractApiErrorPayload({ ok: false }, 400, '   ').message).toBe('400')
  })

  it('keeps RESOURCE_NOT_FOUND and VALIDATION_FAILED on the conversational guidance path', () => {
    const validation = new ApiError(400, 'VALIDATION_FAILED', '파이프라인 id를 입력하세요.')
    const missing = new ApiError(400, 'RESOURCE_NOT_FOUND', '커넥터 이름을 알려주세요.')

    expect(isAgentToolGuidanceError(validation)).toBe(true)
    expect(agentToolGuidanceMessage(validation)).toBe('파이프라인 id를 입력하세요.')
    expect(isAgentToolGuidanceError(missing)).toBe(true)
    expect(agentToolGuidanceMessage(missing)).toBe('커넥터 이름을 알려주세요.')
  })

  it('surfaces policy denials without allowing the mutation', () => {
    const error = new ApiError(400, 'POLICY_DENIED', 'tool is not a read-only slash command target: pause_connector')

    expect(isAgentToolGuidanceError(error)).toBe(false)
    expect(agentToolErrorFeedback(error)).toEqual({
      kind: 'blocked',
      message: 'tool is not a read-only slash command target: pause_connector',
    })
  })

  it('leaves auth, missing tool, and server failures on the normal error path', () => {
    const errors = [
      new ApiError(401, 'UNAUTHORIZED', 'login required'),
      new ApiError(404, 'TOOL_NOT_FOUND', 'tool not found: missing'),
      new ApiError(500, 'SPRING_BACKEND_ERROR', 'backend failed'),
    ]

    errors.forEach((error) => {
      expect(isAgentToolGuidanceError(error)).toBe(false)
      expect(agentToolErrorFeedback(error)).toBeNull()
    })
  })

  it('accepts structurally equivalent ApiError objects', () => {
    expect(agentToolGuidanceMessage({
      status: 400,
      code: 'RESOURCE_NOT_FOUND',
      message: '커넥터 이름을 알려주세요.',
    })).toBe('커넥터 이름을 알려주세요.')
  })
})
