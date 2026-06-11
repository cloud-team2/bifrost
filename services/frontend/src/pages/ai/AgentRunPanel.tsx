import { useEffect, useRef, useState } from 'react'
import { Icon, type IconName } from '../../components/Icon'
import { Spinner } from '../../components/ui'
import { useToast } from '../../components/Toast'
import { useApp, type AgentTask } from '../../store/AppStore'
import {
  ApiError,
  api,
  type AgentRunEvent,
  type AgentStreamingEventType,
  type ApprovalDecisionValue,
  type WorkspaceMemberRole,
} from '../../lib/api'
import { cn } from '../../lib/format'

const AGENT_EVENT_TYPES: AgentStreamingEventType[] = [
  'run_started',
  'agent_started',
  'agent_completed',
  'tool_call_started',
  'tool_call_completed',
  'tool_call_failed',
  'evidence_collected',
  'report_preview_available',
  'report_preview',
  'partial_result',
  'approval_required',
  'change_management_required',
  'execution_started',
  'execution_completed',
  'verification_completed',
  'run_completed',
  'debug_trace',
]

const AGENT_LABEL: Record<string, string> = {
  router: 'Router',
  correlation: 'Correlation',
  planner: 'Planner',
  retrieval: 'Retrieval',
  classifier: 'Classifier',
  rca: 'RCA',
  remediation: 'Remediation',
  policy_guard: 'Policy Guard',
  approval_gate: 'Approval Gate',
  change_gate: 'Change Gate',
  executor: 'Executor',
  verifier: 'Verifier',
  report: 'Report',
}

const ROLE_LABEL: Record<WorkspaceMemberRole, string> = {
  OWNER: '소유자',
  ADMIN: '관리자',
  MEMBER: '멤버',
}

const RISK_LABEL: Record<AgentTask['risk'], string> = { low: '낮음', medium: '중간', high: '높음' }

const DONE_PROGRESS_EVENTS: AgentStreamingEventType[] = [
  'agent_completed',
  'execution_completed',
  'verification_completed',
  'run_started',
]
const RUNNING_PROGRESS_EVENTS: AgentStreamingEventType[] = ['execution_started', 'agent_started']

const THEMES = {
  brand: {
    avatar: 'bg-gradient-to-br from-brand-500 to-violet-600',
    userBubble: 'rounded-br-sm bg-brand-600 text-white',
    icon: 'text-brand-500',
    statusText: 'text-brand-700',
    quick: 'border-brand-200 bg-brand-50 text-brand-700 hover:bg-brand-100 disabled:opacity-50',
    inputFocus: 'focus:border-brand-400',
    send: 'bg-brand-600 hover:bg-brand-700 disabled:bg-brand-300',
    approve: 'bg-brand-600 hover:bg-brand-700 disabled:bg-brand-300',
  },
  violet: {
    avatar: 'bg-gradient-to-br from-violet-400 to-violet-600',
    userBubble: 'rounded-br-sm bg-violet-600 text-white',
    icon: 'text-violet-500',
    statusText: 'text-violet-700',
    quick: 'border-violet-200 bg-violet-50 text-violet-700 hover:bg-violet-100 disabled:opacity-50',
    inputFocus: 'focus:border-violet-400',
    send: 'bg-violet-600 hover:bg-violet-700 disabled:bg-violet-300',
    approve: 'bg-violet-600 hover:bg-violet-700 disabled:bg-violet-300',
  },
} as const

interface TextMsg {
  id: number
  kind: 'text'
  role: 'user' | 'assistant'
  text: string
  dedupeKey?: string
}
type ProgressState = 'running' | 'done' | 'failed' | 'waiting'
interface StatusMsg {
  id: number
  kind: 'status'
  eventType: string
  agent: string | null
  text: string
  state: ProgressState
}
interface ProgressItem {
  key: string
  kind: 'stage' | 'tool' | 'system'
  eventType: string
  agent: string | null
  label: string
  text: string
  state: ProgressState
  summary: string | null
}
interface ProgressMsg {
  id: number
  kind: 'progress'
  runId: string
  expanded: boolean
  terminalState: Extract<ProgressState, 'done' | 'failed'> | null
  terminalText: string | null
  items: ProgressItem[]
}
interface ApprovalMsg {
  id: number
  kind: 'approval'
  runId: string
  actionId: string
  approvalId: string | null
  reason: string
  message: string
  state: 'pending' | 'submitting' | 'approved' | 'rejected' | 'error'
  error: string | null
}
type AgentMsg = TextMsg | StatusMsg | ProgressMsg | ApprovalMsg

type ThemeName = keyof typeof THEMES

interface AgentRunPanelProps {
  title: string
  subtitle: string
  icon: IconName
  accent: ThemeName
  initialMessage?: string
  quickActions: string[]
  inputPlaceholder: string
  runningPlaceholder: string
  hitlLabel?: string
}

export function AgentRunPanel({
  title,
  subtitle,
  icon,
  accent,
  initialMessage,
  quickActions,
  inputPlaceholder,
  runningPlaceholder,
  hitlLabel,
}: AgentRunPanelProps) {
  const app = useApp()
  const toast = useToast()
  const theme = THEMES[accent]
  const [msgs, setMsgs] = useState<AgentMsg[]>(
    initialMessage ? [{ id: 1, kind: 'text', role: 'assistant', text: initialMessage }] : [],
  )
  const [input, setInput] = useState('')
  const [running, setRunning] = useState(false)
  const [roleState, setRoleState] = useState<{
    loading: boolean
    role: WorkspaceMemberRole | null
    error: string | null
  }>({ loading: false, role: null, error: null })

  const seq = useRef(initialMessage ? 1 : 0)
  const msgsRef = useRef<AgentMsg[]>(msgs)
  const esRef = useRef<EventSource | null>(null)
  const runningRef = useRef(false)
  const seenEvents = useRef<Set<string>>(new Set())
  const finalAnswerRunIds = useRef<Set<string>>(new Set())
  const scroll = useRef<HTMLDivElement>(null)
  const taskHandled = useRef(false)
  const runCompleteHandler = useRef<((success: boolean) => void) | null>(null)

  const wsId = app.currentProject?.id ?? null
  const myEmail = app.currentUser?.email ?? ''
  const canApprove = roleState.role === 'OWNER' || roleState.role === 'ADMIN'

  function updateMsgs(updater: (prev: AgentMsg[]) => AgentMsg[]) {
    setMsgs((prev) => {
      const next = updater(prev)
      msgsRef.current = next
      return next
    })
  }

  useEffect(() => {
    msgsRef.current = msgs
  }, [msgs])

  useEffect(() => {
    runningRef.current = running
  }, [running])

  useEffect(() => {
    scroll.current?.scrollTo({ top: scroll.current.scrollHeight, behavior: 'smooth' })
  }, [msgs])

  useEffect(() => {
    if (!wsId || !myEmail) {
      setRoleState({ loading: false, role: null, error: null })
      return
    }
    let cancelled = false
    setRoleState({ loading: true, role: null, error: null })
    api
      .listMembers(wsId)
      .then((members) => {
        if (cancelled) return
        const role = members.find((m) => m.email === myEmail)?.role ?? null
        setRoleState({ loading: false, role, error: null })
      })
      .catch((e) => {
        if (cancelled) return
        setRoleState({
          loading: false,
          role: null,
          error: e instanceof ApiError ? e.message : '승인 권한을 확인하지 못했습니다',
        })
      })
    return () => {
      cancelled = true
    }
  }, [wsId, myEmail])

  useEffect(() => () => esRef.current?.close(), [])

  useEffect(() => {
    if (!app.agentTask) {
      taskHandled.current = false
      return
    }
    if (taskHandled.current) return
    taskHandled.current = true
    const task = app.agentTask
    app.consumeAgentTask()
    startTask(task)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [app.agentTask])

  function appendText(role: TextMsg['role'], text: string) {
    updateMsgs((m) => [...m, { id: ++seq.current, kind: 'text', role, text }])
  }

  async function startRun(
    rawMessage: string,
    options: {
      visibleUserText?: string
      incidentId?: string | null
      remediationRequested?: boolean
      onCompleted?: (success: boolean) => void
    } = {},
  ) {
    const message = rawMessage.trim()
    if (!message || running) return
    if (!wsId) {
      appendText('assistant', '프로젝트를 선택한 뒤 Agent Run을 시작할 수 있습니다.')
      return
    }

    esRef.current?.close()
    seenEvents.current.clear()
    finalAnswerRunIds.current.clear()
    runCompleteHandler.current = options.onCompleted ?? null
    setRunning(true)
    runningRef.current = true
    if (options.visibleUserText) appendText('user', options.visibleUserText)

    try {
      const run = await api.createAgentRun({
        project_id: wsId,
        message,
        incident_id: options.incidentId ?? null,
        remediation_requested: options.remediationRequested ?? false,
        stream: true,
      })
      app.setAgentRunState({
        runId: run.run_id,
        status: 'starting',
        lastEventType: 'run_created',
        lastMessage: `Agent Run ${run.run_id} 생성됨`,
      })
      upsertProgress(run.run_id, {
        key: `${run.run_id}:run`,
        kind: 'system',
        eventType: 'run_created',
        agent: null,
        label: 'Run',
        text: `Agent Run ${run.run_id} 생성됨 — SSE 연결 중`,
        state: 'running',
        summary: null,
      })
      openEventStream(run.run_id)
    } catch (e) {
      setRunning(false)
      runningRef.current = false
      const msg = e instanceof ApiError ? e.message : 'Agent Run 생성에 실패했습니다'
      app.setAgentRunState({ status: 'failed', lastEventType: 'run_create_failed', lastMessage: msg })
      appendText('assistant', msg)
      toast(msg, 'error')
    }
  }

  function send(text: string) {
    const v = text.trim()
    if (!v || running) return
    setInput('')
    void startRun(v, { visibleUserText: v })
  }

  function startTask(task: AgentTask) {
    const message = [
      `Incident action request`,
      `incident_id=${task.incidentId}`,
      `action_id=${task.actionId}`,
      `title=${task.incidentTitle}`,
      `action=${task.label}`,
      `detail=${task.detail}`,
      `risk=${task.risk}`,
      `estimated_time=${task.estimatedTime}`,
      '승인이 필요한 조치이면 approval_required 이벤트를 발행하고, 승인 후 실제 실행 경로로 진행하세요.',
    ].join('\n')
    appendText(
      'assistant',
      `인시던트 '${task.incidentTitle}'의 권장 조치를 Agent Run으로 생성합니다. 조치: ${task.label} · 리스크 ${RISK_LABEL[task.risk]} · 예상 ${task.estimatedTime}`,
    )
    void startRun(message, {
      visibleUserText: `인시던트 조치 요청: ${task.label}`,
      incidentId: task.incidentId,
      remediationRequested: true,
      onCompleted: (success) => {
        if (success) app.runIncidentAction(task.incidentId, task.actionId)
      },
    })
  }

  function openEventStream(runId: string) {
    const es = new EventSource(api.agentRunEventUrl(runId))
    esRef.current = es

    AGENT_EVENT_TYPES.forEach((type) => {
      es.addEventListener(type, (event) => handleStreamEvent(type, event as MessageEvent<string>))
    })
    es.onmessage = (event) => handleStreamEvent('partial_result', event as MessageEvent<string>)
    es.onerror = () => {
      if (!runningRef.current) return
      upsertProgress(runId, {
        key: `${runId}:stream`,
        kind: 'system',
        eventType: 'stream_reconnecting',
        agent: null,
        label: 'SSE',
        text: 'SSE 연결이 끊겨 재연결을 시도합니다.',
        state: 'waiting',
        summary: null,
      })
    }
  }

  function handleStreamEvent(eventType: AgentStreamingEventType, event: MessageEvent<string>) {
    let parsed: AgentRunEvent
    try {
      parsed = JSON.parse(event.data) as AgentRunEvent
    } catch {
      updateMsgs((m) => [
        ...m,
        {
          id: ++seq.current,
          kind: 'status',
          eventType,
          agent: null,
          text: '해석할 수 없는 Agent SSE payload를 수신했습니다.',
          state: 'failed',
        },
      ])
      return
    }
    const normalized: AgentRunEvent = { ...parsed, type: parsed.type ?? eventType, payload: parsed.payload ?? {} }
    const duplicate = normalized.event_id && seenEvents.current.has(normalized.event_id)
    resolveStreamReconnect(normalized.run_id)
    if (duplicate) return
    if (normalized.event_id) seenEvents.current.add(normalized.event_id)
    app.setAgentRunState({
      runId: normalized.run_id,
      status: agentRunStatusFromEvent(normalized),
      lastEventType: normalized.type,
      lastMessage: normalized.message,
    })
    applyAgentEvent(normalized)
  }

  function applyAgentEvent(event: AgentRunEvent) {
    if (event.type === 'debug_trace') return
    if (event.type === 'tool_call_started') {
      upsertTool(event, 'running')
      return
    }
    if (event.type === 'tool_call_completed') {
      upsertTool(event, 'done')
      return
    }
    if (event.type === 'tool_call_failed') {
      upsertTool(event, 'failed')
      return
    }
    if (event.type === 'evidence_collected') {
      return
    }
    if (event.type === 'report_preview' || event.type === 'report_preview_available') {
      upsertAssistantText(runAnswerKey(event.run_id), event.message)
      return
    }
    if (event.type === 'partial_result') {
      const answer = payloadString(event, 'answer')
      const stage = payloadString(event, 'stage')
      if (answer) {
        appendProgressStatus(event, 'done')
        upsertFinalAssistantAnswer(event.run_id, answer)
      } else if (stage === 'rca') {
        appendProgressStatus(event, 'done')
      } else appendProgressStatus(event, 'done')
      return
    }
    if (event.type === 'approval_required') {
      const actionId = payloadString(event, 'action_id') ?? 'unknown_action'
      const approvalId = payloadString(event, 'approval_id')
      const approval = upsertApproval({
        runId: event.run_id,
        actionId,
        approvalId,
        reason: payloadString(event, 'reason') ?? event.message,
        message: event.message,
      })
      if (approval.needsHydration) queueApprovalHydration(approval.id, event.run_id, actionId)
      return
    }
    if (event.type === 'change_management_required') {
      appendStatus(event, 'waiting')
      return
    }
    if (event.type === 'run_completed') {
      const failed = typeof event.payload.error === 'string' || event.message.startsWith('오류')
      settleProgress(event.run_id, failed ? 'failed' : 'done', event.message)
      if (failed) {
        upsertFinalAssistantAnswer(event.run_id, failureSummary(event))
      } else {
        const answer = payloadString(event, 'answer')
        if (answer) {
          upsertFinalAssistantAnswer(event.run_id, answer)
        } else if (!finalAnswerRunIds.current.has(event.run_id) && !hasAssistantText(runAnswerKey(event.run_id))) {
          upsertFinalAssistantAnswer(event.run_id, event.message || 'Agent Run이 완료되었습니다.')
        }
      }
      setRunning(false)
      runningRef.current = false
      esRef.current?.close()
      runCompleteHandler.current?.(!failed)
      runCompleteHandler.current = null
      toast(failed ? 'Agent Run 실패' : 'Agent Run 완료', failed ? 'error' : 'success')
      return
    }

    appendProgressStatus(event, progressStateForEvent(event))
  }

  function appendStatus(event: AgentRunEvent, state: StatusMsg['state']) {
    updateMsgs((m) => [
      ...m,
      {
        id: ++seq.current,
        kind: 'status',
        eventType: event.type,
        agent: event.agent,
        text: event.message,
        state,
      },
    ])
  }

  function appendProgressStatus(event: AgentRunEvent, state: ProgressState) {
    upsertProgress(event.run_id, {
      key: progressKey(event),
      kind: 'stage',
      eventType: event.type,
      agent: event.agent,
      label: progressLabel(event),
      text: event.message,
      state,
      summary: null,
    })
  }

  function upsertTool(event: AgentRunEvent, state: ProgressState) {
    const toolName = payloadString(event, 'tool') ?? 'tool'
    const key = `${event.run_id}:${payloadString(event, 'step_id') ?? toolName}`
    const summary = payloadString(event, 'summary') ?? (state === 'running' ? null : event.message)
    upsertProgress(event.run_id, {
      key: `tool:${key}`,
      kind: 'tool',
      eventType: event.type,
      agent: event.agent,
      label: toolName,
      text: event.message,
      state,
      summary,
    })
  }

  function upsertProgress(runId: string, item: ProgressItem) {
    updateMsgs((prev) => {
      const progressIndex = prev.findIndex((msg): msg is ProgressMsg => msg.kind === 'progress' && msg.runId === runId)
      if (progressIndex < 0) {
        return [
          ...prev,
          { id: ++seq.current, kind: 'progress', runId, expanded: false, terminalState: null, terminalText: null, items: [item] },
        ]
      }
      return prev.map((msg, index) => {
        if (index !== progressIndex || msg.kind !== 'progress') return msg
        const itemIndex = msg.items.findIndex((existing) => existing.key === item.key)
        if (itemIndex < 0) return { ...msg, items: [...msg.items, item] }
        return {
          ...msg,
          items: msg.items.map((existing, existingIndex) =>
            existingIndex === itemIndex
              ? {
                  ...existing,
                  ...item,
                  state: mergeProgressState(existing.state, item.state),
                  summary: item.summary ?? existing.summary,
                }
              : existing,
          ),
        }
      })
    })
  }

  function settleProgress(runId: string, state: Extract<ProgressState, 'done' | 'failed'>, text: string) {
    updateMsgs((prev) =>
      prev.map((msg) => {
        if (msg.kind !== 'progress' || msg.runId !== runId) return msg
        return {
          ...msg,
          terminalState: state,
          terminalText: text || (state === 'done' ? 'Agent Run이 완료되었습니다.' : 'Agent Run이 실패했습니다.'),
          items: msg.items.map((item) =>
            item.state === 'running' || item.state === 'waiting' ? { ...item, state } : item,
          ),
        }
      }),
    )
  }

  function toggleProgress(messageId: number) {
    updateMsgs((m) =>
      m.map((msg) => (msg.kind === 'progress' && msg.id === messageId ? { ...msg, expanded: !msg.expanded } : msg)),
    )
  }

  function resolveStreamReconnect(runId: string) {
    updateMsgs((prev) => {
      let changed = false
      const next = prev.map((msg) => {
        if (msg.kind !== 'progress' || msg.runId !== runId) return msg
        let itemsChanged = false
        const items = msg.items.map((item) => {
          if (item.eventType !== 'stream_reconnecting' || item.state === 'done') return item
          itemsChanged = true
          return { ...item, text: 'SSE 연결이 복구되었습니다.', state: 'done' as const }
        })
        changed = changed || itemsChanged
        return itemsChanged ? { ...msg, items } : msg
      })
      return changed ? next : prev
    })
  }

  function upsertFinalAssistantAnswer(runId: string, text: string) {
    const nextText = text.trim()
    if (!nextText) return
    const finalKey = runAnswerKey(runId)
    updateMsgs((prev) => {
      let foundFinal = false
      const next = prev.map((msg) => {
        if (msg.kind !== 'text' || msg.role !== 'assistant') return msg
        if (msg.dedupeKey === finalKey) {
          foundFinal = true
          return { ...msg, text: nextText }
        }
        return msg
      })
      if (foundFinal) return next
      return [...next, { id: ++seq.current, kind: 'text', role: 'assistant', text: nextText, dedupeKey: finalKey }]
    })
    finalAnswerRunIds.current.add(runId)
  }

  function upsertAssistantText(dedupeKey: string, text: string) {
    const nextText = text.trim()
    if (!nextText) return false
    updateMsgs((prev) => {
      let found = false
      const next = prev.map((msg) => {
        if (msg.kind !== 'text' || msg.role !== 'assistant' || msg.dedupeKey !== dedupeKey) return msg
        found = true
        return { ...msg, text: nextText }
      })
      return found ? next : [...next, { id: ++seq.current, kind: 'text', role: 'assistant', text: nextText, dedupeKey }]
    })
    return true
  }

  function hasAssistantText(dedupeKey: string) {
    return msgsRef.current.some((msg) => msg.kind === 'text' && msg.role === 'assistant' && msg.dedupeKey === dedupeKey)
  }

  function upsertApproval({
    runId,
    actionId,
    approvalId,
    reason,
    message,
  }: {
    runId: string
    actionId: string
    approvalId: string | null
    reason: string
    message: string
  }) {
    const existing = msgsRef.current.find((msg): msg is ApprovalMsg => isSameApproval(msg, runId, actionId, approvalId))
    const id = existing?.id ?? ++seq.current
    const shouldRetryHydration =
      !approvalId && !!existing && !existing.approvalId && existing.state !== 'approved' && existing.state !== 'rejected' && !!existing.error
    updateMsgs((prev) => {
      const existingIndex = prev.findIndex((msg) => isSameApproval(msg, runId, actionId, approvalId))
      if (existingIndex < 0) {
        return [
          ...prev,
          { id, kind: 'approval', runId, actionId, approvalId, reason, message, state: 'pending', error: null },
        ]
      }
      return prev.map((msg, index) =>
        index === existingIndex && msg.kind === 'approval'
          ? {
              ...msg,
              runId,
              actionId,
              approvalId: approvalId ?? msg.approvalId,
              reason,
              message,
              error: approvalId || shouldRetryHydration ? null : msg.error,
            }
          : msg,
      )
    })
    return { id, needsHydration: !approvalId && (!existing || shouldRetryHydration) }
  }

  function queueApprovalHydration(messageId: number, runId: string, actionId: string, attempt = 0) {
    window.setTimeout(async () => {
      const current = msgsRef.current.find((msg): msg is ApprovalMsg => msg.kind === 'approval' && msg.id === messageId)
      if (!current || current.approvalId) return
      const approvalId = await fetchApprovalId(runId, actionId)
      const latest = msgsRef.current.find((msg): msg is ApprovalMsg => msg.kind === 'approval' && msg.id === messageId)
      if (!latest || latest.approvalId) return
      if (approvalId) {
        updateApproval(messageId, { approvalId, error: null })
        return
      }
      if (attempt < 4) {
        queueApprovalHydration(messageId, runId, actionId, attempt + 1)
      } else {
        updateApproval(messageId, { error: 'approval id를 아직 찾지 못했습니다. 잠시 후 다시 시도하세요.' })
      }
    }, 400 + attempt * 500)
  }

  async function fetchApprovalId(runId: string, actionId: string): Promise<string | null> {
    try {
      const approvals = await api.listAgentRunApprovals(runId)
      const match = approvals.pending.find((a) => a.action_id === actionId) ?? approvals.pending[0]
      return match?.approval_id ?? null
    } catch {
      return null
    }
  }

  function updateApproval(messageId: number, patch: Partial<ApprovalMsg>) {
    updateMsgs((m) =>
      m.map((msg) => (msg.kind === 'approval' && msg.id === messageId ? { ...msg, ...patch } : msg)),
    )
  }

  async function submitApproval(messageId: number, decision: ApprovalDecisionValue) {
    const msg = msgsRef.current.find((m): m is ApprovalMsg => m.kind === 'approval' && m.id === messageId)
    if (!msg || msg.state === 'submitting') return
    if (!canApprove) {
      toast('승인은 OWNER 또는 ADMIN만 가능합니다', 'error')
      return
    }
    const approvalId = msg.approvalId ?? (await fetchApprovalId(msg.runId, msg.actionId))
    if (!approvalId) {
      updateApproval(messageId, { error: '승인 요청 ID를 확인하지 못했습니다.' })
      toast('승인 요청 ID를 확인하지 못했습니다', 'error')
      return
    }
    updateApproval(messageId, { approvalId, state: 'submitting', error: null })
    try {
      await api.approvalDecision(approvalId, {
        decision,
        comment: `${myEmail || 'operator'}: ${decision}`,
      })
      updateApproval(messageId, { state: decision, error: null })
      toast(decision === 'approved' ? '승인을 반영했습니다' : '거절을 반영했습니다')
    } catch (e) {
      const error = e instanceof ApiError ? e.message : '승인 결정 반영에 실패했습니다'
      updateApproval(messageId, { state: 'error', error })
      toast(error, 'error')
    }
  }

  return (
    <div className="flex flex-1 flex-col overflow-hidden">
      <div className="flex items-center gap-2 border-b border-gray-200 px-4 py-3">
        <div className={cn('flex h-7 w-7 items-center justify-center rounded-md text-white', theme.avatar)}>
          <Icon name={icon} size={15} />
        </div>
        <div className="min-w-0 flex-1">
          <div className="text-[13px] font-semibold text-gray-900">{title}</div>
          <div className="truncate text-[11px] text-gray-400">{subtitle}</div>
        </div>
        {hitlLabel && <div className="text-[10.5px] font-semibold uppercase tracking-wide text-amber-600">{hitlLabel}</div>}
      </div>

      <div ref={scroll} className="flex-1 space-y-3 overflow-y-auto scroll-thin bg-gray-50 px-4 py-4">
        {msgs.map((m) => {
          if (m.kind === 'text') return <TextBubble key={m.id} msg={m} theme={theme} />
          if (m.kind === 'status') return <StatusCard key={m.id} msg={m} theme={theme} />
          if (m.kind === 'progress') {
            return <ProgressCard key={m.id} msg={m} theme={theme} onToggle={() => toggleProgress(m.id)} />
          }
          if (m.kind === 'approval') {
            return (
              <ApprovalCard
                key={m.id}
                msg={m}
                theme={theme}
                canApprove={canApprove}
                roleLabel={roleState.role ? ROLE_LABEL[roleState.role] : null}
                roleLoading={roleState.loading}
                roleError={roleState.error}
                onApprove={() => submitApproval(m.id, 'approved')}
                onReject={() => submitApproval(m.id, 'rejected')}
              />
            )
          }
          return null
        })}
      </div>

      <div className="flex flex-wrap gap-1.5 border-t border-gray-100 px-3 pt-2.5">
        {quickActions.map((q) => (
          <button
            key={q}
            onClick={() => send(q)}
            disabled={running}
            className={cn('rounded-full border px-2.5 py-1 text-[11px] font-medium', theme.quick)}
          >
            {q}
          </button>
        ))}
      </div>
      <div className="flex items-center gap-2 px-3 py-2.5">
        <input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && send(input)}
          placeholder={running ? runningPlaceholder : inputPlaceholder}
          disabled={running}
          className={cn(
            'h-9 flex-1 rounded-lg border border-gray-200 bg-gray-50 px-3 text-[13px] outline-none focus:bg-white disabled:opacity-60',
            theme.inputFocus,
          )}
        />
        <button
          onClick={() => send(input)}
          disabled={!input.trim() || running}
          className={cn('flex h-9 w-9 items-center justify-center rounded-lg text-white', theme.send)}
        >
          <Icon name="send" size={15} />
        </button>
      </div>
    </div>
  )
}

function TextBubble({ msg, theme }: { msg: TextMsg; theme: (typeof THEMES)[ThemeName] }) {
  return (
    <div className={cn('flex', msg.role === 'user' ? 'justify-end' : 'justify-start')}>
      <div
        className={cn(
          'max-w-[88%] whitespace-pre-wrap break-words rounded-xl px-3 py-2 text-[12.5px] leading-relaxed',
          msg.role === 'user'
            ? theme.userBubble
            : 'rounded-bl-sm border border-gray-200 bg-white text-gray-700',
        )}
      >
        {msg.text}
      </div>
    </div>
  )
}

function StatusCard({ msg, theme }: { msg: StatusMsg; theme: (typeof THEMES)[ThemeName] }) {
  const label = msg.agent ? AGENT_LABEL[msg.agent] ?? msg.agent : msg.eventType
  return (
    <div className="rounded-xl border border-gray-200 bg-white px-3 py-2.5 text-[12px] text-gray-700">
      <div className="mb-1 flex items-center gap-1.5 font-semibold text-gray-800">
        {msg.state === 'running' ? (
          <Spinner size={13} />
        ) : msg.state === 'failed' ? (
          <Icon name="alert" size={13} className="text-rose-500" />
        ) : msg.state === 'waiting' ? (
          <Icon name="clock" size={13} className="text-amber-500" />
        ) : (
          <Icon name="check" size={13} strokeWidth={3} className="text-emerald-500" />
        )}
        <span className={msg.state === 'running' ? theme.statusText : undefined}>{label}</span>
      </div>
      <div className="break-words leading-relaxed text-gray-600">{msg.text}</div>
    </div>
  )
}

function ProgressCard({
  msg,
  theme,
  onToggle,
}: {
  msg: ProgressMsg
  theme: (typeof THEMES)[ThemeName]
  onToggle: () => void
}) {
  const failed = msg.items.find((item) => item.state === 'failed')
  const running = msg.items.find((item) => item.state === 'running')
  const waiting = msg.items.find((item) => item.state === 'waiting')
  const completedCount = msg.items.filter((item) => item.state === 'done').length
  const latest = msg.items[msg.items.length - 1]
  const visualState = progressVisualState({ terminalState: msg.terminalState, failed, running, waiting })
  const headline = progressHeadline({
    terminalState: msg.terminalState,
    terminalText: msg.terminalText,
    failed,
    running,
    waiting,
    latest,
  })

  return (
    <div className="rounded-lg border border-gray-200 bg-white/70 text-[11.5px] text-gray-600">
      <button
        type="button"
        onClick={onToggle}
        className="flex w-full items-center gap-2 px-3 py-2 text-left"
      >
        <div className="flex min-w-0 flex-1 items-center gap-2">
          {visualState === 'running' ? (
            <Spinner size={12} />
          ) : visualState === 'failed' ? (
            <Icon name="alert" size={12} className="text-rose-500" />
          ) : visualState === 'waiting' ? (
            <Icon name="clock" size={12} className="text-amber-500" />
          ) : (
            <Icon name="check" size={12} strokeWidth={3} className="text-emerald-500" />
          )}
          <span className="shrink-0 font-semibold text-gray-800">진행 단계</span>
          <span className="shrink-0 rounded bg-gray-100 px-1.5 py-0.5 font-mono text-[10.5px] text-gray-500">
            {completedCount}/{msg.items.length}
          </span>
          <span className={cn('truncate', visualState === 'running' ? theme.statusText : 'text-gray-500')}>{headline}</span>
        </div>
        <Icon name={msg.expanded ? 'chevron-up' : 'chevron-down'} size={13} className="shrink-0 text-gray-400" />
      </button>
      {msg.expanded && (
        <div className="max-h-80 space-y-2 overflow-y-auto border-t border-gray-100 px-3 py-2.5">
          {msg.items.map((item) => (
            <div key={item.key} className="grid grid-cols-[14px_minmax(0,1fr)] gap-2">
              <ProgressDot state={item.state} />
              <div className="min-w-0">
                <div className="flex min-w-0 items-center gap-1.5">
                  <span className="truncate font-semibold text-gray-700">{item.label}</span>
                  <span className="shrink-0 rounded bg-gray-100 px-1.5 py-0.5 font-mono text-[10px] text-gray-400">
                    {progressEventLabel(item)}
                  </span>
                </div>
                <div className="mt-0.5 whitespace-pre-wrap break-words leading-relaxed text-gray-500">{item.text}</div>
                {item.summary && (
                  <pre className="mt-1 overflow-x-auto whitespace-pre-wrap rounded bg-gray-50 px-2 py-1.5 font-mono text-[10.5px] leading-relaxed text-gray-600">
                    {item.summary}
                  </pre>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

function ProgressDot({ state }: { state: ProgressState }) {
  if (state === 'running') return <Spinner size={12} />
  if (state === 'failed') return <Icon name="alert" size={12} className="mt-0.5 text-rose-500" />
  if (state === 'waiting') return <Icon name="clock" size={12} className="mt-0.5 text-amber-500" />
  return <Icon name="check" size={12} strokeWidth={3} className="mt-0.5 text-emerald-500" />
}

function ApprovalCard({
  msg,
  theme,
  canApprove,
  roleLabel,
  roleLoading,
  roleError,
  onApprove,
  onReject,
}: {
  msg: ApprovalMsg
  theme: (typeof THEMES)[ThemeName]
  canApprove: boolean
  roleLabel: string | null
  roleLoading: boolean
  roleError: string | null
  onApprove: () => void
  onReject: () => void
}) {
  const resolved = msg.state === 'approved' || msg.state === 'rejected'
  const disabled = msg.state === 'submitting' || resolved || !msg.approvalId || roleLoading || !canApprove
  const help = roleLoading
    ? '승인 권한 확인 중…'
    : roleError
      ? roleError
      : !canApprove
        ? `OWNER/ADMIN만 승인할 수 있습니다${roleLabel ? ` (현재 ${roleLabel})` : ''}`
        : !msg.approvalId
          ? 'approval id 확인 중…'
          : `현재 권한: ${roleLabel}`

  return (
    <div className="min-w-0 rounded-xl border border-amber-200 bg-white">
      <div className="flex min-w-0 items-center gap-1.5 border-b border-amber-100 bg-amber-50 px-3 py-2 text-[12px] font-semibold text-amber-900">
        <Icon name="shield" size={13} className="shrink-0" />
        <span className="min-w-0 break-words">승인 필요 · {msg.actionId}</span>
      </div>
      <div className="space-y-1.5 px-3 py-2.5 text-[12px] text-gray-700">
        <div className="break-words leading-relaxed">{msg.message}</div>
        <div className="break-words text-[11.5px] text-gray-500">{msg.reason}</div>
        <div className="break-all font-mono text-[10.5px] text-gray-400">{msg.approvalId ?? 'approval id pending'}</div>
        {(msg.error || help) && <div className={cn('break-words text-[11.5px]', msg.error || roleError ? 'text-rose-600' : 'text-gray-400')}>{msg.error ?? help}</div>}
      </div>
      {resolved ? (
        <div className={cn('border-t border-gray-100 px-3 py-2 text-[11.5px] font-medium', msg.state === 'approved' ? 'text-emerald-600' : 'text-rose-600')}>
          {msg.state === 'approved' ? '✓ 승인 반영됨' : '거절 반영됨'}
        </div>
      ) : (
        <div className="flex gap-2 border-t border-gray-100 px-3 py-2.5">
          <button
            onClick={onApprove}
            disabled={disabled}
            className={cn('flex flex-1 items-center justify-center gap-1.5 rounded-md py-1.5 text-[12px] font-semibold text-white', theme.approve)}
          >
            {msg.state === 'submitting' ? <Spinner size={13} /> : <Icon name="check" size={13} strokeWidth={3} />}
            승인
          </button>
          <button
            onClick={onReject}
            disabled={disabled}
            className="rounded-md border border-gray-200 px-3 py-1.5 text-[12px] font-medium text-gray-600 hover:bg-gray-50 disabled:opacity-50"
          >
            거절
          </button>
        </div>
      )}
    </div>
  )
}

function agentRunStatusFromEvent(event: AgentRunEvent) {
  if (event.type === 'run_completed') {
    return typeof event.payload.error === 'string' || event.message.startsWith('오류') ? 'failed' : 'completed'
  }
  if (event.type === 'approval_required' || event.type === 'change_management_required') return 'waiting_for_approval'
  return 'running'
}

function progressKey(event: AgentRunEvent) {
  const stage = payloadString(event, 'stage')
  if (event.type === 'run_started') return `${event.run_id}:run`
  if (event.type === 'partial_result' && (stage || event.agent)) {
    return `${event.run_id}:stage:${stage ?? event.agent}`
  }
  if ((event.type === 'agent_started' || event.type === 'agent_completed') && event.agent) {
    return `${event.run_id}:stage:${event.agent}`
  }
  if (event.type === 'execution_started' || event.type === 'execution_completed') {
    return `${event.run_id}:stage:${event.agent ?? 'executor'}`
  }
  if (event.type === 'verification_completed') return `${event.run_id}:stage:${event.agent ?? 'verifier'}`
  return `${event.run_id}:${event.type}:${event.agent ?? 'run'}`
}

function progressLabel(event: AgentRunEvent) {
  const stage = payloadString(event, 'stage')
  const agent = event.agent ?? stage
  if (agent) return AGENT_LABEL[agent] ?? agent
  if (event.type === 'run_started') return 'Run'
  return event.type
}

function progressEventLabel(item: ProgressItem) {
  if (item.kind === 'tool') return item.state === 'running' ? 'tool_call' : 'tool_done'
  if (item.eventType === 'run_created') return 'created'
  if (item.eventType === 'run_started') return 'started'
  if (item.eventType === 'agent_started') return 'started'
  if (item.eventType === 'agent_completed') return 'completed'
  if (item.eventType === 'partial_result') return 'summary'
  if (item.eventType === 'execution_started') return 'started'
  if (item.eventType === 'execution_completed') return 'completed'
  if (item.eventType === 'verification_completed') return 'verified'
  if (item.eventType === 'stream_reconnecting') return 'reconnect'
  return item.eventType
}

function progressStateForEvent(event: AgentRunEvent): ProgressState {
  if (DONE_PROGRESS_EVENTS.includes(event.type)) return 'done'
  if (RUNNING_PROGRESS_EVENTS.includes(event.type)) return 'running'
  return 'waiting'
}

function progressVisualState({
  terminalState,
  failed,
  running,
  waiting,
}: {
  terminalState: ProgressMsg['terminalState']
  failed: ProgressItem | undefined
  running: ProgressItem | undefined
  waiting: ProgressItem | undefined
}): ProgressState {
  if (terminalState) return terminalState
  if (failed) return 'failed'
  if (running) return 'running'
  if (waiting) return 'waiting'
  return 'done'
}

function progressHeadline({
  terminalState,
  terminalText,
  failed,
  running,
  waiting,
  latest,
}: {
  terminalState: ProgressMsg['terminalState']
  terminalText: string | null
  failed: ProgressItem | undefined
  running: ProgressItem | undefined
  waiting: ProgressItem | undefined
  latest: ProgressItem | undefined
}) {
  if (terminalState === 'failed') return terminalText || 'Run 실패'
  if (terminalState === 'done') return terminalText || 'Agent Run이 완료되었습니다.'
  if (failed) return `${failed.label} 실패`
  if (running) return `${running.label} 진행 중`
  if (waiting) return `${waiting.label} 대기 중`
  return latest?.text ?? '대기 중'
}

function mergeProgressState(current: ProgressState, next: ProgressState): ProgressState {
  if (current === 'failed' || next === 'failed') return 'failed'
  if (current === 'done') return 'done'
  return next
}

function runAnswerKey(runId: string) {
  return `${runId}:answer`
}

function failureSummary(event: AgentRunEvent) {
  const error = payloadString(event, 'error')
  return event.message || error || 'Agent Run 처리 중 오류가 발생했습니다.'
}

function isSameApproval(msg: AgentMsg, runId: string, actionId: string, approvalId: string | null) {
  if (msg.kind !== 'approval') return false
  if (approvalId && msg.approvalId === approvalId) return true
  return msg.runId === runId && msg.actionId === actionId
}

function payloadString(event: AgentRunEvent, key: string): string | null {
  const value = event.payload[key]
  return typeof value === 'string' ? value : null
}
