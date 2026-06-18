import { useEffect, useId, useRef, useState } from 'react'
import { Icon, type IconName } from '../../components/Icon'
import { Markdown } from '../../components/Markdown'
import { Spinner } from '../../components/ui'
import { CommandPalette } from '../../components/CommandPalette'
import { useToast } from '../../components/Toast'
import { useApp, type AgentTask } from '../../store/AppStore'
import {
  ApiError,
  api,
  type ActionRunCandidateInput,
  type AgentRunMode,
  type AgentThreadSummary,
  type AgentRunEvent,
  type AgentStreamingEventType,
  type ApprovalDecisionValue,
  type CdcReadinessResponse,
  type ConnectorInfo,
  type ConsumerGroupInfo,
  type DatabaseResponse,
  type PipelineResponse,
  type SchemaTable,
  type WorkspaceMemberRole,
} from '../../lib/api'
import { cn } from '../../lib/format'
import { routeAgentInput } from '../../lib/agentInputRouting'
import { hasTraceEvidenceLink, shouldAppendEvidenceCard, type ChatEvidence } from '../../lib/agentEvidence'
import { agentToolErrorFeedback } from '../../lib/agentToolErrors'
import {
  buildSlashCommands,
  slashCommandParams,
  slashSearch,
  type SlashToolCommand,
} from '../../lib/slashCommands'
import {
  buildPipelineCreateInput,
  databaseEndpoint,
  databaseLabel,
  pipelineWizardStartCandidate,
  readinessAllowsCreate,
  readinessBlocked,
  suggestPipelineName,
  tableKey,
  type PipelineWizardPattern,
  type PipelineWizardTable,
} from './pipelineWizard'

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
  router: '라우팅',
  correlation: '상관 분석',
  planner: '계획 수립',
  retrieval: '조회',
  classifier: '분류',
  rca: '근본 원인 분석',
  remediation: '권장 조치',
  policy_guard: '정책 검사',
  approval_gate: '승인 게이트',
  change_gate: '변경 게이트',
  executor: '실행',
  verifier: '검증',
  report: '리포트',
}

const ROLE_LABEL: Record<WorkspaceMemberRole, string> = {
  OWNER: '소유자',
  ADMIN: '관리자',
  MEMBER: '멤버',
}

const DONE_PROGRESS_EVENTS: AgentStreamingEventType[] = [
  'agent_completed',
  'execution_completed',
  'verification_completed',
  'run_started',
]
const RUNNING_PROGRESS_EVENTS: AgentStreamingEventType[] = ['execution_started', 'agent_started']
const GENERIC_HEADLINE_KEYS = ['summary', 'title', 'message', 'name', 'connector', 'pipeline', 'group', 'id']
const GENERIC_BADGE_KEYS = new Set(['severity', 'status', 'state', 'level', 'type', 'risk', 'health', 'result'])

const THEMES = {
  brand: {
    avatar: 'bg-[#0d0d0d]',
    userBubble: 'rounded-br-sm bg-brand-600 text-white',
    icon: 'text-brand-500',
    statusText: 'text-brand-700',
    quick: 'border-brand-200 bg-brand-50 text-brand-700 hover:bg-brand-100 disabled:opacity-50',
    inputFocus: 'focus:border-brand-400',
    send: 'bg-brand-600 hover:bg-brand-700 disabled:bg-brand-300',
    approve: 'bg-brand-600 hover:bg-brand-700 disabled:bg-brand-300',
  },
  violet: {
    avatar: 'bg-[#0d0d0d]',
    userBubble: 'rounded-br-sm bg-[#0d0d0d] text-white',
    icon: 'text-[#8a8a8a]',
    statusText: 'text-[#6b6b73]',
    quick: 'border-[#ececec] bg-[#ededed] text-[#6b6b73] hover:bg-[#ededed] disabled:opacity-50',
    inputFocus: 'focus:border-[#d9d9d9]',
    send: 'bg-[#0d0d0d] hover:bg-[#0d0d0d] disabled:bg-[#d9d9d9]',
    approve: 'bg-[#0d0d0d] hover:bg-[#0d0d0d] disabled:bg-[#d9d9d9]',
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
  // #604: run 시작 시 확정된 전체 stage 흐름 — 진행 분모를 처음부터 고정한다.
  requiredFlow: string[] | null
}
interface ToolPanelMsg {
  id: number
  kind: 'toolPanel'
  runId: string
  panelKey: string
  toolName: string
  params: Record<string, unknown>
  state: ProgressState
  summary: string | null
  result: unknown
  notice: string | null
  error: string | null
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
interface EvidenceMsg extends ChatEvidence {
  id: number
  kind: 'evidence'
  summary: string | null
  redactionStatus: string | null
}
export interface RcaPreviewMsg {
  id: number
  kind: 'rcaPreview'
  runId: string
  rootCauseId: string | null
  confidence: number | null
  verified: boolean | null
  message: string
}
export interface RemediationCandidate {
  actionId: string
  actionType: string | null
  actionName: string
  rootCauseId: string | null
  risk: string | null
  reason: string | null
  expectedEffect: string | null
  rollbackPlan: string | null
  estimatedDuration: string | null
  toolName: string | null
}
export interface RemediationMsg {
  id: number
  kind: 'remediation'
  runId: string
  message: string
  candidates: RemediationCandidate[]
}
type PipelineWizardStep = 'source' | 'target' | 'table' | 'readiness' | 'created'
type PipelineWizardLoading = 'databases' | 'schema' | 'readiness' | 'creating' | null
interface PipelineWizardMsg {
  id: number
  kind: 'pipelineWizard'
  workspaceId: string
  step: PipelineWizardStep
  databases: DatabaseResponse[]
  sourceDbId: string | null
  pattern: PipelineWizardPattern | null
  sinkDbId: string | null
  tables: SchemaTable[]
  table: PipelineWizardTable | null
  readiness: {
    source: CdcReadinessResponse | null
    sink: CdcReadinessResponse | null
  }
  name: string
  alias?: string
  created: PipelineResponse | null
  loading: PipelineWizardLoading
  error: string | null
}
type AgentMsg =
  | TextMsg
  | StatusMsg
  | ProgressMsg
  | ToolPanelMsg
  | ApprovalMsg
  | EvidenceMsg
  | RcaPreviewMsg
  | RemediationMsg
  | PipelineWizardMsg

// 주의: 이 집합은 백엔드 retrieval.py 의 _STRUCTURED_PANEL_TOOLS 와 동기화되어야 한다.
// (SSE 에이전트런에서 백엔드가 이 도구들에만 payload.result 를 실어준다.)
// 슬래시(명령탭) 실행은 executeAgentTool 결과를 직접 받으므로 이 집합과 무관하게 ToolPanelCard 로 렌더된다.
const STRUCTURED_TOOL_INTRO: Record<string, string> = {
  get_consumer_groups: 'Consumer Group의 lag 현황과 상태를 조회합니다.',
  list_pipelines: '현재 프로젝트의 파이프라인 상태를 조회합니다.',
  list_connectors: 'Kafka Connector 상태 및 Task 정보를 조회합니다.',
  analyze_event_log: '최근 2시간 이벤트 로그와 인시던트 현황을 분석합니다.',
}

// 헤더에 보일 짧은 한글 기능명. 백엔드 tools 카탈로그의 label_ko를 미러링한다(없으면 toolDescription 폴백).
const TOOL_LABEL_KO: Record<string, string> = {
  get_consumer_groups: '컨슈머 그룹 조회',
  get_consumer_lag: '컨슈머 지연 조회',
  list_pipelines: '파이프라인 상태 조회',
  list_project_pipelines: '파이프라인 목록 조회',
  list_connectors: '커넥터 목록 조회',
  get_connector_status: '커넥터 상태 조회',
  get_cluster_info: '클러스터 상태 조회',
  get_pipeline_topology: '토폴로지 조회',
  get_incident_summary: '인시던트 요약',
  get_alerts: '인시던트 목록 조회',
  get_traces: '트레이스 조회',
  get_deployments: '배포·변경 이력 조회',
  get_metrics: '지표 조회',
  search_logs: '로그 검색',
  analyze_event_log: '이벤트 로그 분석',
}

export function toolLabelKo(toolName: string): string {
  return TOOL_LABEL_KO[toolName] ?? toolDescription(toolName)
}
const SLASH_CATALOG_ERROR_MESSAGE = '도구 목록을 불러오지 못했습니다. 잠시 후 다시 시도하세요.'
const STALE_PIPELINE_WIZARD_MESSAGE = '프로젝트가 변경되어 이 파이프라인 생성 흐름을 계속할 수 없습니다. 현재 프로젝트에서 다시 시작하세요.'
// #839: get_metrics의 metric enum 한글 라벨. 미정의 값은 원문 폴백.
const METRIC_LABELS_KO: Record<string, string> = {
  pipeline_lag_seconds: '파이프라인 지연(초)',
  consumer_lag_p95: '컨슈머 지연 p95',
  consumer_commit_rate_per_sec: '컨슈머 커밋 속도/초',
  topic_ingress_messages_per_sec: '토픽 유입 메시지/초',
  source_freshness_delay_ms: '소스 신선도 지연(ms)',
  source_watermark_delay_ms: '소스 워터마크 지연(ms)',
  source_event_rate_per_sec: '소스 이벤트 속도/초',
  broker_cpu_cores: '브로커 CPU 코어',
  broker_memory_working_set_bytes: '브로커 메모리 사용량(B)',
  broker_network_receive_bytes_per_sec: '브로커 수신(B/초)',
  broker_network_transmit_bytes_per_sec: '브로커 송신(B/초)',
  broker_fs_read_bytes_per_sec: '브로커 디스크 읽기(B/초)',
  broker_fs_write_bytes_per_sec: '브로커 디스크 쓰기(B/초)',
}

type ThemeName = keyof typeof THEMES

interface AgentRunPanelProps {
  title: string
  subtitle: string
  icon: IconName
  accent: ThemeName
  initialMessage?: string
  quickActions?: string[]
  slashCommands?: boolean
  inputPlaceholder: string
  runningPlaceholder: string
  hitlLabel?: string
  /** #821 멀티 채팅방(세션) UI 활성화 — 자유 채팅 패널(BifrostAgent)에서만 true */
  multiThread?: boolean
}

export function AgentRunPanel({
  title,
  subtitle,
  icon,
  accent,
  initialMessage,
  quickActions = [],
  slashCommands = false,
  inputPlaceholder,
  runningPlaceholder,
  hitlLabel,
  multiThread = false,
}: AgentRunPanelProps) {
  const app = useApp()
  const toast = useToast()
  const theme = THEMES[accent]
  const [msgs, setMsgs] = useState<AgentMsg[]>(
    initialMessage ? [{ id: 1, kind: 'text', role: 'assistant', text: initialMessage }] : [],
  )
  const [input, setInput] = useState('')
  const [running, setRunning] = useState(false)
  const [slashState, setSlashState] = useState<{
    loading: boolean
    commands: SlashToolCommand[]
    error: string | null
  }>({ loading: false, commands: [], error: null })
  const [slashActiveIndex, setSlashActiveIndex] = useState(0)
  const [slashMenuDismissed, setSlashMenuDismissed] = useState(false)
  // 그룹형 명령 팔레트(#599 후속) — 평면 슬래시 대신 그룹 → 기능 선택.
  const [paletteOpen, setPaletteOpen] = useState(false)
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
  // #712 대화 메모리: 자유 대화도 (워크스페이스·패널)별 thread_id를 localStorage에 고정해
  // 리마운트·새로고침 후에도 같은 thread로 이어지게 한다(인시던트 채팅은 incidentId를 thread로 우선).
  // mount 시 이 thread의 이전 대화를 백엔드에서 불러와 transcript를 복원한다.
  const threadIdRef = useRef<string>(
    `chat-${(globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`)}`,
  )
  const threadRestored = useRef(false)
  // #821 멀티 채팅방(세션) — multiThread일 때만 사용.
  const [threads, setThreads] = useState<AgentThreadSummary[]>([])
  const [threadListOpen, setThreadListOpen] = useState(false)
  const [activeThreadId, setActiveThreadId] = useState<string>(threadIdRef.current)
  const [renamingId, setRenamingId] = useState<string | null>(null)
  const [renameText, setRenameText] = useState('')
  const [threadQuery, setThreadQuery] = useState('')
  const scroll = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const slashOptionRefs = useRef<Record<string, HTMLDivElement | null>>({})
  const taskHandled = useRef(false)
  const runCompleteHandler = useRef<((success: boolean) => void) | null>(null)

  const wsId = app.currentProject?.id ?? null
  const myEmail = app.currentUser?.email ?? ''
  const canApprove = roleState.role === 'OWNER' || roleState.role === 'ADMIN'
  const slashQuery = slashCommands ? slashSearch(input) : null
  const slashOptions = slashQuery == null
    ? []
    : slashState.commands.filter((command) => command.slug.includes(slashQuery) || command.toolName.includes(slashQuery))
  const slashMenuOpen = slashCommands && !running && !slashMenuDismissed && slashQuery != null && (slashState.loading || !!slashState.error || slashOptions.length > 0)
  const activeSlashCommand = slashOptions[Math.min(slashActiveIndex, Math.max(slashOptions.length - 1, 0))]
  const slashMenuId = useId()
  const activeSlashOptionId = activeSlashCommand ? `${slashMenuId}-${activeSlashCommand.slug}` : undefined

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
    updateMsgs((prev) =>
      prev.map((msg) =>
        msg.kind === 'pipelineWizard' && msg.workspaceId !== wsId && msg.step !== 'created'
          ? { ...msg, loading: null, error: STALE_PIPELINE_WIZARD_MESSAGE }
          : msg,
      ),
    )
  }, [wsId])

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

  // #821 멀티 채팅방 헬퍼 ----------------------------------------------------
  const activeThreadTitle = threads.find((t) => t.id === activeThreadId)?.title ?? null

  function greetingMsgs(): AgentMsg[] {
    return initialMessage
      ? [{ id: ++seq.current, kind: 'text' as const, role: 'assistant' as const, text: initialMessage }]
      : []
  }

  async function refreshThreads() {
    if (!multiThread || !wsId || !myEmail) return
    try {
      setThreads((await api.listThreads(wsId, myEmail)).threads)
    } catch {
      /* 목록 갱신 실패는 무시 */
    }
  }

  async function restoreTranscript(id: string) {
    try {
      const res = await api.listThreadMessages(id)
      const restored: AgentMsg[] = res.messages
        .filter((m) => m.content?.trim())
        .map((m) => ({
          id: ++seq.current,
          kind: 'text' as const,
          role: (m.role === 'user' ? 'user' : 'assistant') as 'user' | 'assistant',
          text: m.content,
        }))
      updateMsgs(() =>
        restored.length
          ? [
              ...greetingMsgs(),
              { id: ++seq.current, kind: 'text' as const, role: 'assistant' as const, text: '— 이전 대화 —' },
              ...restored,
            ]
          : greetingMsgs(),
      )
    } catch {
      updateMsgs(() => greetingMsgs())
    }
  }

  function activateThread(id: string) {
    threadIdRef.current = id
    setActiveThreadId(id)
    try {
      if (wsId) window.localStorage.setItem(`bifrost.chat.active.${wsId}`, id)
    } catch {
      /* localStorage 불가 시 세션 한정 */
    }
  }

  async function switchThread(id: string) {
    setThreadListOpen(false)
    if (runningRef.current || id === activeThreadId) return
    activateThread(id)
    await restoreTranscript(id)
  }

  function newChat() {
    setThreadListOpen(false)
    if (runningRef.current) return
    const id = `chat-${globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`}`
    activateThread(id)
    updateMsgs(() => greetingMsgs())
    setInput('')
    inputRef.current?.focus()
  }

  async function commitRename(id: string) {
    const next = renameText.trim()
    setRenamingId(null)
    if (!next) return
    setThreads((prev) => prev.map((t) => (t.id === id ? { ...t, title: next } : t)))
    try {
      await api.renameThread(id, next)
    } catch {
      refreshThreads()
    }
  }

  async function removeThread(id: string) {
    setThreads((prev) => prev.filter((t) => t.id !== id))
    try {
      await api.deleteThread(id)
    } catch {
      /* 삭제 실패는 무시 */
    }
    if (id === activeThreadId) newChat()
  }
  // ------------------------------------------------------------------------

  // #712 대화 메모리: 워크스페이스·패널별 thread_id를 localStorage에 고정하고, 이 thread의
  // 이전 대화를 불러와 transcript를 복원한다(리마운트·새로고침 후에도 대화가 이어져 보이게).
  useEffect(() => {
    if (!wsId || threadRestored.current) return
    if (multiThread && !myEmail) return // 소유자(이메일) 준비 후 진행
    threadRestored.current = true

    // #821 멀티 채팅방: 내 스레드 목록 로드 + 마지막 활성(없으면 최근) 스레드 복원.
    if (multiThread) {
      let cancelled = false
      let last: string | null = null
      try {
        last = window.localStorage.getItem(`bifrost.chat.active.${wsId}`)
      } catch {
        /* noop */
      }
      api
        .listThreads(wsId, myEmail)
        .then((res) => {
          if (cancelled) return
          setThreads(res.threads)
          const has = (id: string | null): id is string => !!id && res.threads.some((t) => t.id === id)
          const target = has(last) ? last : res.threads[0]?.id ?? threadIdRef.current
          activateThread(target)
          if (has(target)) restoreTranscript(target)
        })
        .catch(() => {
          /* 목록 로드 실패는 무시 — 새 대화로 시작 */
        })
      return () => {
        cancelled = true
      }
    }

    const storageKey = `bifrost.chat.thread.${wsId}.${title}`
    let threadId: string | null = null
    try {
      threadId = window.localStorage.getItem(storageKey)
      if (!threadId) {
        threadId = threadIdRef.current
        window.localStorage.setItem(storageKey, threadId)
      }
    } catch {
      threadId = threadIdRef.current // localStorage 불가(프라이빗 모드 등)면 세션 한정
    }
    threadIdRef.current = threadId

    let cancelled = false
    api
      .listThreadMessages(threadId)
      .then((res) => {
        if (cancelled || res.messages.length === 0) return
        const restored: AgentMsg[] = res.messages
          .filter((m) => m.content?.trim())
          .map((m) => ({
            id: ++seq.current,
            kind: 'text' as const,
            role: m.role === 'user' ? 'user' : 'assistant',
            text: m.content,
          }))
        if (restored.length === 0) return
        // 인사 메시지(initialMessage) 뒤에 이전 대화를 복원해 잇는다. 사용자가 이미
        // 입력을 시작했다면(텍스트 외 메시지 존재) 덮어쓰지 않는다.
        updateMsgs((prev) => {
          const onlyGreeting = prev.every((m) => m.kind === 'text' && m.role === 'assistant')
          if (!onlyGreeting) return prev
          const greeting = initialMessage
            ? [{ id: 0, kind: 'text' as const, role: 'assistant' as const, text: initialMessage }]
            : []
          return [
            ...greeting,
            { id: ++seq.current, kind: 'text' as const, role: 'assistant' as const, text: '— 이전 대화 —' },
            ...restored,
          ]
        })
      })
      .catch(() => {
        // 복원 실패는 무시(메모리는 백엔드에 남아 있고, 새 질문부터 이어진다).
      })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [wsId, myEmail, multiThread])

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

  useEffect(() => {
    if (!slashCommands) return
    let cancelled = false
    setSlashState({ loading: true, commands: [], error: null })
    api
      .listAgentTools()
      .then(async (catalog) => {
        const readTools = catalog.tools.filter((tool) => tool.risk === 'read_only')
        const details = await Promise.all(readTools.map((tool) => api.getAgentTool(tool.name)))
        if (cancelled) return
        setSlashState({
          loading: false,
          commands: buildSlashCommands(details.map((tool) => ({
            name: tool.name,
            description: tool.description,
            method: tool.method,
            path: tool.path_template,
            risk: tool.risk,
            params_schema: tool.params_schema,
            group: tool.group,
            labelKo: tool.label_ko,
          })), STRUCTURED_TOOL_INTRO),
          error: null,
        })
      })
      .catch((e) => {
        if (cancelled) return
        setSlashState({
          loading: false,
          commands: [],
          error: SLASH_CATALOG_ERROR_MESSAGE,
        })
      })
    return () => {
      cancelled = true
    }
  }, [slashCommands])

  useEffect(() => {
    setSlashActiveIndex(0)
  }, [input, slashState.commands.length])

  useEffect(() => {
    if (!slashMenuOpen || !activeSlashCommand) return
    slashOptionRefs.current[activeSlashCommand.slug]?.scrollIntoView({ block: 'nearest' })
  }, [slashMenuOpen, activeSlashCommand])

  function appendText(role: TextMsg['role'], text: string) {
    updateMsgs((m) => [...m, { id: ++seq.current, kind: 'text', role, text }])
  }

  function findPipelineWizard(messageId: number) {
    return msgsRef.current.find((msg): msg is PipelineWizardMsg => msg.kind === 'pipelineWizard' && msg.id === messageId) ?? null
  }

  function isCurrentPipelineWizardWorkspace(msg: PipelineWizardMsg) {
    return !!wsId && msg.workspaceId === wsId
  }

  function markStalePipelineWizard(messageId: number) {
    updatePipelineWizard(messageId, (msg) => ({
      ...msg,
      loading: null,
      error: STALE_PIPELINE_WIZARD_MESSAGE,
    }))
  }

  function updatePipelineWizard(messageId: number, updater: (msg: PipelineWizardMsg) => PipelineWizardMsg) {
    updateMsgs((prev) =>
      prev.map((msg) => (msg.kind === 'pipelineWizard' && msg.id === messageId ? updater(msg) : msg)),
    )
  }

  async function startPipelineWizard(visibleUserText: string) {
    if (!wsId) {
      appendText('assistant', '프로젝트를 선택한 뒤 파이프라인을 생성할 수 있습니다.')
      return
    }

    appendText('user', visibleUserText)
    const messageId = ++seq.current
    updateMsgs((prev) => [
      ...prev,
      {
        id: messageId,
        kind: 'pipelineWizard',
        workspaceId: wsId,
        step: 'source',
        databases: [],
        sourceDbId: null,
        pattern: null,
        sinkDbId: null,
        tables: [],
        table: null,
        readiness: { source: null, sink: null },
        name: '',
        created: null,
        loading: 'databases',
        error: null,
      },
    ])

    await loadPipelineWizardDatabases(messageId, wsId)
  }

  async function loadPipelineWizardDatabases(messageId: number, workspaceId: string) {
    updatePipelineWizard(messageId, (msg) => ({ ...msg, loading: 'databases', error: null }))
    try {
      const databases = await api.listDatabases(workspaceId)
      updatePipelineWizard(messageId, (msg) => ({
        ...msg,
        databases,
        loading: null,
        error: null,
      }))
    } catch (e) {
      updatePipelineWizard(messageId, (msg) => ({
        ...msg,
        loading: null,
        error: e instanceof ApiError ? e.message : '데이터베이스 목록을 불러오지 못했습니다.',
      }))
    }
  }

  async function selectPipelineWizardSource(messageId: number, sourceDbId: string) {
    const current = findPipelineWizard(messageId)
    if (!current) return
    if (!isCurrentPipelineWizardWorkspace(current)) {
      markStalePipelineWizard(messageId)
      return
    }
    const workspaceId = current.workspaceId
    updatePipelineWizard(messageId, (msg) => ({
      ...msg,
      step: 'target',
      sourceDbId,
      pattern: null,
      sinkDbId: null,
      tables: [],
      table: null,
      readiness: { source: null, sink: null },
      name: '',
      created: null,
      loading: 'schema',
      error: null,
    }))

    try {
      const schema = await api.databaseSchema(workspaceId, sourceDbId)
      const latest = findPipelineWizard(messageId)
      if (!latest || latest.sourceDbId !== sourceDbId || latest.workspaceId !== workspaceId || !isCurrentPipelineWizardWorkspace(latest)) return
      updatePipelineWizard(messageId, (msg) => ({
        ...msg,
        tables: schema.tables,
        loading: null,
        error: null,
      }))
    } catch (e) {
      const latest = findPipelineWizard(messageId)
      if (!latest || latest.sourceDbId !== sourceDbId || latest.workspaceId !== workspaceId || !isCurrentPipelineWizardWorkspace(latest)) return
      updatePipelineWizard(messageId, (msg) => ({
        ...msg,
        tables: [],
        loading: null,
        error: e instanceof ApiError ? e.message : '소스 DB 스키마를 불러오지 못했습니다.',
      }))
    }
  }

  function selectPipelineWizardPattern(messageId: number, pattern: PipelineWizardPattern) {
    const current = findPipelineWizard(messageId)
    if (!current) return
    if (!isCurrentPipelineWizardWorkspace(current)) {
      markStalePipelineWizard(messageId)
      return
    }
    updatePipelineWizard(messageId, (msg) => ({
      ...msg,
      step: pattern === 'fan-out' ? 'table' : 'target',
      pattern,
      sinkDbId: null,
      table: null,
      readiness: { source: null, sink: null },
      name: '',
      created: null,
      error: null,
    }))
  }

  function selectPipelineWizardSink(messageId: number, sinkDbId: string) {
    const current = findPipelineWizard(messageId)
    if (!current) return
    if (!isCurrentPipelineWizardWorkspace(current)) {
      markStalePipelineWizard(messageId)
      return
    }
    updatePipelineWizard(messageId, (msg) => ({
      ...msg,
      step: 'table',
      sinkDbId,
      table: null,
      readiness: { source: null, sink: null },
      name: '',
      created: null,
      error: null,
    }))
  }

  function selectPipelineWizardTable(messageId: number, table: PipelineWizardTable) {
    const current = findPipelineWizard(messageId)
    if (!current) return
    if (!isCurrentPipelineWizardWorkspace(current)) {
      markStalePipelineWizard(messageId)
      return
    }
    const source = current.databases.find((db) => db.id === current.sourceDbId) ?? null
    const sink = current.databases.find((db) => db.id === current.sinkDbId) ?? null
    const name = suggestPipelineName(source, table, current.pattern, sink)
    updatePipelineWizard(messageId, (msg) => ({
      ...msg,
      step: 'readiness',
      table,
      readiness: { source: null, sink: null },
      name: msg.name || name,
      created: null,
      loading: 'readiness',
      error: null,
    }))
    if (current.sourceDbId && current.pattern) {
      void checkPipelineWizardReadiness(messageId, {
        workspaceId: current.workspaceId,
        sourceDbId: current.sourceDbId,
        pattern: current.pattern,
        sinkDbId: current.sinkDbId,
        table,
      })
    }
  }

  async function checkPipelineWizardReadiness(
    messageId: number,
    snapshot?: {
      workspaceId: string
      sourceDbId: string
      pattern: PipelineWizardPattern
      sinkDbId: string | null
      table: PipelineWizardTable | null
    },
  ) {
    const current = findPipelineWizard(messageId)
    const target = snapshot ?? (current?.sourceDbId && current.pattern
      ? {
          workspaceId: current.workspaceId,
          sourceDbId: current.sourceDbId,
          pattern: current.pattern,
          sinkDbId: current.sinkDbId,
          table: current.table,
        }
      : null)
    if (!target) return
    const latestBeforeRequest = findPipelineWizard(messageId)
    if (!latestBeforeRequest || !isCurrentPipelineWizardWorkspace(latestBeforeRequest)) {
      markStalePipelineWizard(messageId)
      return
    }
    if (target.pattern === 'direct' && !target.sinkDbId) return
    const workspaceId = target.workspaceId
    const sourceDbId = target.sourceDbId
    const sinkDbId = target.pattern === 'direct' ? target.sinkDbId : null
    const selectedTable = target.table ? tableKey(target.table) : null

    updatePipelineWizard(messageId, (msg) => ({ ...msg, loading: 'readiness', error: null }))
    try {
      const [source, sink] = await Promise.all([
        api.cdcReadiness(workspaceId, sourceDbId),
        sinkDbId ? api.sinkReadiness(workspaceId, sinkDbId) : Promise.resolve(null),
      ])
      const latest = findPipelineWizard(messageId)
      if (
        !latest ||
        latest.workspaceId !== workspaceId ||
        !isCurrentPipelineWizardWorkspace(latest) ||
        latest.sourceDbId !== sourceDbId ||
        latest.sinkDbId !== sinkDbId ||
        (latest.table ? tableKey(latest.table) : null) !== selectedTable
      ) return
      updatePipelineWizard(messageId, (msg) => ({
        ...msg,
        readiness: { source, sink },
        loading: null,
        error: null,
      }))
    } catch (e) {
      updatePipelineWizard(messageId, (msg) => ({
        ...msg,
        loading: null,
        error: e instanceof ApiError ? e.message : 'readiness 확인에 실패했습니다.',
      }))
    }
  }

  function updatePipelineWizardName(messageId: number, name: string) {
    const current = findPipelineWizard(messageId)
    if (!current) return
    if (!isCurrentPipelineWizardWorkspace(current)) {
      markStalePipelineWizard(messageId)
      return
    }
    updatePipelineWizard(messageId, (msg) => ({ ...msg, name }))
  }

  function updatePipelineWizardAlias(messageId: number, alias: string) {
    const current = findPipelineWizard(messageId)
    if (!current) return
    if (!isCurrentPipelineWizardWorkspace(current)) {
      markStalePipelineWizard(messageId)
      return
    }
    updatePipelineWizard(messageId, (msg) => ({ ...msg, alias }))
  }

  async function createPipelineFromWizard(messageId: number) {
    const current = findPipelineWizard(messageId)
    if (!current?.sourceDbId || !current.pattern || !current.table || !current.name.trim()) return
    if (!isCurrentPipelineWizardWorkspace(current)) {
      markStalePipelineWizard(messageId)
      return
    }
    if (current.pattern === 'direct' && !current.sinkDbId) return
    if (!readinessAllowsCreate(current.readiness.source, current.readiness.sink, current.pattern)) return

    updatePipelineWizard(messageId, (msg) => ({ ...msg, loading: 'creating', error: null }))
    try {
      const created = await api.createPipeline(current.workspaceId, buildPipelineCreateInput({
        name: current.name,
        alias: current.alias,
        pattern: current.pattern,
        sourceDbId: current.sourceDbId,
        sinkDbId: current.sinkDbId,
        table: current.table,
      }))
      const failed = created.status === 'error'
      updatePipelineWizard(messageId, (msg) => ({
        ...msg,
        step: 'created',
        created,
        loading: null,
        error: failed ? created.statusMessage ?? '파이프라인 생성 요청이 오류 상태로 종료되었습니다.' : null,
      }))
      toast(
        failed ? created.statusMessage ?? '파이프라인 생성에 실패했습니다' : '파이프라인 생성 요청됨 — 활성화되면 자동 반영됩니다',
        failed ? 'error' : 'success',
      )
    } catch (e) {
      updatePipelineWizard(messageId, (msg) => ({
        ...msg,
        loading: null,
        error: e instanceof ApiError ? e.message : '파이프라인 생성에 실패했습니다.',
      }))
      toast(e instanceof ApiError ? e.message : '파이프라인 생성에 실패했습니다.', 'error')
    }
  }

  function retryPipelineWizard(messageId: number) {
    const current = findPipelineWizard(messageId)
    if (!current) return
    if (!isCurrentPipelineWizardWorkspace(current)) {
      markStalePipelineWizard(messageId)
      return
    }
    if (current.step === 'source' || current.databases.length === 0) {
      void loadPipelineWizardDatabases(messageId, current.workspaceId)
      return
    }
    if (current.step === 'target' && current.sourceDbId && current.tables.length === 0) {
      void selectPipelineWizardSource(messageId, current.sourceDbId)
      return
    }
    if (current.step === 'readiness') {
      void checkPipelineWizardReadiness(messageId)
    }
  }

  function backPipelineWizardToSource(messageId: number) {
    const current = findPipelineWizard(messageId)
    if (!current) return
    if (!isCurrentPipelineWizardWorkspace(current)) {
      markStalePipelineWizard(messageId)
      return
    }
    updatePipelineWizard(messageId, (msg) => ({
      ...msg,
      step: 'source',
      sourceDbId: null,
      pattern: null,
      sinkDbId: null,
      tables: [],
      table: null,
      readiness: { source: null, sink: null },
      name: '',
      created: null,
      loading: null,
      error: null,
    }))
  }

  async function startRun(
    rawMessage: string,
    options: {
      visibleUserText?: string
      mode?: AgentRunMode
      incidentId?: string | null
      remediationRequested?: boolean
      actionCandidate?: ActionRunCandidateInput | null
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
        mode: options.mode ?? null,
        message,
        incident_id: options.incidentId ?? null,
        thread_id: options.incidentId ?? threadIdRef.current,
        owner: multiThread && !options.incidentId ? (myEmail || undefined) : undefined,
        remediation_requested: options.remediationRequested ?? false,
        action_candidate: options.actionCandidate ?? null,
        stream: true,
      })
      // #821 멀티 채팅방: 새 스레드 등장·제목·시각 반영을 위해 목록 갱신.
      if (multiThread && !options.incidentId) void refreshThreads()
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
    const wizardMessage = pipelineWizardStartCandidate(text, { running })
    if (wizardMessage) {
      setInput('')
      void startPipelineWizard(wizardMessage)
      return
    }
    const route = routeAgentInput(text, {
      slashCommands,
      slashLoading: slashState.loading,
      slashError: slashState.error,
      commands: slashState.commands,
    })
    if (route.kind === 'empty' || running) return
    if (route.kind === 'slash_execute') {
      setInput('')
      void runSlashCommand(route.parsed.command, route.parsed.args)
      return
    }
    if (route.kind === 'slash_missing_args') {
      const feedback = slashMissingArgsFeedback(text, route)
      appendText('user', feedback.userText)
      appendText('assistant', feedback.assistantText)
      setInput(feedback.input)
      window.setTimeout(() => inputRef.current?.focus(), 0)
      return
    }
    if (route.kind === 'slash_loading' || route.kind === 'slash_error' || route.kind === 'slash_unknown') {
      appendText('assistant', route.message)
      return
    }
    setInput('')
    void startRun(route.message, { visibleUserText: route.message })
  }

  async function runSlashCommand(command: SlashToolCommand, args: string[] = []) {
    if (running) return
    const projectRef = app.currentProject?.slug || app.currentProject?.id || null
    if (!projectRef) {
      appendText('assistant', '프로젝트를 선택한 뒤 slash command를 실행할 수 있습니다.')
      return
    }

    esRef.current?.close()
    const runId = `slash_${Date.now()}_${seq.current + 1}`
    const panelKey = `${runId}:${command.toolName}`
    const params = slashCommandParams(command, args)
    setRunning(true)
    runningRef.current = true
    appendText('user', [command.label, ...args].join(' '))
    updateMsgs((m) => [
      ...m,
      {
        id: ++seq.current,
        kind: 'toolPanel',
        runId,
        panelKey,
        toolName: command.toolName,
        params,
        state: 'running',
        summary: 'slash command 직접 호출',
        result: null,
        notice: null,
        error: null,
      },
    ])
    app.setAgentRunState({
      runId,
      status: 'running',
      lastEventType: 'slash_command_started',
      lastMessage: `${command.label} 직접 호출 중`,
    })

    try {
      const response = await api.executeAgentTool(command.toolName, { project_id: projectRef, params })
      finishSlashCommand(panelKey, runId, 'done', response.result ?? null, null)
      toast('조회 완료', 'success')
    } catch (e) {
      const feedback = agentToolErrorFeedback(e)
      const message = feedback?.message ?? (e instanceof ApiError ? e.message : 'tool 조회에 실패했습니다')
      finishSlashCommand(
        panelKey,
        runId,
        feedback?.kind === 'guidance' ? 'done' : 'failed',
        null,
        message,
        feedback?.kind,
      )
      if (feedback) {
        upsertFinalAssistantAnswer(runId, feedback.message)
        if (feedback.kind === 'guidance') setInput(`${[command.label, ...args].join(' ')} `)
        toast(feedback.message, feedback.kind === 'blocked' ? 'error' : 'info')
      } else {
        toast(message, 'error')
      }
    }
  }

  function finishSlashCommand(
    panelKey: string,
    runId: string,
    state: Extract<ProgressState, 'done' | 'failed'>,
    result: unknown,
    error: string | null,
    outcome?: 'guidance' | 'blocked',
  ) {
    updateMsgs((prev) =>
      prev.map((msg) =>
        msg.kind === 'toolPanel' && msg.panelKey === panelKey
          ? {
              ...msg,
              state,
              summary: state === 'done' && outcome !== 'guidance' ? 'slash command 직접 호출 완료' : msg.summary,
              result,
              notice: outcome === 'guidance' ? error : null,
              error: outcome === 'guidance' ? null : error,
            }
          : msg,
      ),
    )
    setRunning(false)
    runningRef.current = false
    app.setAgentRunState({
      runId,
      status: state === 'done' && outcome !== 'guidance' ? 'completed' : outcome === 'guidance' ? 'idle' : 'failed',
      lastEventType: state === 'done' && outcome !== 'guidance'
        ? 'slash_command_completed'
        : outcome === 'guidance'
          ? 'slash_command_guidance'
          : outcome === 'blocked'
            ? 'slash_command_blocked'
            : 'slash_command_failed',
      lastMessage: state === 'done' && outcome !== 'guidance' ? 'slash command 조회 완료' : error,
    })
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
      `인시던트 '${task.incidentTitle}'의 권장 조치를 실행합니다. ${task.detail}`,
    )
    void startRun(message, {
      visibleUserText: `인시던트 조치 요청: ${task.label}`,
      mode: 'action_execution',
      incidentId: task.incidentId,
      remediationRequested: false,
      actionCandidate: task.actionCandidate,
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
      upsertToolEvent(event, 'running')
      return
    }
    if (event.type === 'tool_call_completed') {
      upsertToolEvent(event, 'done')
      return
    }
    if (event.type === 'tool_call_failed') {
      upsertToolEvent(event, 'failed')
      return
    }
    if (event.type === 'evidence_collected') {
      const evidence: Omit<EvidenceMsg, 'id' | 'kind'> = {
        evidenceId: payloadString(event, 'evidence_id'),
        evidenceType: payloadString(event, 'evidence_type'),
        summary: payloadString(event, 'summary'),
        redactionStatus: payloadString(event, 'redaction_status'),
        traceId: payloadString(event, 'trace_id'),
        pipelineId: payloadString(event, 'pipeline_id'),
      }
      updateMsgs((m) => {
        const existingEvidence = m.filter((msg): msg is EvidenceMsg => msg.kind === 'evidence')
        if (!shouldAppendEvidenceCard(existingEvidence, evidence)) return m
        return [
          ...m,
          {
            id: ++seq.current,
            kind: 'evidence',
            ...evidence,
          },
        ]
      })
      return
    }
    if (event.type === 'report_preview' || event.type === 'report_preview_available') {
      const handledRca = upsertRcaPreviewCard(event)
      const handledRemediation = upsertRemediationCard(event)
      if (handledRca || handledRemediation) return
      upsertAssistantText(runAnswerKey(event.run_id), event.message)
      return
    }
    if (event.type === 'partial_result') {
      const answer = payloadString(event, 'answer')
      const stage = payloadString(event, 'stage')
      if (stage === 'rca') {
        appendProgressStatus(event, 'done')
        upsertRcaPreviewCard(event)
        return
      }
      if (stage === 'remediation') {
        appendProgressStatus(event, 'done')
        upsertRemediationCard(event, true)
        return
      }
      if (upsertRemediationCard(event)) {
        appendProgressStatus(event, 'done')
        return
      }
      if (answer) {
        appendProgressStatus(event, 'done')
        upsertFinalAssistantAnswer(event.run_id, answer)
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
    if (isRcaStageEvent(event) && event.type === 'agent_completed') {
      appendProgressStatus(event, progressStateForEvent(event))
      upsertRcaPreviewCard(event)
      return
    }
    if (isRemediationStageEvent(event) && event.type === 'agent_completed') {
      appendProgressStatus(event, progressStateForEvent(event))
      upsertRemediationCard(event, true)
      return
    }

    appendProgressStatus(event, progressStateForEvent(event))
  }

  function upsertRcaPreviewCard(event: AgentRunEvent) {
    if (!isRcaStageEvent(event)) return false
    const rootCauseId = payloadRootCauseId(event)
    const confidence = payloadNumber(event, 'confidence')
    const verified = payloadBoolean(event, 'verified')
    const message = event.message.trim()
    updateMsgs((prev) => {
      const existingIndex = prev.findIndex((msg): msg is RcaPreviewMsg => msg.kind === 'rcaPreview' && msg.runId === event.run_id)
      if (existingIndex < 0) {
        return [
          ...prev,
          {
            id: ++seq.current,
            kind: 'rcaPreview',
            runId: event.run_id,
            rootCauseId,
            confidence,
            verified,
            message,
          },
        ]
      }
      return prev.map((msg, index) =>
        index === existingIndex && msg.kind === 'rcaPreview'
          ? {
              ...msg,
              rootCauseId: rootCauseId ?? msg.rootCauseId,
              confidence: confidence ?? msg.confidence,
              verified: verified ?? msg.verified,
              message: message || msg.message,
            }
          : msg,
      )
    })
    return true
  }

  function upsertRemediationCard(event: AgentRunEvent, allowEmpty = false) {
    const candidates = remediationCandidatesFromPayload(event.payload)
    if (!allowEmpty && candidates.length === 0) return false
    const message = event.message.trim()
    updateMsgs((prev) => {
      const existingIndex = prev.findIndex((msg): msg is RemediationMsg => msg.kind === 'remediation' && msg.runId === event.run_id)
      if (existingIndex < 0) {
        return [
          ...prev,
          {
            id: ++seq.current,
            kind: 'remediation',
            runId: event.run_id,
            message,
            candidates,
          },
        ]
      }
      return prev.map((msg, index) =>
        index === existingIndex && msg.kind === 'remediation'
          ? {
              ...msg,
              message: message || msg.message,
              candidates: candidates.length > 0 ? mergeRemediationCandidates(msg.candidates, candidates) : msg.candidates,
            }
          : msg,
      )
    })
    return true
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
    }, payloadRequiredFlow(event))
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

  function upsertToolEvent(event: AgentRunEvent, state: ProgressState) {
    const toolName = payloadString(event, 'tool') ?? 'tool'
    if (isStructuredTool(toolName)) {
      upsertStructuredToolPanel(event, state)
      return
    }
    upsertTool(event, state)
  }

  function upsertStructuredToolPanel(event: AgentRunEvent, state: ProgressState) {
    const toolName = payloadString(event, 'tool') ?? 'tool'
    if (!isStructuredTool(toolName)) return
    const panelKey = `${event.run_id}:${payloadString(event, 'step_id') ?? toolName}`
    const summary = payloadString(event, 'summary')
    const params = payloadRecord(event, 'params') ?? {}
    const result = event.payload.result ?? null
    const error = payloadErrorMessage(event)
    updateMsgs((prev) => {
      const existingIndex = prev.findIndex((msg): msg is ToolPanelMsg => msg.kind === 'toolPanel' && msg.panelKey === panelKey)
      if (existingIndex < 0) {
        return [
          ...prev,
          {
            id: ++seq.current,
            kind: 'toolPanel',
            runId: event.run_id,
            panelKey,
            toolName,
            params,
            state,
            summary,
            result,
            notice: null,
            error,
          },
        ]
      }
      return prev.map((msg, index) =>
        index === existingIndex && msg.kind === 'toolPanel'
          ? {
              ...msg,
              toolName,
              params,
              state: mergeProgressState(msg.state, state),
              summary: summary ?? msg.summary,
              result: result ?? msg.result,
              notice: null,
              error: error ?? msg.error,
            }
          : msg,
      )
    })
  }

  function upsertProgress(runId: string, item: ProgressItem, requiredFlow?: string[] | null) {
    updateMsgs((prev) => {
      const progressIndex = prev.findIndex((msg): msg is ProgressMsg => msg.kind === 'progress' && msg.runId === runId)
      if (progressIndex < 0) {
        return [
          ...prev,
          {
            id: ++seq.current,
            kind: 'progress',
            runId,
            expanded: false,
            terminalState: null,
            terminalText: null,
            items: [item],
            requiredFlow: requiredFlow ?? null,
          },
        ]
      }
      return prev.map((msg, index) => {
        if (index !== progressIndex || msg.kind !== 'progress') return msg
        const nextRequiredFlow = requiredFlow ?? msg.requiredFlow
        const itemIndex = msg.items.findIndex((existing) => existing.key === item.key)
        if (itemIndex < 0) return { ...msg, requiredFlow: nextRequiredFlow, items: [...msg.items, item] }
        return {
          ...msg,
          requiredFlow: nextRequiredFlow,
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
    <div className="relative flex flex-1 flex-col overflow-hidden">
      <div className="flex items-center gap-2 border-b border-gray-200 px-4 py-3">
        <div className={cn('flex h-7 w-7 items-center justify-center rounded-md text-white', theme.avatar)}>
          <Icon name={icon} size={15} />
        </div>
        <div className="min-w-0 flex-1">
          <div className="truncate text-[13px] font-semibold text-gray-900">
            {multiThread ? activeThreadTitle || '새 대화' : title}
          </div>
          <div className="truncate text-[11px] text-gray-400">{subtitle}</div>
        </div>
        {multiThread && (
          <>
            <button
              type="button"
              onClick={() => setThreadListOpen(true)}
              title="채팅방 목록"
              className="flex h-7 w-7 items-center justify-center rounded-md border border-gray-200 text-gray-500 hover:bg-gray-50"
            >
              <Icon name="list" size={15} />
            </button>
            <button
              type="button"
              onClick={newChat}
              disabled={running}
              title="새 대화"
              className="flex h-7 w-7 items-center justify-center rounded-md bg-[#0d0d0d] text-white hover:opacity-90 disabled:opacity-40"
            >
              <Icon name="plus" size={15} />
            </button>
          </>
        )}
        {hitlLabel && <div className="text-[10.5px] font-semibold uppercase tracking-wide text-[#6b6b73]">{hitlLabel}</div>}
      </div>

      {multiThread && threadListOpen && (
        <div className="absolute inset-0 z-20 flex flex-col bg-white">
          <div className="flex items-center gap-2 border-b border-gray-200 px-3 py-3">
            <button
              type="button"
              onClick={() => setThreadListOpen(false)}
              title="닫기"
              className="flex h-7 w-7 items-center justify-center rounded-md text-gray-500 hover:bg-gray-50"
            >
              <Icon name="chevron-left" size={16} />
            </button>
            <div className="flex-1 text-[13px] font-semibold text-gray-900">내 채팅방</div>
            <button
              type="button"
              onClick={newChat}
              disabled={running}
              className="flex items-center gap-1 rounded-md bg-[#0d0d0d] px-2.5 py-1.5 text-[12px] font-medium text-white hover:opacity-90 disabled:opacity-40"
            >
              <Icon name="plus" size={13} />
              새 대화
            </button>
          </div>
          <div className="px-3 pt-2.5 pb-1.5">
            <div className="flex items-center gap-2 rounded-md border border-gray-200 px-2.5 py-1.5">
              <Icon name="search" size={13} className="text-gray-400" />
              <input
                value={threadQuery}
                onChange={(e) => setThreadQuery(e.target.value)}
                placeholder="채팅 검색"
                className="w-full bg-transparent text-[12px] text-gray-800 outline-none placeholder:text-gray-400"
              />
            </div>
          </div>
          <div className="flex-1 overflow-y-auto scroll-thin px-2 pb-2">
            {threads
              .filter((t) => {
                const q = threadQuery.trim().toLowerCase()
                return !q || (t.title ?? '').toLowerCase().includes(q) || (t.preview ?? '').toLowerCase().includes(q)
              })
              .map((t) => (
                <div
                  key={t.id}
                  className={cn(
                    'group flex items-center gap-2 rounded-md px-2.5 py-2',
                    t.id === activeThreadId ? 'bg-gray-100' : 'hover:bg-gray-50',
                  )}
                >
                  {renamingId === t.id ? (
                    <input
                      autoFocus
                      value={renameText}
                      onChange={(e) => setRenameText(e.target.value)}
                      onBlur={() => commitRename(t.id)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') commitRename(t.id)
                        else if (e.key === 'Escape') setRenamingId(null)
                      }}
                      className="w-full rounded border border-[#0d0d0d] px-1.5 py-0.5 text-[12.5px] font-medium text-gray-800 outline-none"
                    />
                  ) : (
                    <button type="button" onClick={() => switchThread(t.id)} className="min-w-0 flex-1 text-left">
                      <div className="truncate text-[12.5px] font-medium text-gray-800">{t.title || '새 대화'}</div>
                      {t.preview && <div className="truncate text-[11px] text-gray-400">{t.preview}</div>}
                    </button>
                  )}
                  {renamingId !== t.id && (
                    <div className="flex shrink-0 items-center gap-1 opacity-0 group-hover:opacity-100">
                      <button
                        type="button"
                        onClick={() => {
                          setRenamingId(t.id)
                          setRenameText(t.title ?? '')
                        }}
                        title="이름 변경"
                        className="flex h-6 w-6 items-center justify-center rounded text-gray-400 hover:bg-gray-200 hover:text-gray-600"
                      >
                        <Icon name="pencil" size={12} />
                      </button>
                      <button
                        type="button"
                        onClick={() => removeThread(t.id)}
                        title="삭제"
                        className="flex h-6 w-6 items-center justify-center rounded text-gray-400 hover:bg-gray-200 hover:text-[#c0392b]"
                      >
                        <Icon name="trash" size={12} />
                      </button>
                    </div>
                  )}
                </div>
              ))}
            {threads.length === 0 && (
              <div className="px-3 py-10 text-center text-[12px] text-gray-400">
                채팅방이 없습니다. ‘새 대화’로 시작하세요.
              </div>
            )}
          </div>
        </div>
      )}

      <div ref={scroll} className="flex-1 space-y-3 overflow-y-auto scroll-thin bg-gray-50 px-4 py-4">
        {msgs.map((m) => {
          if (m.kind === 'text') return <TextBubble key={m.id} msg={m} theme={theme} />
          if (m.kind === 'status') return <StatusCard key={m.id} msg={m} theme={theme} />
          if (m.kind === 'progress') {
            return <ProgressCard key={m.id} msg={m} theme={theme} onToggle={() => toggleProgress(m.id)} />
          }
          if (m.kind === 'evidence') {
            return <EvidenceCard key={m.id} msg={m} onOpenTrace={app.openPipelineTrace} />
          }
          if (m.kind === 'rcaPreview') return <RcaPreviewCard key={m.id} msg={m} />
          if (m.kind === 'remediation') return <RemediationCard key={m.id} msg={m} />
          if (m.kind === 'pipelineWizard') {
            return (
              <PipelineWizardCard
                key={m.id}
                msg={m}
                onSelectSource={(sourceDbId) => selectPipelineWizardSource(m.id, sourceDbId)}
                onSelectPattern={(pattern) => selectPipelineWizardPattern(m.id, pattern)}
                onSelectSink={(sinkDbId) => selectPipelineWizardSink(m.id, sinkDbId)}
                onSelectTable={(table) => selectPipelineWizardTable(m.id, table)}
                onNameChange={(name) => updatePipelineWizardName(m.id, name)}
                onAliasChange={(alias) => updatePipelineWizardAlias(m.id, alias)}
                onCreate={() => createPipelineFromWizard(m.id)}
                onRetry={() => retryPipelineWizard(m.id)}
                onBackToSource={() => backPipelineWizardToSource(m.id)}
              />
            )
          }
          if (m.kind === 'toolPanel') return <ToolPanelCard key={m.id} msg={m} onOpenPipeline={app.openPipeline} onOpenIncident={app.openIncident} />
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

      {quickActions.length > 0 && (
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
      )}
      {slashMenuOpen && (
        <div className="border-t border-gray-100 px-3 pt-2">
          <div
            id={slashMenuId}
            role="listbox"
            className="max-h-64 overflow-y-auto rounded-lg border border-gray-200 bg-white text-[12px] shadow-sm"
          >
            {slashState.loading ? (
              <div className="flex items-center gap-2 px-3 py-2 text-gray-500">
                <Spinner size={12} />
                <span>tool catalog loading</span>
              </div>
            ) : slashState.error ? (
              <div className="break-words px-3 py-2 text-[#c0392b]">{slashState.error}</div>
            ) : (
              slashOptions.map((command, index) => (
                <div
                  key={command.toolName}
                  id={`${slashMenuId}-${command.slug}`}
                  role="option"
                  aria-selected={index === slashActiveIndex}
                  ref={(node) => {
                    slashOptionRefs.current[command.slug] = node
                  }}
                  onMouseEnter={() => setSlashActiveIndex(index)}
                  onMouseDown={(e) => {
                    e.preventDefault()
                    const route = routeAgentInput(input, {
                      slashCommands,
                      slashLoading: slashState.loading,
                      slashError: slashState.error,
                      commands: slashState.commands,
                    })
                    const selectedInput = slashSelectionSubmissionText(input, route, command)
                    if (selectedInput) {
                      send(selectedInput)
                    } else {
                      setInput('')
                      void runSlashCommand(command)
                    }
                  }}
                  className={cn(
                    'flex w-full items-start gap-2 px-3 py-2 text-left',
                    index === slashActiveIndex ? 'bg-gray-50' : 'hover:bg-gray-50',
                  )}
                >
                  <SlashCommandOptionContent command={command} />
                </div>
              ))
            )}
          </div>
        </div>
      )}
      {slashCommands && paletteOpen && !running && (
        <div className="border-t border-gray-100 px-3 pt-2">
          <CommandPalette
            commands={slashState.commands}
            loading={slashState.loading}
            error={slashState.error}
            loadOptions={(param) => {
              if (!wsId) return null
              if (param === 'pipeline_id') {
                return api
                  .listPipelines(wsId)
                  .then((items) => items.map((p) => ({ value: p.id, label: p.name || p.id })))
              }
              if (param === 'connector_name') {
                // #836: 클러스터 전체가 아니라 현재 프로젝트 파이프라인의 커넥터만 노출.
                return api.listPipelines(wsId).then(async (pipelines) => {
                  const perPipeline = await Promise.all(
                    pipelines.map((p) =>
                      api.listPipelineConnectors(wsId, p.id).catch(() => [] as ConnectorInfo[]),
                    ),
                  )
                  const seen = new Set<string>()
                  const options: { value: string; label: string }[] = []
                  for (const conn of perPipeline.flat()) {
                    if (seen.has(conn.name)) continue
                    seen.add(conn.name)
                    options.push({ value: conn.name, label: conn.name })
                  }
                  return options
                })
              }
              if (param === 'consumer_group') {
                // #838: 현재 프로젝트 파이프라인의 컨슈머 그룹만 노출.
                return api.listPipelines(wsId).then(async (pipelines) => {
                  const perPipeline = await Promise.all(
                    pipelines.map((p) =>
                      api.pipelineConsumerGroups(wsId, p.id).catch(() => [] as ConsumerGroupInfo[]),
                    ),
                  )
                  const seen = new Set<string>()
                  const options: { value: string; label: string }[] = []
                  for (const group of perPipeline.flat()) {
                    if (seen.has(group.name)) continue
                    seen.add(group.name)
                    options.push({ value: group.name, label: group.name })
                  }
                  return options
                })
              }
              if (param === 'metric') {
                // #839: 카탈로그 enum이 있으면 select, 없으면(BE 미배포) 자유 입력 폴백.
                const metricCmd = slashState.commands.find((cmd) => cmd.toolName === 'get_metrics')
                const enumValues = metricCmd?.argEnums?.metric
                if (!enumValues || enumValues.length === 0) return null
                return Promise.resolve(
                  enumValues.map((value) => ({ value, label: METRIC_LABELS_KO[value] ?? value })),
                )
              }
              if (param === 'incident_id') {
                // 인시던트 ID 는 자유 입력 대신 프로젝트 인시던트 목록에서 선택.
                return Promise.resolve(
                  app.incidents.map((inc) => ({ value: inc.id, label: inc.title || inc.id })),
                )
              }
              return null
            }}
            onRunTool={(command, args) => {
              setInput('')
              void runSlashCommand(command, args)
            }}
            onCreatePipeline={() => {
              setInput('')
              void startPipelineWizard('파이프라인 생성')
            }}
            onClose={() => setPaletteOpen(false)}
          />
        </div>
      )}
      <div className="flex items-center gap-2 px-3 py-2.5">
        {slashCommands && (
          <button
            type="button"
            onClick={() => setPaletteOpen((open) => !open)}
            disabled={running}
            aria-label="명령 팔레트 열기"
            className="flex h-9 shrink-0 items-center gap-1 rounded-lg border border-gray-200 bg-white px-2.5 text-[12px] text-gray-600 hover:bg-gray-50 disabled:opacity-60"
          >
            <Icon name="grid" size={14} />
            명령
          </button>
        )}
        <input
          ref={inputRef}
          value={input}
          onChange={(e) => {
            if (slashCommands && e.target.value === '/') {
              setPaletteOpen(true)
              setInput('')
              return
            }
            setInput(e.target.value)
            setSlashMenuDismissed(false)
          }}
          onKeyDown={(e) => {
            if (slashMenuOpen && e.key === 'Escape') {
              e.preventDefault()
              setSlashMenuDismissed(true)
              return
            }
            if (slashMenuOpen && slashOptions.length > 0) {
              if (e.key === 'ArrowDown') {
                e.preventDefault()
                setSlashActiveIndex((index) => (index + 1) % slashOptions.length)
                return
              }
              if (e.key === 'ArrowUp') {
                e.preventDefault()
                setSlashActiveIndex((index) => (index - 1 + slashOptions.length) % slashOptions.length)
                return
              }
              if (e.key === 'Enter' && activeSlashCommand) {
                e.preventDefault()
                const route = routeAgentInput(input, {
                  slashCommands,
                  slashLoading: slashState.loading,
                  slashError: slashState.error,
                  commands: slashState.commands,
                })
                const selectedInput = slashSelectionSubmissionText(input, route, activeSlashCommand)
                if (selectedInput) {
                  send(selectedInput)
                } else {
                  setInput('')
                  void runSlashCommand(activeSlashCommand)
                }
                return
              }
            }
            if (e.key === 'Enter') send(input)
          }}
          placeholder={running ? runningPlaceholder : inputPlaceholder}
          disabled={running}
          role={slashCommands ? 'combobox' : undefined}
          aria-autocomplete={slashCommands ? 'list' : undefined}
          aria-expanded={slashCommands ? slashMenuOpen : undefined}
          aria-controls={slashMenuOpen ? slashMenuId : undefined}
          aria-activedescendant={slashMenuOpen ? activeSlashOptionId : undefined}
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

export function SlashCommandOptionContent({ command }: { command: SlashToolCommand }) {
  return (
    <>
      <span className="max-w-[52%] shrink-0 break-all rounded bg-gray-900 px-1.5 py-0.5 font-mono text-[10.5px] text-white">
        {command.label}
      </span>
      <span className="min-w-0 flex-1">
        <span className="block break-words font-medium text-gray-700">{command.description}</span>
      </span>
    </>
  )
}

function TextBubble({ msg, theme }: { msg: TextMsg; theme: (typeof THEMES)[ThemeName] }) {
  const isUser = msg.role === 'user'
  return (
    <div className={cn('flex', isUser ? 'justify-end' : 'justify-start')}>
      <div
        className={cn(
          'max-w-[88%] break-words rounded-xl px-3 py-2 text-[12.5px] leading-relaxed',
          isUser
            ? cn('whitespace-pre-wrap', theme.userBubble)
            : 'rounded-bl-sm border border-gray-200 bg-white text-gray-700',
        )}
      >
        {isUser ? msg.text : <Markdown>{msg.text}</Markdown>}
      </div>
    </div>
  )
}

function PipelineWizardCard({
  msg,
  onSelectSource,
  onSelectPattern,
  onSelectSink,
  onSelectTable,
  onNameChange,
  onAliasChange,
  onCreate,
  onRetry,
  onBackToSource,
}: {
  msg: PipelineWizardMsg
  onSelectSource: (sourceDbId: string) => void
  onSelectPattern: (pattern: PipelineWizardPattern) => void
  onSelectSink: (sinkDbId: string) => void
  onSelectTable: (table: PipelineWizardTable) => void
  onNameChange: (name: string) => void
  onAliasChange: (alias: string) => void
  onCreate: () => void
  onRetry: () => void
  onBackToSource: () => void
}) {
  const source = msg.databases.find((db) => db.id === msg.sourceDbId) ?? null
  const sink = msg.databases.find((db) => db.id === msg.sinkDbId) ?? null
  const sinkOptions = msg.databases.filter((db) => db.id !== msg.sourceDbId)
  const canCreate =
    !!msg.sourceDbId &&
    !!msg.pattern &&
    !!msg.table &&
    !!msg.name.trim() &&
    (msg.pattern !== 'direct' || !!msg.sinkDbId) &&
    readinessAllowsCreate(msg.readiness.source, msg.readiness.sink, msg.pattern)
  const blocked = readinessBlocked(msg.readiness.source) || readinessBlocked(msg.readiness.sink)
  const busy = msg.loading != null
  const schemaUnavailable = !!msg.sourceDbId && msg.loading !== 'schema' && msg.tables.length === 0 && !!msg.error

  return (
    <div className="rounded-lg border border-gray-200 bg-white text-[12px] text-gray-700">
      <div className="flex items-start gap-2 border-b border-gray-100 px-3 py-2.5">
        <div className="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-gray-900 text-white">
          <Icon name="route" size={14} />
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-1.5">
            <span className="font-semibold text-gray-900">파이프라인 생성</span>
            <span className="rounded bg-gray-100 px-1.5 py-0.5 text-[10.5px] font-medium text-gray-500">
              {pipelineWizardStepLabel(msg)}
            </span>
          </div>
          <div className="mt-0.5 break-words text-[11.5px] text-gray-500">
            {pipelineWizardSummary(msg, source, sink)}
          </div>
        </div>
      </div>

      <div className="space-y-3 px-3 py-3">
        <PipelineWizardProgress msg={msg} />

        {msg.step === 'source' && (
          <div className="space-y-2">
            <WizardSectionTitle title="Source DB" detail="변경을 감지할 데이터베이스를 선택하세요." />
            {msg.loading === 'databases' ? (
              <WizardLoading text="데이터베이스 목록을 불러오는 중" />
            ) : msg.error && msg.databases.length === 0 ? (
              <WizardError text={msg.error} onRetry={onRetry} />
            ) : msg.databases.length === 0 ? (
              <WizardEmpty text="등록된 데이터베이스가 없습니다." />
            ) : (
              <div className="space-y-1.5">
                {msg.databases.map((db) => (
                  <DatabaseChoiceButton
                    key={db.id}
                    db={db}
                    readinessKind="source"
                    selected={db.id === msg.sourceDbId}
                    disabled={busy}
                    blockOnKnownStatus
                    onClick={() => onSelectSource(db.id)}
                  />
                ))}
              </div>
            )}
          </div>
        )}

        {msg.step === 'target' && (
          <div className="space-y-3">
            <SelectionSummary source={source} sink={sink} table={msg.table} pattern={msg.pattern} />
            <div className="space-y-2">
              <WizardSectionTitle title="전달 방식" detail="Sink DB가 필요한지 선택하세요." />
              <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
                <PatternChoiceButton
                  title="여러 서비스에 전달"
                  detail="Kafka topic으로 발행합니다."
                  icon="share"
                  selected={msg.pattern === 'fan-out'}
                  disabled={busy || schemaUnavailable}
                  onClick={() => onSelectPattern('fan-out')}
                />
                <PatternChoiceButton
                  title="다른 DB에 복제"
                  detail="Sink DB에 동기화합니다."
                  icon="database"
                  selected={msg.pattern === 'direct'}
                  disabled={busy || schemaUnavailable || sinkOptions.length === 0}
                  onClick={() => onSelectPattern('direct')}
                />
              </div>
              {sinkOptions.length === 0 && (
                <WizardEmpty text="다른 DB에 복제하려면 Source 외 Sink DB를 먼저 등록하세요." />
              )}
            </div>
            {msg.pattern === 'direct' && (
              <div className="space-y-2">
                <WizardSectionTitle title="Sink DB" detail="데이터를 받을 데이터베이스를 선택하세요." />
                {sinkOptions.length === 0 ? (
                  <WizardEmpty text="선택 가능한 Sink DB가 없습니다." />
                ) : (
                  <div className="space-y-1.5">
                    {sinkOptions.map((db) => (
                      <DatabaseChoiceButton
                        key={db.id}
                        db={db}
                        readinessKind="sink"
                        selected={db.id === msg.sinkDbId}
                        disabled={busy}
                        onClick={() => onSelectSink(db.id)}
                      />
                    ))}
                  </div>
                )}
              </div>
            )}
            {msg.loading === 'schema' && <WizardLoading text="소스 DB 스키마를 불러오는 중" />}
            {msg.error && msg.loading !== 'schema' && (
              <div className="space-y-2">
                <WizardError text={msg.error} onRetry={onRetry} />
                <button
                  type="button"
                  onClick={onBackToSource}
                  className="rounded border border-gray-200 px-2.5 py-1.5 text-[11.5px] font-medium text-gray-600 hover:bg-gray-50"
                >
                  Source 다시 선택
                </button>
              </div>
            )}
          </div>
        )}

        {msg.step === 'table' && (
          <div className="space-y-3">
            <SelectionSummary source={source} sink={sink} table={msg.table} pattern={msg.pattern} />
            <div className="space-y-2">
              <WizardSectionTitle title="테이블" detail="소스 DB 스키마에서 대상 테이블을 선택하세요." />
              {msg.loading === 'schema' ? (
                <WizardLoading text="소스 DB 스키마를 불러오는 중" />
              ) : msg.error && msg.tables.length === 0 ? (
                <WizardError text={msg.error} onRetry={onRetry} />
              ) : msg.tables.length === 0 ? (
                <WizardEmpty text="조회된 테이블이 없습니다." />
              ) : (
                <div className="max-h-64 space-y-1.5 overflow-y-auto">
                  {msg.tables.map((table) => {
                    const selected = msg.table ? tableKey(table) === tableKey(msg.table) : false
                    return (
                      <button
                        type="button"
                        key={tableKey(table)}
                        disabled={busy}
                        onClick={() => onSelectTable({ schema: table.schema, name: table.name })}
                        className={cn(
                          'flex w-full items-center gap-2 rounded-md border px-2.5 py-2 text-left',
                          selected ? 'border-brand-500 bg-brand-50' : 'border-gray-200 hover:bg-gray-50',
                          busy && 'opacity-60',
                        )}
                      >
                        <Icon name="table" size={13} className={selected ? 'text-brand-600' : 'text-gray-400'} />
                        <span className="min-w-0 flex-1 break-all font-mono text-[11.5px] text-gray-700">
                          {tableKey(table)}
                        </span>
                        <span className="shrink-0 rounded bg-gray-100 px-1.5 py-0.5 text-[10.5px] text-gray-500">
                          {table.columns.length} cols
                        </span>
                      </button>
                    )
                  })}
                </div>
              )}
            </div>
          </div>
        )}

        {msg.step === 'readiness' && (
          <div className="space-y-3">
            <SelectionSummary source={source} sink={sink} table={msg.table} pattern={msg.pattern} />
            <div className="space-y-2">
              <WizardSectionTitle title="Readiness" detail="Source CDC와 Sink 준비 상태를 확인합니다." />
              {msg.loading === 'readiness' ? (
                <WizardLoading text="readiness 확인 중" />
              ) : msg.error && !msg.readiness.source ? (
                <WizardError text={msg.error} onRetry={onRetry} />
              ) : (
                <div className="space-y-2">
                  <ReadinessResult title="Source CDC" readiness={msg.readiness.source} />
                  {msg.pattern === 'direct' && <ReadinessResult title="Sink" readiness={msg.readiness.sink} />}
                </div>
              )}
            </div>
            {msg.readiness.source && (
              <div className="space-y-2 border-t border-gray-100 pt-3">
                <label className="block text-[11.5px] font-medium text-gray-600" htmlFor={`pipeline-name-${msg.id}`}>
                  파이프라인 이름
                </label>
                <input
                  id={`pipeline-name-${msg.id}`}
                  value={msg.name}
                  onChange={(e) => onNameChange(e.target.value)}
                  disabled={msg.loading === 'creating'}
                  className="h-9 w-full rounded-md border border-gray-200 px-2.5 text-[12.5px] outline-none focus:border-brand-500 disabled:opacity-60"
                />
                <label className="block text-[11.5px] font-medium text-gray-600" htmlFor={`pipeline-alias-${msg.id}`}>
                  표시 이름 <span className="font-normal text-gray-400">(선택 · 한글)</span>
                </label>
                <input
                  id={`pipeline-alias-${msg.id}`}
                  value={msg.alias ?? ''}
                  onChange={(e) => onAliasChange(e.target.value)}
                  disabled={msg.loading === 'creating'}
                  placeholder="예: 주문 동기화"
                  className="h-9 w-full rounded-md border border-gray-200 px-2.5 text-[12.5px] outline-none focus:border-brand-500 disabled:opacity-60"
                />
                {blocked && (
                  <div className="break-words rounded border border-[#c0392b] bg-[#fcf3f2] px-2 py-1.5 text-[11.5px] text-[#c0392b]">
                    BLOCKED readiness 항목이 있어 생성할 수 없습니다.
                  </div>
                )}
                {msg.error && msg.readiness.source && (
                  <div className="break-words rounded border border-[#c0392b] bg-[#fcf3f2] px-2 py-1.5 text-[11.5px] text-[#c0392b]">
                    {msg.error}
                  </div>
                )}
                <button
                  type="button"
                  disabled={!canCreate || msg.loading === 'creating'}
                  onClick={onCreate}
                  className="flex h-9 w-full items-center justify-center gap-1.5 rounded-md bg-brand-600 px-3 text-[12.5px] font-semibold text-white hover:bg-brand-700 disabled:bg-brand-300"
                >
                  {msg.loading === 'creating' ? <Spinner size={13} /> : <Icon name="play-circle" size={14} />}
                  파이프라인 생성
                </button>
              </div>
            )}
          </div>
        )}

        {msg.step === 'created' && msg.created && (
          <div className="space-y-3">
            <SelectionSummary source={source} sink={sink} table={msg.table} pattern={msg.pattern} />
            <div
              className={cn(
                'rounded-md border px-3 py-2.5',
                msg.created.status === 'error'
                  ? 'border-[#c0392b] bg-[#fcf3f2]'
                  : 'border-[#ececec] bg-[#ededed]',
              )}
            >
              <div
                className={cn(
                  'mb-1 flex items-center gap-1.5 font-semibold',
                  msg.created.status === 'error' ? 'text-[#c0392b]' : 'text-[#6b6b73]',
                )}
              >
                <Icon name={msg.created.status === 'error' ? 'alert' : 'check'} size={13} strokeWidth={3} />
                {msg.created.status === 'error' ? '생성 요청 오류' : '생성 요청 완료'}
              </div>
              <div
                className={cn(
                  'space-y-1 font-mono text-[11px]',
                  msg.created.status === 'error' ? 'text-[#c0392b]' : 'text-[#6b6b73]',
                )}
              >
                <div className="break-all">id={msg.created.id}</div>
                <div className="break-words">status={msg.created.status}</div>
                {msg.created.statusMessage && <div className="break-words">message={msg.created.statusMessage}</div>}
                <div className="break-all">topic={msg.created.topic ?? '-'}</div>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

function PipelineWizardProgress({ msg }: { msg: PipelineWizardMsg }) {
  const steps: PipelineWizardStep[] = ['source', 'target', 'table', 'readiness', 'created']
  const activeIndex = steps.indexOf(msg.step)
  return (
    <div className="grid grid-cols-5 gap-1">
      {steps.map((step, index) => (
        <div
          key={step}
          className={cn(
            'h-1.5 rounded-full',
            index < activeIndex || msg.step === 'created'
              ? 'bg-[#c8c8c8]'
              : index === activeIndex
                ? 'bg-brand-500'
                : 'bg-gray-200',
          )}
        />
      ))}
    </div>
  )
}

function DatabaseChoiceButton({
  db,
  readinessKind,
  selected,
  disabled,
  blockOnKnownStatus = false,
  onClick,
}: {
  db: DatabaseResponse
  readinessKind: 'source' | 'sink'
  selected: boolean
  disabled: boolean
  blockOnKnownStatus?: boolean
  onClick: () => void
}) {
  const readinessStatus = databaseReadinessStatus(db, readinessKind)
  const blocked = readinessStatus === 'BLOCKED' || db.connectionStatus === 'UNREACHABLE'
  const choiceDisabled = disabled || (blockOnKnownStatus && blocked)
  return (
    <button
      type="button"
      disabled={choiceDisabled}
      onClick={onClick}
      className={cn(
        'flex w-full items-center gap-2 rounded-md border px-2.5 py-2 text-left',
        selected ? 'border-brand-500 bg-brand-50' : 'border-gray-200 hover:bg-gray-50',
        choiceDisabled && 'cursor-not-allowed opacity-60',
      )}
    >
      <Icon name="database" size={14} className={blocked ? 'text-[#c0392b]' : selected ? 'text-brand-600' : 'text-gray-400'} />
      <span className="min-w-0 flex-1">
        <span className="block truncate text-[12.5px] font-medium text-gray-800">{databaseLabel(db)}</span>
        <span className="block truncate font-mono text-[10.5px] text-gray-400">{databaseEndpoint(db)}</span>
      </span>
      <span className="shrink-0 rounded bg-gray-100 px-1.5 py-0.5 text-[10.5px] font-medium text-gray-500">
        {db.engine}
      </span>
      {readinessStatus && (
        <span
          className={cn(
            'shrink-0 rounded px-1.5 py-0.5 text-[10.5px] font-semibold',
            readinessChipClass(readinessStatus),
          )}
        >
          {readinessStatus}
        </span>
      )}
      {blockOnKnownStatus && blocked && (
        <span className="shrink-0 rounded bg-[#c0392b] px-1.5 py-0.5 text-[10.5px] font-semibold text-white">
          선택 불가
        </span>
      )}
    </button>
  )
}

function PatternChoiceButton({
  title,
  detail,
  icon,
  selected,
  disabled,
  onClick,
}: {
  title: string
  detail: string
  icon: IconName
  selected: boolean
  disabled: boolean
  onClick: () => void
}) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      className={cn(
        'flex items-start gap-2 rounded-md border px-2.5 py-2 text-left',
        selected ? 'border-brand-500 bg-brand-50' : 'border-gray-200 hover:bg-gray-50',
        disabled && 'opacity-60',
      )}
    >
      <Icon name={icon} size={14} className={selected ? 'mt-0.5 text-brand-600' : 'mt-0.5 text-gray-400'} />
      <span className="min-w-0">
        <span className="block break-words font-medium text-gray-800">{title}</span>
        <span className="block break-words text-[11px] text-gray-500">{detail}</span>
      </span>
    </button>
  )
}

function SelectionSummary({
  source,
  sink,
  table,
  pattern,
}: {
  source: DatabaseResponse | null
  sink: DatabaseResponse | null
  table: PipelineWizardTable | null
  pattern: PipelineWizardPattern | null
}) {
  return (
    <div className="grid gap-1.5 rounded-md border border-gray-100 bg-gray-50 px-2.5 py-2 font-mono text-[10.5px] text-gray-500">
      <div className="break-all">source={source ? databaseLabel(source) : '-'}</div>
      <div className="break-all">target={pattern === 'direct' ? (sink ? databaseLabel(sink) : '-') : pattern === 'fan-out' ? 'fan-out topic' : '-'}</div>
      <div className="break-all">table={table ? tableKey(table) : '-'}</div>
    </div>
  )
}

function ReadinessResult({ title, readiness }: { title: string; readiness: CdcReadinessResponse | null }) {
  if (!readiness) return <WizardEmpty text={`${title} readiness 결과가 없습니다.`} />
  return (
    <div className="rounded-md border border-gray-200">
      <div className="flex items-center justify-between gap-2 border-b border-gray-100 px-2.5 py-2">
        <span className="font-semibold text-gray-800">{title}</span>
        <span className={cn('rounded px-1.5 py-0.5 text-[10.5px] font-semibold', readinessChipClass(readiness.overallStatus))}>
          {readiness.overallStatus}
        </span>
      </div>
      <div className="divide-y divide-gray-100">
        {readiness.checks.length === 0 ? (
          <div className="px-2.5 py-2 text-[11.5px] text-gray-400">체크 항목 없음</div>
        ) : (
          readiness.checks.map((check) => (
            <div key={check.name} className="grid grid-cols-[minmax(0,1fr)_auto] gap-2 px-2.5 py-2">
              <div className="min-w-0">
                <div className="break-words font-medium text-gray-700">{check.name}</div>
                <div className="mt-0.5 break-words font-mono text-[10.5px] text-gray-400">
                  actual={check.actual || '-'} / expected={check.expected || '-'}
                </div>
                {check.hint && <div className="mt-0.5 break-words text-[11px] text-gray-500">{check.hint}</div>}
              </div>
              <span className={cn('h-fit rounded px-1.5 py-0.5 text-[10.5px] font-semibold', readinessChipClass(check.status))}>
                {check.status}
              </span>
            </div>
          ))
        )}
      </div>
    </div>
  )
}

function WizardSectionTitle({ title, detail }: { title: string; detail: string }) {
  return (
    <div>
      <div className="text-[12px] font-semibold text-gray-800">{title}</div>
      <div className="break-words text-[11.5px] text-gray-500">{detail}</div>
    </div>
  )
}

function WizardLoading({ text }: { text: string }) {
  return (
    <div className="flex items-center gap-2 rounded-md border border-gray-200 bg-gray-50 px-2.5 py-2 text-[11.5px] text-gray-500">
      <Spinner size={12} />
      <span>{text}</span>
    </div>
  )
}

function WizardError({ text, onRetry }: { text: string; onRetry: () => void }) {
  return (
    <div className="space-y-2 rounded-md border border-[#c0392b] bg-[#fcf3f2] px-2.5 py-2 text-[11.5px] text-[#c0392b]">
      <div className="break-words">{text}</div>
      <button
        type="button"
        onClick={onRetry}
        className="rounded border border-[#c0392b] bg-white px-2 py-1 text-[11.5px] font-medium text-[#c0392b] hover:bg-[#fcf3f2]"
      >
        다시 시도
      </button>
    </div>
  )
}

function WizardEmpty({ text }: { text: string }) {
  return <div className="rounded-md border border-dashed border-gray-200 px-2.5 py-3 text-center text-[11.5px] text-gray-400">{text}</div>
}

function pipelineWizardStepLabel(msg: PipelineWizardMsg) {
  if (msg.loading === 'databases') return 'DB 조회'
  if (msg.loading === 'schema') return '스키마 조회'
  if (msg.loading === 'readiness') return 'readiness'
  if (msg.loading === 'creating') return '생성 중'
  if (msg.step === 'source') return 'Source 선택'
  if (msg.step === 'target') return 'Target 선택'
  if (msg.step === 'table') return '테이블 선택'
  if (msg.step === 'readiness') return '확인'
  return '완료'
}

function pipelineWizardSummary(
  msg: PipelineWizardMsg,
  source: DatabaseResponse | null,
  sink: DatabaseResponse | null,
) {
  if (msg.step === 'created' && msg.created) return `${msg.created.name} · ${msg.created.status}`
  if (!source) return '보유 DB 목록에서 Source를 선택하세요.'
  if (!msg.pattern) return `${databaseLabel(source)} 선택됨`
  if (msg.pattern === 'direct' && !sink) return `${databaseLabel(source)}에서 복제할 Sink DB를 선택하세요.`
  if (!msg.table) return 'Source 스키마에서 테이블을 선택하세요.'
  return `${databaseLabel(source)}.${msg.table.name} 생성 준비`
}

function databaseReadinessStatus(db: DatabaseResponse, kind: 'source' | 'sink'): CdcReadinessResponse['overallStatus'] | null {
  if (kind === 'source') return db.cdcReadinessStatus
  const value = (db as { sinkReadinessStatus?: unknown }).sinkReadinessStatus
  return value === 'OK' || value === 'WARNING' || value === 'BLOCKED' ? value : null
}

function readinessChipClass(status: CdcReadinessResponse['overallStatus']) {
  if (status === 'OK') return 'bg-[#ededed] text-[#6b6b73]'
  if (status === 'WARNING') return 'bg-[#ededed] text-[#6b6b73]'
  return 'bg-[#c0392b] text-white'
}

function StatusCard({ msg, theme }: { msg: StatusMsg; theme: (typeof THEMES)[ThemeName] }) {
  const label = msg.agent ? AGENT_LABEL[msg.agent] ?? msg.agent : msg.eventType
  return (
    <div className="rounded-xl border border-gray-200 bg-white px-3 py-2.5 text-[12px] text-gray-700">
      <div className="mb-1 flex items-center gap-1.5 font-semibold text-gray-800">
        {msg.state === 'running' ? (
          <Spinner size={13} />
        ) : msg.state === 'failed' ? (
          <Icon name="alert" size={13} className="text-[#c0392b]" />
        ) : msg.state === 'waiting' ? (
          <Icon name="clock" size={13} className="text-[#8a8a8a]" />
        ) : (
          <Icon name="check" size={13} strokeWidth={3} className="text-[#8a8a8a]" />
        )}
        <span className={msg.state === 'running' ? theme.statusText : undefined}>{label}</span>
      </div>
      <div className="break-words leading-relaxed text-gray-600">{msg.text}</div>
    </div>
  )
}

function EvidenceCard({
  msg,
  onOpenTrace,
}: {
  msg: EvidenceMsg
  onOpenTrace: (pipelineId: string, traceId: string) => void
}) {
  const hasTraceLink = hasTraceEvidenceLink(msg)
  return (
    <div className="rounded-xl border border-gray-200 bg-white px-3 py-2.5 text-[12px] text-gray-700">
      <div className="mb-1 flex items-center gap-1.5 font-semibold text-gray-800">
        <Icon name="check" size={13} strokeWidth={3} className="text-[#8a8a8a]" />
        <span>Evidence · {msg.evidenceType ?? '증거 수집'}</span>
      </div>
      {msg.summary && <div className="break-words leading-relaxed text-gray-600">{msg.summary}</div>}
      {hasTraceLink && (
        <button
          type="button"
          className="mt-1.5 rounded-md border border-gray-200 px-3 py-1.5 text-[12px] font-medium text-gray-600 hover:bg-gray-50 disabled:opacity-50"
          onClick={() => onOpenTrace(msg.pipelineId, msg.traceId)}
        >
          Tracing 탭에서 상세 보기
        </button>
      )}
    </div>
  )
}

export function RcaPreviewCard({ msg }: { msg: RcaPreviewMsg }) {
  return (
    <div className="rounded-xl border border-[#ececec] bg-white px-3 py-2.5 text-[12px] text-gray-700">
      <div className="mb-2 flex min-w-0 flex-wrap items-center gap-1.5">
        <Icon name="search" size={13} className="shrink-0 text-[#6b6b73]" />
        <span className="shrink-0 font-semibold text-gray-900">근본 원인 분석</span>
        {msg.confidence != null && (
          <span className="shrink-0 rounded bg-gray-100 px-1.5 py-0.5 text-[10.5px] font-medium text-gray-500">
            신뢰도 {Math.round(msg.confidence * 100)}%
          </span>
        )}
        {msg.verified && (
          <span className="shrink-0 rounded bg-emerald-50 px-1.5 py-0.5 text-[10.5px] font-semibold text-emerald-700">
            검증됨
          </span>
        )}
      </div>
      <div className="break-words leading-relaxed text-gray-600">
        {msg.message || (msg.rootCauseId ? '근본 원인 후보가 도착했습니다.' : '근본 원인 분석 결과를 기다리는 중입니다.')}
      </div>
    </div>
  )
}

export function RemediationCard({ msg }: { msg: RemediationMsg }) {
  return (
    <div className="rounded-xl border border-[#ececec] bg-white text-[12px] text-gray-700">
      <div className="flex min-w-0 items-center gap-1.5 border-b border-[#ececec] bg-[#ededed] px-3 py-2">
        <Icon name="shield" size={13} className="shrink-0 text-[#6b6b73]" />
        <span className="shrink-0 font-semibold text-[#6b6b73]">권장 조치</span>
        <span className="shrink-0 rounded bg-white px-1.5 py-0.5 font-mono text-[10.5px] font-semibold text-[#6b6b73]">
          {msg.candidates.length}
        </span>
        {msg.message && <span className="min-w-0 truncate text-[11.5px] text-[#6b6b73]">{msg.message}</span>}
      </div>
      <div className="space-y-2 px-3 py-2.5">
        {msg.candidates.length === 0 ? (
          <PanelEmpty text="권장 조치 후보 데이터가 아직 없습니다." />
        ) : (
          msg.candidates.map((candidate, index) => (
            <div key={`${candidate.actionId}:${index}`} className="rounded-lg border border-gray-100 bg-gray-50 px-2.5 py-2">
              <div className="flex min-w-0 items-start gap-2">
                <div className="min-w-0 flex-1">
                  <div className="flex min-w-0 flex-wrap items-center gap-1.5">
                    <span className="break-words font-semibold text-gray-900">{candidate.actionName}</span>
                    {candidate.risk && (
                      <span className={cn('shrink-0 rounded px-1.5 py-0.5 text-[10.5px] font-semibold', riskChipClass(candidate.risk))}>
                        {candidate.risk}
                      </span>
                    )}
                    {candidate.actionType && (
                      <span className="shrink-0 rounded bg-white px-1.5 py-0.5 font-mono text-[10.5px] text-gray-500">
                        {candidate.actionType}
                      </span>
                    )}
                  </div>
                  {candidate.reason && <div className="mt-1 break-words leading-relaxed text-gray-600">{candidate.reason}</div>}
                  {candidate.expectedEffect && (
                    <div className="mt-1 break-words text-[11.5px] leading-relaxed text-gray-500">
                      기대 효과: {candidate.expectedEffect}
                    </div>
                  )}
                  {candidate.rollbackPlan && (
                    <div className="mt-1 break-words text-[11.5px] leading-relaxed text-gray-500">
                      롤백: {candidate.rollbackPlan}
                    </div>
                  )}
                  <div className="mt-1.5 flex flex-wrap gap-1.5">
                    <span className="max-w-full break-all rounded bg-white px-1.5 py-0.5 font-mono text-[10.5px] text-gray-500">
                      {candidate.actionId}
                    </span>
                    {candidate.rootCauseId && (
                      <span className="max-w-full break-all rounded bg-white px-1.5 py-0.5 font-mono text-[10.5px] text-gray-500">
                        {candidate.rootCauseId}
                      </span>
                    )}
                    {candidate.toolName && (
                      <span className="max-w-full break-all rounded bg-white px-1.5 py-0.5 font-mono text-[10.5px] text-gray-500">
                        {candidate.toolName}
                      </span>
                    )}
                    {candidate.estimatedDuration && (
                      <span className="max-w-full break-all rounded bg-white px-1.5 py-0.5 font-mono text-[10.5px] text-gray-500">
                        {candidate.estimatedDuration}
                      </span>
                    )}
                  </div>
                </div>
              </div>
            </div>
          ))
        )}
      </div>
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
  const counts = progressCounts(msg)
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
            <Icon name="alert" size={12} className="text-[#c0392b]" />
          ) : visualState === 'waiting' ? (
            <Icon name="clock" size={12} className="text-[#8a8a8a]" />
          ) : (
            <Icon name="check" size={12} strokeWidth={3} className="text-[#8a8a8a]" />
          )}
          <span className="shrink-0 font-semibold text-gray-800">진행 단계</span>
          <span className="shrink-0 rounded bg-gray-100 px-1.5 py-0.5 font-mono text-[10.5px] text-gray-500">
            {counts.done}/{counts.total}
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
  if (state === 'failed') return <Icon name="alert" size={12} className="mt-0.5 text-[#c0392b]" />
  if (state === 'waiting') return <Icon name="clock" size={12} className="mt-0.5 text-[#8a8a8a]" />
  return <Icon name="check" size={12} strokeWidth={3} className="mt-0.5 text-[#8a8a8a]" />
}

function ToolPanelCard({
  msg,
  onOpenPipeline,
  onOpenIncident,
}: {
  msg: ToolPanelMsg
  onOpenPipeline?: (id: string) => void
  onOpenIncident?: (id: string) => void
}) {
  const result = asRecord(msg.result)
  const resultError = result ? recordString(result, 'error') : null
  const error = msg.error ?? resultError
  const notice = msg.notice

  return (
    <div className="rounded-lg border border-gray-200 bg-white text-[12px] text-gray-700">
      <div className="space-y-2 border-b border-gray-100 px-3 py-2.5">
        <div className="flex items-start gap-2">
          <div className="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-[#0d0d0d] text-white">
            {msg.state === 'running' ? (
              <Spinner size={13} className="text-white" />
            ) : msg.state === 'failed' ? (
              <Icon name="alert" size={14} />
            ) : (
              <Icon name="table" size={14} />
            )}
          </div>
          <div className="min-w-0 flex-1">
            <div className="break-words font-semibold text-gray-900">{toolLabelKo(msg.toolName)}</div>
            <div className="mt-1 flex flex-wrap gap-1.5">
              {paramChips(msg.toolName, msg.params)
                .filter(([key]) => key !== 'scope')
                .map(([key, value]) => (
                  <span key={key} className="max-w-full break-all rounded bg-gray-100 px-1.5 py-0.5 font-mono text-[10.5px] text-gray-500">
                    {paramLabelKo(key)} {formatParamChipValue(value)}
                  </span>
                ))}
            </div>
          </div>
        </div>
        {error && (
          <div className="break-words rounded border border-[#c0392b] bg-[#fcf3f2] px-2 py-1.5 text-[11.5px] text-[#c0392b]">
            {error}
          </div>
        )}
        {notice && (
          <div className="break-words rounded border border-[#ececec] bg-[#ededed] px-2 py-1.5 text-[11.5px] text-[#6b6b73]">
            {notice}
          </div>
        )}
      </div>
      <div className="px-3 py-2.5">
        {msg.state === 'running' ? (
          <PanelEmpty text="조회 중" />
        ) : (error || notice) && msg.result == null ? null : msg.toolName === 'get_consumer_groups' ? (
          <ConsumerGroupsPanel result={result} />
        ) : msg.toolName === 'list_pipelines' || msg.toolName === 'list_project_pipelines' ? (
          <PipelineStatusPanel result={result} onOpenPipeline={onOpenPipeline} />
        ) : msg.toolName === 'list_connectors' ? (
          <ConnectorStatusPanel result={result} />
        ) : msg.toolName === 'get_connector_status' ? (
          <ConnectorDetailPanel result={result} />
        ) : msg.toolName === 'get_alerts' ? (
          <AlertsPanel result={result} onOpenIncident={onOpenIncident} />
        ) : msg.toolName === 'analyze_event_log' || msg.toolName === 'get_incident_summary' ? (
          <EventSummaryPanel result={result} />
        ) : (
          <GenericToolResultPanel result={msg.result} />
        )}
      </div>
    </div>
  )
}

export function GenericToolResultPanel({ result }: { result: unknown }) {
  if (result == null) return <PanelEmpty text="표시할 결과가 없습니다" />
  return (
    <div className="max-h-72 overflow-auto space-y-2 font-mono text-[11px] leading-relaxed text-gray-600">
      <GenericToolValue value={result} depth={0} />
    </div>
  )
}

function GenericToolValue({ value, depth }: { value: unknown; depth: number }) {
  if (Array.isArray(value)) return <GenericArrayValue items={value} depth={depth} />
  const record = asRecord(value)
  if (record) return <GenericObjectValue record={record} depth={depth} />
  return <GenericPrimitiveBlock value={value} />
}

function GenericObjectValue({ record, depth }: { record: Record<string, unknown>; depth: number }) {
  const entries = Object.entries(record).filter(([, value]) => value !== undefined)
  if (entries.length === 0) return <PanelEmpty text="표시할 결과가 없습니다" />

  const scalarEntries = entries.filter(([, value]) => isGenericScalar(value) && formatGenericScalar(value) !== '-')
  const nestedEntries = entries.filter(([, value]) => !isGenericScalar(value))

  return (
    <div className="space-y-2">
      {scalarEntries.length > 0 && (
        <div className="grid gap-1.5 sm:grid-cols-2">
          {scalarEntries.map(([key, value]) => (
            <GenericScalarField key={key} label={key} value={value} />
          ))}
        </div>
      )}
      {nestedEntries.map(([key, value]) => (
        <GenericNestedSection key={key} label={key} value={value} depth={depth + 1} />
      ))}
    </div>
  )
}

function GenericArrayValue({ items, depth }: { items: unknown[]; depth: number }) {
  const visibleItems = items.filter((item) => item !== undefined)
  if (visibleItems.length === 0) return <PanelEmpty text="표시할 항목이 없습니다" />

  return (
    <div className="space-y-1.5">
      {visibleItems.map((item, index) => {
        const record = asRecord(item)
        if (record) return <GenericRecordCard key={index} record={record} index={index} depth={depth + 1} />
        return (
          <div key={index} className="rounded border border-gray-100 bg-gray-50 px-2 py-1.5">
            <GenericToolValue value={item} depth={depth + 1} />
          </div>
        )
      })}
    </div>
  )
}

function GenericNestedSection({ label, value, depth }: { label: string; value: unknown; depth: number }) {
  return (
    <section className="space-y-1">
      <div className="text-[10.5px] font-semibold uppercase text-gray-400">{formatGenericLabel(label)}</div>
      <GenericToolValue value={value} depth={depth} />
    </section>
  )
}

function GenericRecordCard({
  record,
  index,
  depth,
}: {
  record: Record<string, unknown>
  index: number
  depth: number
}) {
  const headline = genericRecordHeadline(record, index)
  const badges = Object.entries(record).filter(([key, value]) => isGenericBadgeKey(key) && isGenericScalar(value))
  const entries = Object.entries(record).filter(
    ([key, value]) => value !== undefined && key !== headline.key && !isGenericBadgeKey(key),
  )
  const scalarEntries = entries.filter(([, value]) => isGenericScalar(value) && formatGenericScalar(value) !== '-')
  const nestedEntries = entries.filter(([, value]) => !isGenericScalar(value))

  return (
    <article className="space-y-1.5 rounded-md border border-gray-100 bg-gray-50 px-2 py-2">
      <div className="flex flex-wrap items-start gap-1.5">
        <div className="min-w-0 flex-1 break-words font-semibold text-gray-700">{headline.text}</div>
        {badges.map(([key, value]) => (
          <GenericBadge key={key} label={key} value={value} />
        ))}
      </div>
      {scalarEntries.length > 0 && (
        <div className="grid gap-1.5 sm:grid-cols-2">
          {scalarEntries.map(([key, value]) => (
            <GenericScalarField key={key} label={key} value={value} compact />
          ))}
        </div>
      )}
      {nestedEntries.map(([key, value]) => (
        <GenericNestedSection key={key} label={key} value={value} depth={depth + 1} />
      ))}
    </article>
  )
}

function GenericScalarField({ label, value, compact = false }: { label: string; value: unknown; compact?: boolean }) {
  return (
    <div className={cn('min-w-0 rounded border border-gray-100 bg-white px-2', compact ? 'py-1' : 'py-1.5')}>
      <div className="text-[10px] font-semibold uppercase text-gray-400">{formatGenericLabel(label)}</div>
      <div className="mt-0.5 break-words text-gray-700">
        {isGenericBadgeKey(label) || typeof value === 'boolean' ? (
          <GenericBadge label={label} value={value} />
        ) : (
          formatGenericScalar(value)
        )}
      </div>
    </div>
  )
}

function GenericPrimitiveBlock({ value }: { value: unknown }) {
  return (
    <div className="break-words rounded bg-gray-50 px-2 py-2 text-gray-700">
      {formatGenericScalar(value)}
    </div>
  )
}

function GenericBadge({ label, value }: { label: string; value: unknown }) {
  return (
    <span className={cn('inline-flex max-w-full rounded px-1.5 py-0.5 text-[10.5px] font-semibold', genericBadgeClass(label, value))}>
      <span className="truncate">{formatGenericScalar(value)}</span>
    </span>
  )
}

function ConsumerGroupsPanel({ result }: { result: Record<string, unknown> | null }) {
  const rows = recordArray(result, 'consumerGroups', 'consumer_groups')
  if (rows.length === 0) return <PanelEmpty text="Consumer Group 데이터 없음" />

  return (
    <div className="overflow-x-auto">
      <table className="min-w-full table-fixed font-mono text-[11px]">
        <thead className="text-left text-gray-400">
          <tr>
            <th className="w-[42%] py-1 pr-3 font-medium">consumerGroup</th>
            <th className="w-[20%] py-1 pr-3 font-medium">groupState</th>
            <th className="w-[16%] py-1 pr-3 text-right font-medium">lag</th>
            <th className="w-[22%] py-1 font-medium">label(owner)</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row, index) => {
            const group = recordString(row, 'group') ?? '-'
            const state = recordString(row, 'state') ?? 'UNKNOWN'
            const lag = recordNumber(row, 'lag')
            const rowError = recordString(row, 'error')
            const warning = state === 'REBALANCING' || state === 'UNKNOWN' || (lag != null && lag >= 5000) || !!rowError
            return (
              <tr key={`${group}:${index}`} className="border-t border-gray-100 align-top">
                <td className="break-all py-1.5 pr-3 text-gray-700">{warning ? '⚠ ' : ''}{group}</td>
                <td className="break-words py-1.5 pr-3 text-gray-600">{state}</td>
                <td className="py-1.5 pr-3 text-right text-gray-700">{lag == null ? '-' : lag.toLocaleString()}</td>
                <td className="break-words py-1.5 text-gray-500">
                  {recordString(row, 'owner') ?? '-'}
                  {rowError && <div className="mt-0.5 text-[10px] text-[#c0392b]">{rowError}</div>}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

export function PipelineStatusPanel({
  result,
  onOpenPipeline,
}: {
  result: Record<string, unknown> | null
  onOpenPipeline?: (id: string) => void
}) {
  const rows = recordArray(result, 'pipelines')
  if (rows.length === 0) return <PanelEmpty text="파이프라인 데이터 없음" />

  const items = rows.map((row) => {
    const status = (recordString(row, 'status') ?? 'unknown').toLowerCase()
    const lag = recordNumber(row, 'lag')
    const token = lag != null && lag >= 5000 && semanticToken(status) === 'safe' ? 'warn' : semanticToken(status)
    return {
      id: recordString(row, 'id', 'pipeline_id', 'pipelineId'),
      name: recordString(row, 'name') ?? recordString(row, 'id') ?? '-',
      statusLabel: pipelineStatusKo(status),
      token,
      lag,
      error: recordString(row, 'error'),
    }
  })
  const counts = statusCounts(items.map((item) => item.token))

  return (
    <div className="text-[12px]">
      <div className="mb-1.5 text-[11px] text-gray-500">{statusCountText(rows.length, counts)}</div>
      <div className="divide-y divide-gray-100 rounded-md border border-gray-100">
        {items.map((item, index) => {
          const clickable = !!(onOpenPipeline && item.id)
          const right = (
            <span className="flex shrink-0 items-center gap-1.5">
              <span className={cn('inline-flex items-center gap-1', statusTextClass(item.token))}>
                <span className={cn('h-1.5 w-1.5 rounded-full', semanticDotClass(item.token))} />
                {item.statusLabel}
                {item.token === 'warn' && item.lag != null && <span className="font-mono">{item.lag.toLocaleString()}</span>}
                {item.error && <span className="ml-1 truncate font-mono text-[10px] text-[#c0392b]">{item.error}</span>}
              </span>
              {clickable && <Icon name="chevron-right" size={12} className="text-gray-300" />}
            </span>
          )
          const name = <span className="min-w-0 truncate font-medium text-gray-800">{item.name}</span>
          return clickable ? (
            <button
              key={`${item.name}:${index}`}
              onClick={() => onOpenPipeline!(item.id!)}
              title="파이프라인 상세 열기"
              className="flex w-full items-center justify-between gap-2 px-2.5 py-1.5 text-left hover:bg-gray-50"
            >
              {name}
              {right}
            </button>
          ) : (
            <div key={`${item.name}:${index}`} className="flex items-center justify-between gap-2 px-2.5 py-1.5">
              {name}
              {right}
            </div>
          )
        })}
      </div>
    </div>
  )
}

function ConnectorStatusPanel({ result }: { result: Record<string, unknown> | null }) {
  const rows = recordArray(result, 'connectors')
  if (rows.length === 0) return <PanelEmpty text="Connector 데이터 없음" />

  return (
    <div className="overflow-x-auto">
      <table className="min-w-full table-fixed font-mono text-[11px]">
        <thead className="text-left text-gray-400">
          <tr>
            <th className="w-[34%] py-1 pr-3 font-medium">connector</th>
            <th className="w-[14%] py-1 pr-3 font-medium">type</th>
            <th className="w-[22%] py-1 pr-3 font-medium">status</th>
            <th className="w-[14%] py-1 pr-3 text-right font-medium">tasks</th>
            <th className="w-[16%] py-1 text-right font-medium">throughput(/s)</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row, index) => {
            const connector = recordString(row, 'connector') ?? '-'
            const status = recordString(row, 'status') ?? 'UNKNOWN'
            const running = recordNumber(row, 'tasksRunning', 'tasks_running')
            const total = recordNumber(row, 'tasksTotal', 'tasks_total')
            const throughput = recordNumber(row, 'throughputPerSecond', 'throughput_per_second')
            const rowError = recordString(row, 'error')
            const taskWarning = running != null && total != null && running < total
            const warning = status.includes('FAILED') || status === 'UNKNOWN' || taskWarning || !!rowError
            return (
              <tr key={`${connector}:${index}`} className="border-t border-gray-100 align-top">
                <td className="break-all py-1.5 pr-3 text-gray-700">{warning ? '⚠ ' : ''}{connector}</td>
                <td className="break-words py-1.5 pr-3 text-gray-600">{recordString(row, 'type') ?? '-'}</td>
                <td className="break-words py-1.5 pr-3 text-gray-600">{status}</td>
                <td className="py-1.5 pr-3 text-right text-gray-700">{running == null || total == null ? '-/-' : `${running}/${total}`}</td>
                <td className="py-1.5 text-right text-gray-700">
                  {throughput == null ? '-' : throughput.toLocaleString()}
                  {rowError && <div className="mt-0.5 text-[10px] text-[#c0392b]">{rowError}</div>}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

export interface ConnectorStatusSummary {
  name: string | null
  state: string
  stateLabel: string
  stateToken: SemanticToken
  total: number
  running: number
  failed: number
  lastError: string | null
}

const CONNECTOR_STATE_LABEL: Record<string, string> = {
  running: '실행 중',
  paused: '일시정지',
  failed: '실패',
  unassigned: '미할당',
  restarting: '재시작 중',
  destroyed: '제거됨',
  stopped: '중지됨',
}

export function connectorStatusSummary(result: unknown): ConnectorStatusSummary | null {
  const record = asRecord(result)
  if (!record) return null
  const state = (recordString(record, 'state', 'connector_state', 'status') ?? 'UNKNOWN').toUpperCase()
  const tasks = recordArray(record, 'tasks')
  const total = tasks.length
  const running = tasks.filter((task) => (recordString(task, 'state', 'status') ?? '').toUpperCase() === 'RUNNING').length
  const failed = total - running
  const lastError =
    recordString(record, 'last_error', 'lastError', 'trace') ??
    tasks.map((task) => recordString(task, 'trace', 'last_error')).find((value): value is string => !!value) ??
    null
  return {
    name: recordString(record, 'connector_name', 'connector', 'name'),
    state,
    stateLabel: CONNECTOR_STATE_LABEL[state.toLowerCase()] ?? state,
    stateToken: semanticToken(state),
    total,
    running,
    failed,
    lastError,
  }
}

export function ConnectorDetailPanel({ result }: { result: Record<string, unknown> | null }) {
  const summary = connectorStatusSummary(result)
  if (!summary) return <PanelEmpty text="커넥터 상태 데이터 없음" />
  const taskText =
    summary.total === 0
      ? '태스크 없음'
      : summary.failed > 0
        ? `태스크 ${summary.running}/${summary.total} 정상 · ${summary.failed}개 실패`
        : `태스크 ${summary.running}/${summary.total} 정상`
  return (
    <div className="space-y-2 text-[12px]">
      <div className="flex flex-wrap items-center gap-2">
        <span className={cn('inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-[11px] font-semibold', semanticBadgeClass(summary.stateToken))}>
          <span className={cn('h-1.5 w-1.5 rounded-full', semanticDotClass(summary.stateToken))} />
          {summary.stateLabel}
        </span>
        <span className={summary.failed > 0 ? 'text-[#c0392b]' : 'text-gray-600'}>{taskText}</span>
      </div>
      {summary.lastError && (
        <details className="rounded border border-[#e7c9c4] bg-[#fcf3f2] px-2 py-1.5 text-[11.5px] text-[#c0392b]">
          <summary className="cursor-pointer select-none">오류 원인 보기</summary>
          <div className="mt-1 whitespace-pre-wrap break-words font-mono text-[10.5px]">{summary.lastError}</div>
        </details>
      )}
    </div>
  )
}

const SEVERITY_KO: Record<string, string> = {
  critical: '긴급',
  fatal: '긴급',
  error: '오류',
  high: '높음',
  warning: '경고',
  warn: '경고',
  medium: '중간',
  info: '정보',
  low: '낮음',
}

export function severityKo(severity: string) {
  return SEVERITY_KO[severity.toLowerCase()] ?? severity
}

const INCIDENT_STATUS_KO: Record<string, string> = {
  open: '미해결',
  active: '진행 중',
  investigating: '조사 중',
  acknowledged: '확인됨',
  mitigated: '완화됨',
  resolved: '해결',
  closed: '종료',
}

function incidentStatusKo(status: string) {
  return INCIDENT_STATUS_KO[status.toLowerCase()] ?? status
}

// get_incident_summary 는 단일 인시던트(IncidentSummaryData)를 반환할 수 있다.
function IncidentSummaryCard({ result }: { result: Record<string, unknown> }) {
  const id = recordString(result, 'incident_id', 'incidentId')
  const status = recordString(result, 'status') ?? ''
  const severity = recordString(result, 'severity')
  const summary = recordString(result, 'summary', 'note', 'root_cause_summary', 'rootCauseSummary')
  const rootCause = recordString(result, 'root_cause_summary', 'rootCauseSummary')
  return (
    <div className="space-y-2 text-[12px]">
      <div className="flex flex-wrap items-center gap-2">
        {status && (
          <span className={cn('inline-flex items-center rounded px-1.5 py-0.5 text-[11px] font-semibold', semanticBadgeClass(semanticToken(status)))}>
            {incidentStatusKo(status)}
          </span>
        )}
        {severity && <span className="text-gray-500">심각도 {severityKo(severity)}</span>}
        {id && <span className="font-mono text-[10.5px] text-gray-400">#{shortId(id)}</span>}
      </div>
      {summary && <div className="leading-relaxed text-gray-700">{summary}</div>}
      {rootCause && rootCause !== summary && (
        <div className="rounded bg-gray-50 px-2 py-1.5 text-[11.5px] text-gray-600">
          <span className="font-semibold">근본 원인</span> · {rootCause}
        </div>
      )}
    </div>
  )
}

export function AlertsPanel({
  result,
  onOpenIncident,
}: {
  result: Record<string, unknown> | null
  onOpenIncident?: (id: string) => void
}) {
  const rows = recordArray(result, 'alerts')
  if (rows.length === 0) return <PanelEmpty text="인시던트 없음" />
  return (
    <div className="space-y-1.5 text-[12px]">
      {rows.map((row, index) => {
        const severity = (recordString(row, 'severity') ?? '').toLowerCase()
        const token = semanticToken(severity)
        const incidentId = recordString(row, 'incident_id', 'incidentId')
        const clickable = !!(onOpenIncident && incidentId)
        const inner = (
          <>
            <span className={cn('inline-flex shrink-0 items-center rounded px-1.5 py-0.5 text-[10.5px] font-semibold', semanticBadgeClass(token))}>
              {severityKo(severity)}
            </span>
            <span className="min-w-0 flex-1 truncate text-gray-700">{recordString(row, 'summary', 'title') ?? '-'}</span>
            {clickable && <Icon name="chevron-right" size={12} className="shrink-0 text-gray-300" />}
          </>
        )
        return clickable ? (
          <button
            key={index}
            onClick={() => onOpenIncident!(incidentId!)}
            title="인시던트 상세 열기"
            className="flex w-full items-center gap-2 rounded-md border border-gray-100 px-2 py-1.5 text-left hover:bg-gray-50"
          >
            {inner}
          </button>
        ) : (
          <div key={index} className="flex items-center gap-2 rounded-md border border-gray-100 px-2 py-1.5">
            {inner}
          </div>
        )
      })}
    </div>
  )
}

export function EventSummaryPanel({ result }: { result: Record<string, unknown> | null }) {
  if (!result) return <PanelEmpty text="이벤트/인시던트 데이터 없음" />
  const open = recordNumber(result, 'openIncidents', 'open_incidents') ?? 0
  const criticalCount = recordNumber(result, 'criticalIncidents', 'critical_incidents') ?? 0
  const critical = recordArray(result, 'critical')
  const warnings = recordArray(result, 'warnings')

  // 현황(집계/목록) 데이터가 아니고 단일 인시던트 요약이면 요약 카드로 폴백.
  const hasOverview = open > 0 || criticalCount > 0 || critical.length > 0 || warnings.length > 0
  const isSingleIncident = !hasOverview && !!recordString(result, 'incident_id', 'incidentId', 'summary', 'note')
  if (isSingleIncident) return <IncidentSummaryCard result={result} />

  return (
    <div className="space-y-2.5 text-[12px]">
      <div className="text-gray-600">
        미해결 <b className="text-gray-800">{open.toLocaleString()}건</b>
        {criticalCount > 0 && (
          <>
            {' · '}긴급 <b className="text-[#c0392b]">{criticalCount.toLocaleString()}건</b>
          </>
        )}
      </div>
      <div>
        <div className="mb-1 text-[10.5px] font-semibold text-gray-400">긴급</div>
        {critical.length === 0 ? (
          <PanelEmpty text="긴급 인시던트 없음" />
        ) : (
          <div className="space-y-1">
            {critical.map((item, index) => {
              const id = recordString(item, 'incidentId', 'incident_id')
              return (
                <div key={`${id ?? index}`} className="break-words rounded border border-rose-100 bg-rose-50 px-2 py-1.5 text-[11.5px] text-gray-700">
                  ⚠ {recordString(item, 'title') ?? '-'}
                  {id && <span className="text-gray-400"> · #{shortId(id)}</span>}
                </div>
              )
            })}
          </div>
        )}
      </div>
      <div>
        <div className="mb-1 text-[10.5px] font-semibold text-gray-400">경고</div>
        {warnings.length === 0 ? (
          <PanelEmpty text="주요 경고 이벤트 없음" />
        ) : (
          <div className="space-y-1">
            {warnings.map((item, index) => {
              const id = recordString(item, 'eventId', 'event_id')
              return (
                <div key={`${id ?? index}`} className="break-words rounded bg-gray-50 px-2 py-1.5 text-[11.5px] text-gray-600">
                  {recordString(item, 'message') ?? recordString(item, 'type') ?? '-'}
                  {id && <span className="text-gray-400"> · #{shortId(id)}</span>}
                </div>
              )
            })}
          </div>
        )}
      </div>
    </div>
  )
}

function PanelEmpty({ text }: { text: string }) {
  return <div className="rounded bg-gray-50 px-2 py-2 text-[11.5px] text-gray-400">{text}</div>
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
  // 권한이 정상이고 승인 가능한 평상시엔 도움말을 띄우지 않는다(노이즈 제거).
  const help = roleLoading
    ? '승인 권한 확인 중…'
    : roleError
      ? roleError
      : !canApprove
        ? `소유자 또는 관리자만 승인할 수 있습니다${roleLabel ? ` (현재 ${roleLabel})` : ''}`
        : !msg.approvalId
          ? '승인 요청 확인 중…'
          : null

  return (
    <div className="min-w-0 rounded-xl border border-[#e7c9c4] bg-white">
      <div className="flex min-w-0 items-center gap-1.5 border-b border-[#f0dad6] bg-[#fcf3f2] px-3 py-2 text-[12px] font-semibold text-[#c0392b]">
        <Icon name="shield" size={13} className="shrink-0" />
        <span className="min-w-0 break-words">승인 필요</span>
      </div>
      <div className="space-y-1.5 px-3 py-2.5 text-[12px] text-gray-700">
        <div className="break-words leading-relaxed">{msg.message}</div>
        {msg.reason && msg.reason !== msg.message && (
          <div className="break-words text-[11.5px] text-gray-500">{msg.reason}</div>
        )}
        {(msg.error || help) && <div className={cn('break-words text-[11.5px]', msg.error || roleError ? 'text-[#c0392b]' : 'text-gray-400')}>{msg.error ?? help}</div>}
      </div>
      {resolved ? (
        <div className={cn('border-t border-gray-100 px-3 py-2 text-[11.5px] font-medium', msg.state === 'approved' ? 'text-[#6b6b73]' : 'text-[#c0392b]')}>
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

function payloadRequiredFlow(event: AgentRunEvent): string[] | null {
  const value = event.payload?.required_flow
  if (!Array.isArray(value)) return null
  const stages = value.filter((entry): entry is string => typeof entry === 'string')
  return stages.length > 0 ? stages : null
}

// #604: required_flow가 있으면 분모를 전체 stage 수로 고정하고 분자도 그 stage들의
// 완료 수만 센다(run/router 등 부가 항목 제외). 없으면(구버전 백엔드) 기존 동작 유지.
export function progressCounts(msg: { items: ProgressItem[]; requiredFlow: string[] | null }) {
  if (msg.requiredFlow && msg.requiredFlow.length > 0) {
    const done = msg.requiredFlow.filter((stage) =>
      msg.items.some((item) => item.key.endsWith(`:stage:${stage}`) && item.state === 'done'),
    ).length
    return { done, total: msg.requiredFlow.length }
  }
  return { done: msg.items.filter((item) => item.state === 'done').length, total: msg.items.length }
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

export function slashSelectionSubmissionText(
  value: string,
  route: ReturnType<typeof routeAgentInput>,
  command: SlashToolCommand,
) {
  if (route.kind === 'slash_execute' && route.parsed.command.toolName === command.toolName) return value
  if (route.kind === 'slash_missing_args' && firstToken(route.input) === command.label) return value
  if (command.argParams.length > 0) return command.label
  return null
}

export function slashMissingArgsFeedback(
  value: string,
  route: Extract<ReturnType<typeof routeAgentInput>, { kind: 'slash_missing_args' }>,
) {
  return {
    userText: value.trim(),
    assistantText: route.message,
    input: route.input,
  }
}

function firstToken(value: string) {
  return value.trim().split(/\s+/, 1)[0] ?? ''
}

function failureSummary(event: AgentRunEvent) {
  const error = payloadString(event, 'error')
  return event.message || error || 'Agent Run 처리 중 오류가 발생했습니다.'
}

function isRcaStageEvent(event: AgentRunEvent) {
  const stage = payloadString(event, 'stage')?.toLowerCase()
  const agent = event.agent?.toLowerCase()
  return stage === 'rca' || agent === 'rca' || payloadRootCauseId(event) != null
}

function isRemediationStageEvent(event: AgentRunEvent) {
  const stage = payloadString(event, 'stage')?.toLowerCase()
  const agent = event.agent?.toLowerCase()
  return stage === 'remediation' || agent === 'remediation' || remediationCandidatesFromPayload(event.payload).length > 0
}

function payloadRootCauseId(event: AgentRunEvent) {
  return payloadString(event, 'root_cause_id')
    ?? payloadString(event, 'rootCauseId')
    ?? payloadString(event, 'selected_root_cause_id')
    ?? payloadString(event, 'selectedRootCauseId')
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

function payloadNumber(event: AgentRunEvent, key: string): number | null {
  const value = event.payload[key]
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function payloadBoolean(event: AgentRunEvent, key: string): boolean | null {
  const value = event.payload[key]
  return typeof value === 'boolean' ? value : null
}

function payloadRecord(event: AgentRunEvent, key: string): Record<string, unknown> | null {
  return asRecord(event.payload[key])
}

function payloadErrorMessage(event: AgentRunEvent): string | null {
  const error = event.payload.error
  if (typeof error === 'string') return error
  const record = asRecord(error)
  return recordString(record, 'message') ?? (event.type === 'tool_call_failed' ? event.message : null)
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value != null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null
}

function recordArray(record: Record<string, unknown> | null, ...keys: string[]): Record<string, unknown>[] {
  if (!record) return []
  for (const key of keys) {
    const value = record[key]
    if (Array.isArray(value)) return value.map(asRecord).filter((item): item is Record<string, unknown> => item != null)
  }
  return []
}

function recordString(record: Record<string, unknown> | null, ...keys: string[]): string | null {
  if (!record) return null
  for (const key of keys) {
    const value = record[key]
    if (typeof value === 'string' && value) return value
  }
  return null
}

function recordNumber(record: Record<string, unknown> | null, ...keys: string[]): number | null {
  if (!record) return null
  for (const key of keys) {
    const value = record[key]
    if (typeof value === 'number' && Number.isFinite(value)) return value
    if (typeof value === 'string' && value.trim()) {
      const parsed = Number(value)
      if (Number.isFinite(parsed)) return parsed
    }
  }
  return null
}

function isGenericScalar(value: unknown) {
  return value == null || typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean'
}

function genericRecordHeadline(record: Record<string, unknown>, index: number) {
  for (const key of GENERIC_HEADLINE_KEYS) {
    const value = record[key]
    if (isGenericScalar(value)) {
      const text = formatGenericScalar(value)
      if (text !== '-') return { key, text }
    }
  }
  return { key: null, text: `item ${index + 1}` }
}

function isGenericBadgeKey(key: string) {
  return GENERIC_BADGE_KEYS.has(key.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toLowerCase())
}

const FIELD_LABEL_KO: Record<string, string> = {
  state: '상태',
  status: '상태',
  connector_state: '상태',
  connector_name: '커넥터',
  connector: '커넥터',
  pipeline: '파이프라인',
  pipeline_id: '파이프라인',
  name: '이름',
  tasks: '태스크',
  task_id: '태스크',
  trace: '오류',
  last_error: '오류',
  error: '오류',
  worker_id: '워커',
  lag: '지연',
  partition: '파티션',
  offset: '오프셋',
  topic: '토픽',
  group: '그룹',
  consumer_group: '컨슈머 그룹',
  type: '종류',
  throughput: '처리량',
  throughput_per_second: '초당 처리량',
  count: '개수',
  severity: '심각도',
  title: '제목',
  message: '메시지',
  level: '레벨',
  incident_id: '인시던트',
  observed_at: '관측 시각',
  updated_at: '갱신 시각',
  created_at: '생성 시각',
}

function formatGenericLabel(key: string) {
  const normalized = key.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toLowerCase()
  return (
    FIELD_LABEL_KO[normalized] ??
    key
      .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
      .replace(/[_-]+/g, ' ')
      .trim()
  )
}

function formatGenericScalar(value: unknown) {
  if (value == null || value === '') return '-'
  if (typeof value === 'number') return Number.isFinite(value) ? value.toLocaleString() : '-'
  if (typeof value === 'boolean') return value ? 'true' : 'false'
  return String(value)
}

// 의미색 토큰 — 모노크롬 기반 위에 안전(초록)/주의(주황)/위험(빨강)만 선택적으로 입힌다.
export type SemanticToken = 'safe' | 'warn' | 'danger' | 'neutral'

const SEMANTIC_DANGER = ['critical', 'error', 'failed', 'failure', 'fail', 'blocked', 'down', 'high', 'forbidden', 'unassigned', 'destroyed', 'stopped']
const SEMANTIC_WARN = ['warning', 'warn', 'degraded', 'pending', 'rebalancing', 'medium', 'lag', 'paused', 'restarting']
const SEMANTIC_SAFE = ['healthy', 'running', 'success', 'succeeded', 'active', 'resolved', 'closed', 'read_only', 'normal', 'ok', 'low']

export function semanticToken(value: string): SemanticToken {
  const n = value.toLowerCase()
  if (SEMANTIC_DANGER.some((token) => n.includes(token))) return 'danger'
  if (SEMANTIC_WARN.some((token) => n.includes(token))) return 'warn'
  if (SEMANTIC_SAFE.some((token) => n.includes(token))) return 'safe'
  return 'neutral'
}

export function semanticBadgeClass(token: SemanticToken) {
  if (token === 'danger') return 'bg-[#c0392b] text-white'
  if (token === 'warn') return 'bg-amber-100 text-amber-700'
  if (token === 'safe') return 'bg-emerald-100 text-emerald-700'
  return 'bg-gray-100 text-gray-600'
}

function semanticDotClass(token: SemanticToken) {
  if (token === 'danger') return 'bg-[#c0392b]'
  if (token === 'warn') return 'bg-amber-500'
  if (token === 'safe') return 'bg-emerald-500'
  return 'bg-gray-400'
}

function statusTextClass(token: SemanticToken) {
  if (token === 'danger') return 'text-[#c0392b]'
  if (token === 'warn') return 'text-amber-700'
  if (token === 'safe') return 'text-emerald-700'
  return 'text-gray-500'
}

const PIPELINE_STATUS_KO: Record<string, string> = {
  running: '정상',
  ok: '정상',
  healthy: '정상',
  active: '정상',
  lag: '지연',
  degraded: '지연',
  pending: '지연',
  error: '오류',
  failed: '오류',
  down: '오류',
  stopped: '중지',
  paused: '일시정지',
  unknown: '알 수 없음',
}

export function pipelineStatusKo(status: string) {
  return PIPELINE_STATUS_KO[status.toLowerCase()] ?? status
}

export function statusCounts(tokens: SemanticToken[]): Record<SemanticToken, number> {
  return tokens.reduce(
    (acc, token) => {
      acc[token] += 1
      return acc
    },
    { safe: 0, warn: 0, danger: 0, neutral: 0 } as Record<SemanticToken, number>,
  )
}

function statusCountText(total: number, counts: Record<SemanticToken, number>) {
  const parts = [`전체 ${total}개`]
  if (counts.safe) parts.push(`정상 ${counts.safe}`)
  if (counts.warn) parts.push(`지연 ${counts.warn}`)
  if (counts.danger) parts.push(`오류 ${counts.danger}`)
  return parts.join(' · ')
}

function genericBadgeClass(label: string, value: unknown) {
  if (typeof value === 'boolean') return value ? 'bg-emerald-100 text-emerald-700' : 'bg-gray-100 text-gray-600'
  const token = semanticToken(formatGenericScalar(value))
  if (token !== 'neutral') return semanticBadgeClass(token)
  return 'bg-gray-100 text-gray-600'
}
export function remediationCandidatesFromPayload(payload: unknown): RemediationCandidate[] {
  const seen = new Set<string>()
  const candidates: RemediationCandidate[] = []
  for (const raw of actionCandidateRecords(payload)) {
    const candidate = normalizeRemediationCandidate(raw)
    if (!candidate) continue
    const key = candidate.actionId || candidate.actionName
    if (seen.has(key)) continue
    seen.add(key)
    candidates.push(candidate)
  }
  return candidates
}

function actionCandidateRecords(payload: unknown): Record<string, unknown>[] {
  const record = asRecord(payload)
  if (!record) return []
  const records: Record<string, unknown>[] = []
  for (const key of ['action_candidates', 'actionCandidates', 'candidates']) {
    const value = record[key]
    if (Array.isArray(value)) {
      records.push(...value.map(asRecord).filter((item): item is Record<string, unknown> => item != null))
    }
  }
  for (const key of ['final_response', 'finalResponse', 'remediation']) {
    records.push(...actionCandidateRecords(record[key]))
  }
  return records
}

function normalizeRemediationCandidate(record: Record<string, unknown>): RemediationCandidate | null {
  const id = recordString(record, 'action_id', 'actionId', 'id')
  const actionName = recordString(record, 'action_name', 'actionName', 'name', 'label') ?? id
  if (!actionName) return null
  return {
    actionId: id ?? actionName,
    actionType: recordString(record, 'action_type', 'actionType', 'type'),
    actionName,
    rootCauseId: recordString(record, 'root_cause_id', 'rootCauseId'),
    risk: recordString(record, 'risk'),
    reason: recordString(record, 'reason', 'detail', 'description'),
    expectedEffect: recordString(record, 'expected_effect', 'expectedEffect', 'effect'),
    rollbackPlan: recordString(record, 'rollback_plan', 'rollbackPlan'),
    estimatedDuration: recordString(record, 'estimated_duration', 'estimatedDuration', 'estimated_time', 'estimatedTime'),
    toolName: recordString(record, 'tool_name', 'toolName'),
  }
}

function mergeRemediationCandidates(current: RemediationCandidate[], next: RemediationCandidate[]) {
  const merged = new Map<string, RemediationCandidate>()
  for (const candidate of current) merged.set(candidate.actionId || candidate.actionName, candidate)
  for (const candidate of next) merged.set(candidate.actionId || candidate.actionName, candidate)
  return [...merged.values()]
}

function riskChipClass(risk: string) {
  return semanticBadgeClass(semanticToken(risk))
}

const PARAM_LABEL_KO: Record<string, string> = {
  connector_name: '커넥터',
  connector: '커넥터',
  pipeline_id: '파이프라인',
  pipelineId: '파이프라인',
  consumer_group: '컨슈머 그룹',
  group: '컨슈머 그룹',
  incident_id: '인시던트',
  incidentId: '인시던트',
  database_id: '데이터베이스',
  table: '테이블',
  topic: '토픽',
  query: '검색어',
  metric: '지표',
}

function paramLabelKo(key: string) {
  return PARAM_LABEL_KO[key] ?? formatGenericLabel(key)
}

function formatParamChipValue(value: unknown) {
  if (typeof value !== 'string') return formatParamValue(value)
  return value.length > 22 ? `…${value.slice(-14)}` : value
}

function paramChips(toolName: string, params: Record<string, unknown>): [string, unknown][] {
  const entries = Object.entries(params)
  if (entries.length > 0) return entries
  if (toolName === 'get_consumer_groups' || toolName === 'list_pipelines' || toolName === 'list_connectors') {
    return [['scope', 'current_project']]
  }
  return []
}

function isStructuredTool(toolName: string) {
  return Object.prototype.hasOwnProperty.call(STRUCTURED_TOOL_INTRO, toolName)
}

function toolDescription(toolName: string) {
  return STRUCTURED_TOOL_INTRO[toolName] ?? toolName.replace(/_/g, ' ')
}

function formatParamValue(value: unknown) {
  if (typeof value === 'string') return `"${value}"`
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  if (value == null) return 'null'
  return JSON.stringify(value)
}

function shortId(value: string) {
  return value.length > 12 ? value.slice(0, 8) : value
}
