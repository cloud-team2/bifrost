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
}
interface StatusMsg {
  id: number
  kind: 'status'
  eventType: string
  agent: string | null
  text: string
  state: 'running' | 'done' | 'failed' | 'waiting'
}
interface ToolMsg {
  id: number
  kind: 'tool'
  key: string
  toolName: string
  detail: string
  status: 'running' | 'completed' | 'failed'
  summary: string | null
}
interface EvidenceMsg {
  id: number
  kind: 'evidence'
  evidenceId: string
  evidenceType: string
  summary: string
  redactionStatus: string | null
}
interface ReportMsg {
  id: number
  kind: 'report'
  title: string
  text: string
  verified: boolean | null
  confidence: number | null
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
interface DoneMsg {
  id: number
  kind: 'done'
  text: string
  success: boolean
}

type AgentMsg = TextMsg | StatusMsg | ToolMsg | EvidenceMsg | ReportMsg | ApprovalMsg | DoneMsg

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
      updateMsgs((m) => [
        ...m,
        {
          id: ++seq.current,
          kind: 'status',
          eventType: 'run_created',
          agent: null,
          text: `Agent Run ${run.run_id} 생성됨 — SSE 연결 중`,
          state: 'running',
        },
      ])
      openEventStream(run.run_id)
    } catch (e) {
      setRunning(false)
      runningRef.current = false
      const msg = e instanceof ApiError ? e.message : 'Agent Run 생성에 실패했습니다'
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
      updateMsgs((m) => [
        ...m,
        {
          id: ++seq.current,
          kind: 'status',
          eventType: 'stream_reconnecting',
          agent: null,
          text: 'SSE 연결이 끊겨 재연결을 시도합니다.',
          state: 'waiting',
        },
      ])
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
    if (normalized.event_id && seenEvents.current.has(normalized.event_id)) return
    if (normalized.event_id) seenEvents.current.add(normalized.event_id)
    applyAgentEvent(normalized)
  }

  function applyAgentEvent(event: AgentRunEvent) {
    if (event.type === 'debug_trace') return
    if (event.type === 'tool_call_started') {
      upsertTool(event, 'running')
      return
    }
    if (event.type === 'tool_call_completed') {
      upsertTool(event, 'completed')
      return
    }
    if (event.type === 'tool_call_failed') {
      upsertTool(event, 'failed')
      return
    }
    if (event.type === 'evidence_collected') {
      updateMsgs((m) => [
        ...m,
        {
          id: ++seq.current,
          kind: 'evidence',
          evidenceId: payloadString(event, 'evidence_id') ?? event.event_id,
          evidenceType: payloadString(event, 'evidence_type') ?? 'evidence',
          summary: payloadString(event, 'summary') ?? event.message,
          redactionStatus: payloadString(event, 'redaction_status'),
        },
      ])
      return
    }
    if (event.type === 'report_preview' || event.type === 'report_preview_available') {
      updateMsgs((m) => [
        ...m,
        {
          id: ++seq.current,
          kind: 'report',
          title: 'Report preview',
          text: event.message,
          verified: payloadBoolean(event, 'verified'),
          confidence: payloadNumber(event, 'confidence'),
        },
      ])
      return
    }
    if (event.type === 'partial_result') {
      const answer = payloadString(event, 'answer')
      if (answer) appendText('assistant', answer)
      else appendStatus(event, 'done')
      return
    }
    if (event.type === 'approval_required') {
      const id = ++seq.current
      const actionId = payloadString(event, 'action_id') ?? 'unknown_action'
      const approvalId = payloadString(event, 'approval_id')
      updateMsgs((m) => [
        ...m,
        {
          id,
          kind: 'approval',
          runId: event.run_id,
          actionId,
          approvalId,
          reason: payloadString(event, 'reason') ?? event.message,
          message: event.message,
          state: 'pending',
          error: null,
        },
      ])
      if (!approvalId) queueApprovalHydration(id, event.run_id, actionId)
      return
    }
    if (event.type === 'change_management_required') {
      appendStatus(event, 'waiting')
      return
    }
    if (event.type === 'run_completed') {
      const failed = typeof event.payload.error === 'string' || event.message.startsWith('오류')
      updateMsgs((m) => [
        ...m,
        { id: ++seq.current, kind: 'done', text: event.message || 'Agent Run이 완료되었습니다.', success: !failed },
      ])
      setRunning(false)
      runningRef.current = false
      esRef.current?.close()
      runCompleteHandler.current?.(!failed)
      runCompleteHandler.current = null
      toast(failed ? 'Agent Run 실패' : 'Agent Run 완료', failed ? 'error' : 'success')
      return
    }

    const doneTypes: AgentStreamingEventType[] = [
      'agent_completed',
      'execution_completed',
      'verification_completed',
      'run_started',
    ]
    const waitingTypes: AgentStreamingEventType[] = ['execution_started', 'agent_started']
    appendStatus(event, doneTypes.includes(event.type) ? 'done' : waitingTypes.includes(event.type) ? 'running' : 'waiting')
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

  function upsertTool(event: AgentRunEvent, status: ToolMsg['status']) {
    const toolName = payloadString(event, 'tool') ?? 'tool'
    const key = `${event.run_id}:${payloadString(event, 'step_id') ?? toolName}`
    const summary = payloadString(event, 'summary') ?? (status === 'running' ? null : event.message)
    updateMsgs((prev) => {
      let found = false
      const next = prev.map((msg) => {
        if (msg.kind !== 'tool' || msg.key !== key) return msg
        found = true
        return { ...msg, toolName, detail: event.message, status, summary }
      })
      if (found) return next
      return [
        ...next,
        { id: ++seq.current, kind: 'tool', key, toolName, detail: event.message, status, summary },
      ]
    })
  }

  function queueApprovalHydration(messageId: number, runId: string, actionId: string, attempt = 0) {
    window.setTimeout(async () => {
      const approvalId = await fetchApprovalId(runId, actionId)
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
          if (m.kind === 'tool') return <ToolCard key={m.id} msg={m} theme={theme} />
          if (m.kind === 'evidence') return <EvidenceCard key={m.id} msg={m} />
          if (m.kind === 'report') return <ReportCard key={m.id} msg={m} />
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
          return <DoneCard key={m.id} msg={m} />
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
          'max-w-[88%] rounded-xl px-3 py-2 text-[12.5px] leading-relaxed',
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
      <div className="leading-relaxed text-gray-600">{msg.text}</div>
    </div>
  )
}

function ToolCard({ msg, theme }: { msg: ToolMsg; theme: (typeof THEMES)[ThemeName] }) {
  return (
    <div className="rounded-lg border border-gray-200 bg-white">
      <div className="flex items-center gap-1.5 border-b border-gray-100 px-3 py-1.5 font-mono text-[11px] text-gray-500">
        {msg.status === 'running' ? (
          <Spinner size={11} />
        ) : msg.status === 'failed' ? (
          <Icon name="alert" size={11} className="text-rose-500" />
        ) : (
          <Icon name="zap" size={11} className={theme.icon} />
        )}
        <span className="font-semibold text-gray-700">{msg.toolName}</span>
        <span className="text-gray-300">·</span>
        <span className="truncate text-gray-400">{msg.detail}</span>
      </div>
      {msg.summary ? (
        <pre className="overflow-x-auto whitespace-pre-wrap px-3 py-2.5 font-mono text-[10.5px] leading-relaxed text-gray-700">
          {msg.summary}
        </pre>
      ) : (
        <div className="flex items-center gap-2 px-3 py-2.5 text-[11.5px] text-gray-400">
          <Spinner size={12} />
          도구 호출 중…
        </div>
      )}
    </div>
  )
}

function EvidenceCard({ msg }: { msg: EvidenceMsg }) {
  return (
    <div className="rounded-xl border border-sky-200 bg-sky-50 px-3 py-2.5 text-[12px] text-sky-900">
      <div className="mb-1 flex items-center gap-1.5 font-semibold">
        <Icon name="book" size={13} />
        Evidence · {msg.evidenceType}
      </div>
      <div className="leading-relaxed">{msg.summary}</div>
      <div className="mt-1 font-mono text-[10.5px] text-sky-600">
        {msg.evidenceId}{msg.redactionStatus ? ` · ${msg.redactionStatus}` : ''}
      </div>
    </div>
  )
}

function ReportCard({ msg }: { msg: ReportMsg }) {
  return (
    <div className="rounded-xl border border-amber-200 bg-amber-50 px-3 py-2.5 text-[12px] text-amber-900">
      <div className="mb-1 flex items-center gap-1.5 font-semibold">
        <Icon name="log" size={13} />
        {msg.title}
        {msg.verified === false && <span className="rounded bg-amber-100 px-1.5 py-0.5 text-[10px]">검증 전</span>}
        {msg.confidence !== null && <span className="text-[10.5px] text-amber-700">{Math.round(msg.confidence * 100)}%</span>}
      </div>
      <div className="leading-relaxed">{msg.text}</div>
    </div>
  )
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
    <div className="rounded-xl border border-amber-200 bg-white">
      <div className="flex items-center gap-1.5 border-b border-amber-100 bg-amber-50 px-3 py-2 text-[12px] font-semibold text-amber-900">
        <Icon name="shield" size={13} />
        승인 필요 · {msg.actionId}
      </div>
      <div className="space-y-1.5 px-3 py-2.5 text-[12px] text-gray-700">
        <div className="leading-relaxed">{msg.message}</div>
        <div className="text-[11.5px] text-gray-500">{msg.reason}</div>
        <div className="font-mono text-[10.5px] text-gray-400">{msg.approvalId ?? 'approval id pending'}</div>
        {(msg.error || help) && <div className={cn('text-[11.5px]', msg.error || roleError ? 'text-rose-600' : 'text-gray-400')}>{msg.error ?? help}</div>}
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

function DoneCard({ msg }: { msg: DoneMsg }) {
  return (
    <div
      className={cn(
        'rounded-xl border px-3 py-2.5 text-[12.5px] leading-relaxed',
        msg.success ? 'border-emerald-200 bg-emerald-50 text-emerald-800' : 'border-rose-200 bg-rose-50 text-rose-800',
      )}
    >
      <div className="mb-1 flex items-center gap-1.5 font-semibold">
        <Icon name={msg.success ? 'check' : 'alert'} size={13} strokeWidth={msg.success ? 3 : 2} />
        {msg.success ? '완료' : '실패'}
      </div>
      {msg.text}
    </div>
  )
}

function payloadString(event: AgentRunEvent, key: string): string | null {
  const value = event.payload[key]
  return typeof value === 'string' ? value : null
}

function payloadNumber(event: AgentRunEvent, key: string): number | null {
  const value = event.payload[key]
  return typeof value === 'number' ? value : null
}

function payloadBoolean(event: AgentRunEvent, key: string): boolean | null {
  const value = event.payload[key]
  return typeof value === 'boolean' ? value : null
}
