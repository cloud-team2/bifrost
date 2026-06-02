import { useEffect, useRef, useState } from 'react'
import { Icon } from '../../components/Icon'
import { Spinner } from '../../components/ui'
import { useToast } from '../../components/Toast'
import { useApp, type AgentTask } from '../../store/AppStore'
import { pipelineLabel } from '../../data/helpers'
import { cn } from '../../lib/format'

/* ------------------------------------------------------------------ types */

interface Step { label: string; detail: string }

interface ReadScenario {
  kind: 'read'
  match: RegExp
  intro: string
  toolName: string
  toolParams: string
  toolResult: (app: ReturnType<typeof useApp>) => string
}

interface WriteScenario {
  kind: 'write'
  match: RegExp
  title: string
  intro: string
  steps: Step[]
  warning?: string
  summary: string
}

type Scenario = ReadScenario | WriteScenario

interface TextMsg { id: number; kind: 'text'; role: 'user' | 'assistant'; text: string }
interface ToolMsg { id: number; kind: 'tool'; toolName: string; toolParams: string; result: string }
interface PlanMsg { id: number; kind: 'plan'; scenario: WriteScenario; state: 'pending' | 'approved' | 'cancelled' }
interface ExecMsg { id: number; kind: 'exec'; scenario: WriteScenario; current: number }
interface DoneMsg { id: number; kind: 'done'; text: string }
type Msg = TextMsg | ToolMsg | PlanMsg | ExecMsg | DoneMsg

/* --------------------------------------------------------------- scenarios */

const SCENARIOS: Scenario[] = [
  /* read: pipeline */
  {
    kind: 'read',
    match: /파이프라인.*(목록|상태|확인)|pipeline.*(list|status)|list.*pipe/i,
    intro: '현재 프로젝트의 파이프라인 상태를 조회합니다.',
    toolName: 'list_pipelines',
    toolParams: 'scope="current_project"',
    toolResult: (app) => {
      const edges = app.edges.filter((e) => app.currentProject?.pipelineIds.includes(e.id))
      if (!edges.length) return '파이프라인 없음'
      return edges
        .map((e) => {
          const lag = e.metrics?.lag ?? 0
          const warn = e.status === 'error' || e.status === 'lag' ? ' ⚠' : ''
          return `${e.id.padEnd(6)}  ${pipelineLabel(e).substring(0, 24).padEnd(26)}  ${e.status.padEnd(10)}  lag:${lag.toLocaleString().padStart(6)}${warn}`
        })
        .join('\n')
    },
  },

  /* read: database */
  {
    kind: 'read',
    match: /db.*상태|database.*(health|상태|확인)|데이터베이스.*(상태|확인)/i,
    intro: '등록된 데이터베이스의 연결 상태와 Replication lag을 조회합니다.',
    toolName: 'get_db_status',
    toolParams: 'scope="current_project"',
    toolResult: (app) => {
      const dbs = app.nodes.filter(
        (n) => n.type === 'database' && app.currentProject?.dbIds.includes(n.id),
      )
      if (!dbs.length) return 'DB 없음'
      return dbs
        .map((d) => {
          const lag = d.metrics?.lag_ms ?? 0
          const warn = d.status !== 'healthy' ? ' ⚠' : ''
          return `${(d.alias ?? d.label).substring(0, 22).padEnd(24)}  ${d.status.padEnd(8)}  lag:${lag}ms   ${d.tech ?? 'db'} ${d.host}${warn}`
        })
        .join('\n')
    },
  },

  /* read: consumer groups */
  {
    kind: 'read',
    match: /consumer.*(group|lag|분석)|컨슈머.*(지연|그룹|분석)|lag.*분석/i,
    intro: 'Consumer Group의 lag 현황과 상태를 조회합니다.',
    toolName: 'get_consumer_groups',
    toolParams: 'scope="current_project"',
    toolResult: (app) => {
      const svcs = app.nodes.filter((n) => n.type === 'service' && n.consumerGroup)
      if (!svcs.length) return '조회 가능한 Consumer Group 없음'
      return svcs
        .map((s) => {
          const lag = s.lag ?? 0
          const warn = s.groupState === 'REBALANCING' || lag >= 5000 ? ' ⚠' : ''
          return `${(s.consumerGroup ?? '').padEnd(24)}  ${(s.groupState ?? 'UNKNOWN').padEnd(12)}  lag:${lag.toLocaleString().padStart(7)}  ${s.label}${warn}`
        })
        .join('\n')
    },
  },

  /* read: connectors */
  {
    kind: 'read',
    match: /connector.*(상태|status|조회)|커넥터.*(상태|조회)/i,
    intro: 'Kafka Connector 상태 및 Task 정보를 조회합니다.',
    toolName: 'get_connector_status',
    toolParams: 'api="GET /connectors/{name}/status"',
    toolResult: () =>
      [
        ['orders-source',    'Source', 'RUNNING',           '2/2', '1,840/s'],
        ['users-source',     'Source', 'RUNNING',           '1/1', '420/s'],
        ['audit-source',     'Source', 'PARTIALLY_FAILED',  '1/2', '45/s  ⚠'],
        ['txn-source',       'Source', 'RUNNING',           '2/2', '3,120/s'],
        ['analytics-sink',   'Sink',   'RUNNING',           '4/4', '—'],
        ['fraud-store-sink', 'Sink',   'RUNNING',           '2/2', '—'],
      ]
        .map(
          ([name, kind, status, tasks, rps]) =>
            `${name.padEnd(20)}  ${kind.padEnd(7)}  ${status.padEnd(22)}  tasks:${tasks.padEnd(5)}  ${rps}`,
        )
        .join('\n'),
  },

  /* read: kafka topics */
  {
    kind: 'read',
    match: /kafka.*topic|토픽.*(상태|목록)|topic.*status/i,
    intro: 'Kafka 토픽의 produce/consume 처리량을 조회합니다.',
    toolName: 'get_kafka_topics',
    toolParams: 'scope="current_project"',
    toolResult: (app) => {
      const edges = app.edges.filter((e) => app.currentProject?.pipelineIds.includes(e.id))
      if (!edges.length) return '토픽 없음'
      return edges
        .map((e) => {
          const pr = e.metrics?.produce_rate ?? 0
          const cr = e.metrics?.consume_rate ?? 0
          const warn = e.status === 'lag' || e.status === 'error' ? ' ⚠' : ''
          return `${e.topic.substring(0, 32).padEnd(34)}  ${String(e.partitions) + 'p'}  produce:${String(pr).padStart(6)}/s  consume:${String(cr).padStart(6)}/s${warn}`
        })
        .join('\n')
    },
  },

  /* read: pod / service status */
  {
    kind: 'read',
    match: /pod.*상태|service.*status|k8s|파드|서비스.*상태/i,
    intro: 'Kubernetes Pod 및 Consumer 서비스 상태를 조회합니다.',
    toolName: 'get_pod_status',
    toolParams: 'namespace="commerce,fraud"',
    toolResult: (app) => {
      const svcs = app.nodes.filter((n) => n.type === 'service')
      if (!svcs.length) return '서비스 없음'
      return svcs
        .map((s) => {
          const warn = s.status !== 'healthy' ? ' ⚠' : ''
          return `${s.label.padEnd(22)}  ${s.status.padEnd(8)}  ${s.host}  lang:${s.lang ?? '—'}${warn}`
        })
        .join('\n')
    },
  },

  /* read: event log */
  {
    kind: 'read',
    match: /이벤트.*(로그|분석)|event.*log|log.*분석|인시던트.*현황/i,
    intro: '최근 2시간 이벤트 로그와 인시던트 현황을 분석합니다.',
    toolName: 'analyze_event_log',
    toolParams: 'window="2h" level="warn+"',
    toolResult: (app) => {
      const open = app.incidents.filter((i) => i.status !== 'resolved')
      const critical = open.filter((i) => i.severity === 'critical').length
      return [
        `인시던트   open:${open.length}건  (critical:${critical}건)`,
        '',
        ...open.map((i) => `  [${i.severity.toUpperCase().padEnd(8)}]  ${i.title}`),
        '',
        '주요 경고 이벤트:',
        '  cg-audit REBALANCING 30분↑ → lag 7,400건',
        '  cg-fraud-detector REBALANCING → lag 18,400건',
        '  audit-source Connector Task 1개 FAILED',
      ].join('\n')
    },
  },

  /* write: pause */
  {
    kind: 'write',
    match: /pause|일시정지|파이프라인.*멈/i,
    title: '파이프라인 일시정지',
    intro: '파이프라인을 일시정지합니다. Kafka 토픽과 오프셋은 보존되며 Resume 시 마지막 오프셋부터 재개됩니다.',
    steps: [
      { label: '대상 파이프라인 확인', detail: 'active / lag 상태 파이프라인 스캔' },
      { label: 'Source Connector 일시정지', detail: 'PUT /connectors/{name}/pause' },
      { label: '상태 전이 확인', detail: 'RUNNING → PAUSED 확인 (최대 10초)' },
    ],
    summary: '파이프라인 일시정지 완료. 토픽 오프셋 보존 · Resume 시 데이터 손실 없이 재개됩니다.',
  },

  /* write: resume */
  {
    kind: 'write',
    match: /resume|재개|파이프라인.*다시/i,
    title: '파이프라인 재개',
    intro: '일시정지된 파이프라인을 재개합니다. 마지막 커밋 오프셋부터 수집을 다시 시작합니다.',
    steps: [
      { label: 'PAUSED Connector 확인', detail: 'PAUSED 상태 Connector 목록 조회' },
      { label: 'Connector 재개', detail: 'PUT /connectors/{name}/resume' },
      { label: '처리량 회복 확인', detail: 'produce_rate > 0 확인 (30초 모니터링)' },
    ],
    summary: '파이프라인 재개 완료. 오프셋 갭 없이 정상 수집 중입니다.',
  },

  /* write: connector restart */
  {
    kind: 'write',
    match: /connector.*restart|커넥터.*재시작|재시작.*커넥터/i,
    title: 'Connector 재시작',
    intro: 'FAILED / PARTIALLY_FAILED 상태 Connector를 재시작합니다.',
    steps: [
      { label: 'Connector 상태 확인', detail: 'Task 오류 코드 및 마지막 실패 지점 조회' },
      { label: 'Failed Task 재시작', detail: 'POST /connectors/{name}/tasks/{task}/restart' },
      { label: '30초 모니터링', detail: 'RUNNING 상태 + records/sec 회복 확인' },
    ],
    warning: '재시작 중 일부 레코드가 중복 처리될 수 있습니다 (at-least-once).',
    summary: 'Connector RUNNING 상태 복구 완료. 모든 Task 정상 동작 중입니다.',
  },

  /* write: rebalance */
  {
    kind: 'write',
    match: /rebalance|리밸런|파티션.*균형/i,
    title: '파티션 리밸런싱',
    intro: '브로커 간 리더 파티션을 재분배합니다. 특정 브로커에 쏠린 리더십을 균등하게 분산합니다.',
    steps: [
      { label: '파티션 분포 분석', detail: '브로커별 리더/레플리카 분산 현황 스캔' },
      { label: '리밸런싱 계획 수립', detail: '이전 대상 파티션 산정 (스로틀 500MB/s)' },
      { label: 'Preferred 리더 선출', detail: 'AdminClient.electLeaders() 호출' },
      { label: '결과 검증', detail: 'Under-replicated 파티션 없음 + 균등 분산 확인' },
    ],
    warning: '리더 선출 중 해당 파티션의 produce가 일시 중단됩니다 (~2초).',
    summary: '리밸런싱 완료. 리더 파티션이 브로커 간 균등 분산되었습니다.',
  },
]

const QUICK = ['파이프라인 상태 확인', 'Consumer Group lag 분석', 'Connector 상태 조회', '이벤트 로그 분석']
const RISK_LABEL: Record<AgentTask['risk'], string> = { low: '낮음', medium: '중간', high: '높음' }

/* --------------------------------------------------------------- component */

export function BifrostAgent({ viewLabel: _viewLabel }: { viewLabel: string }) {
  const app = useApp()
  const toast = useToast()
  const [msgs, setMsgs] = useState<Msg[]>([])
  const [input, setInput] = useState('')
  const [running, setRunning] = useState(false)
  const seq = useRef(1)
  const scroll = useRef<HTMLDivElement>(null)
  const taskHandled = useRef(false)

  useEffect(() => {
    scroll.current?.scrollTo({ top: scroll.current.scrollHeight, behavior: 'smooth' })
  }, [msgs])

  useEffect(() => {
    if (!app.agentTask) { taskHandled.current = false; return }
    if (taskHandled.current) return
    taskHandled.current = true
    const task = app.agentTask
    app.consumeAgentTask()
    startTask(task)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [app.agentTask])

  function send(text: string) {
    const v = text.trim()
    if (!v || running) return
    setMsgs((m) => [...m, { id: ++seq.current, kind: 'text', role: 'user', text: v }])
    setInput('')

    const sc = SCENARIOS.find((s) => s.match.test(v))
    setTimeout(() => {
      if (!sc) {
        setMsgs((m) => [
          ...m,
          { id: ++seq.current, kind: 'text', role: 'assistant', text: '파이프라인 조회, DB·Connector·Consumer Group·Pod 상태, 이벤트 로그 분석, Pause/Resume, Connector 재시작, 파티션 리밸런싱을 지원합니다.' },
        ])
        return
      }
      if (sc.kind === 'read') {
        const toolId = ++seq.current
        setMsgs((m) => [
          ...m,
          { id: ++seq.current, kind: 'text', role: 'assistant', text: sc.intro },
          { id: toolId, kind: 'tool', toolName: sc.toolName, toolParams: sc.toolParams, result: '' },
        ])
        setTimeout(() => {
          setMsgs((m) =>
            m.map((x) =>
              x.id === toolId && x.kind === 'tool' ? { ...x, result: sc.toolResult(app) } : x,
            ),
          )
        }, 700)
      } else {
        setMsgs((m) => [
          ...m,
          { id: ++seq.current, kind: 'text', role: 'assistant', text: sc.intro },
          { id: ++seq.current, kind: 'plan', scenario: sc, state: 'pending' },
        ])
      }
    }, 600)
  }

  function approve(planId: number, sc: WriteScenario) {
    setMsgs((m) => m.map((x) => (x.id === planId && x.kind === 'plan' ? { ...x, state: 'approved' } : x)))
    const execId = ++seq.current
    setMsgs((m) => [...m, { id: execId, kind: 'exec', scenario: sc, current: 0 }])
    runSteps(execId, sc, () => toast(`${sc.title} — 완료`))
  }

  function cancel(planId: number) {
    setMsgs((m) => m.map((x) => (x.id === planId && x.kind === 'plan' ? { ...x, state: 'cancelled' } : x)))
  }

  function runSteps(execId: number, sc: WriteScenario, onComplete?: () => void) {
    setRunning(true)
    let i = 0
    const tick = () => {
      i++
      if (i < sc.steps.length) {
        setMsgs((m) => m.map((x) => (x.id === execId && x.kind === 'exec' ? { ...x, current: i } : x)))
        setTimeout(tick, 1100)
      } else {
        setMsgs((m) => m.map((x) => (x.id === execId && x.kind === 'exec' ? { ...x, current: -1 } : x)))
        setMsgs((m) => [...m, { id: ++seq.current, kind: 'done', text: sc.summary }])
        setRunning(false)
        onComplete?.()
      }
    }
    setTimeout(tick, 1100)
  }

  function startTask(task: AgentTask) {
    const sc: WriteScenario = {
      kind: 'write',
      match: /(?!)/,
      title: task.label,
      intro: `인시던트 '${task.incidentTitle}'의 권장 조치를 실행합니다. ${task.detail}`,
      steps: [
        { label: '인시던트 컨텍스트 수집', detail: `'${task.incidentTitle}' 관련 메트릭·리소스 상태 조회` },
        { label: '사전 조건 검증', detail: `리스크: ${RISK_LABEL[task.risk]} · 예상 소요: ${task.estimatedTime}` },
        { label: '조치 실행', detail: task.label },
        { label: '사후 검증', detail: '조치 효과 측정 + 시스템 안정성 확인' },
      ],
      warning: task.risk === 'low' ? undefined : '이 조치는 운영 트래픽에 일시적인 영향을 줄 수 있습니다.',
      summary: `'${task.label}' 완료. 사후 검증 결과 시스템 안정 상태입니다.`,
    }
    const execId = ++seq.current
    setMsgs((m) => [
      ...m,
      { id: ++seq.current, kind: 'text', role: 'assistant', text: sc.intro },
      { id: ++seq.current, kind: 'plan', scenario: sc, state: 'approved' },
      { id: execId, kind: 'exec', scenario: sc, current: 0 },
    ])
    runSteps(execId, sc, () => {
      app.runIncidentAction(task.incidentId, task.actionId)
      toast(`${task.label} — 완료`)
    })
  }

  return (
    <div className="flex flex-1 flex-col overflow-hidden">
      {/* Header */}
      <div className="flex items-center gap-2 border-b border-gray-200 px-4 py-3">
        <div className="flex h-7 w-7 items-center justify-center rounded-md bg-gradient-to-br from-brand-500 to-violet-600 text-white">
          <Icon name="zap" size={15} />
        </div>
        <div className="min-w-0 flex-1">
          <div className="text-[13px] font-semibold text-gray-900">Bifrost Agent</div>
          <div className="text-[11px] text-gray-400">{app.currentProject?.name ?? '—'}</div>
        </div>
      </div>

      {/* Messages */}
      <div ref={scroll} className="flex-1 space-y-3 overflow-y-auto scroll-thin bg-gray-50 px-4 py-4">
        {msgs.map((m) => {
          if (m.kind === 'text')
            return (
              <div key={m.id} className={cn('flex', m.role === 'user' ? 'justify-end' : 'justify-start')}>
                <div
                  className={cn(
                    'max-w-[88%] rounded-xl px-3 py-2 text-[12.5px] leading-relaxed',
                    m.role === 'user'
                      ? 'rounded-br-sm bg-brand-600 text-white'
                      : 'rounded-bl-sm border border-gray-200 bg-white text-gray-700',
                  )}
                >
                  {m.text}
                </div>
              </div>
            )
          if (m.kind === 'tool') return <ToolCard key={m.id} msg={m} />
          if (m.kind === 'plan')
            return (
              <PlanCard
                key={m.id}
                msg={m}
                onApprove={() => approve(m.id, m.scenario)}
                onCancel={() => cancel(m.id)}
                disabled={running}
              />
            )
          if (m.kind === 'exec') return <ExecCard key={m.id} msg={m} />
          return (
            <div
              key={m.id}
              className="rounded-xl border border-emerald-200 bg-emerald-50 px-3 py-2.5 text-[12.5px] leading-relaxed text-emerald-800"
            >
              <div className="mb-1 flex items-center gap-1.5 font-semibold">
                <Icon name="check" size={13} strokeWidth={3} />
                완료
              </div>
              {m.text}
            </div>
          )
        })}
      </div>

      {/* Quick actions */}
      <div className="flex flex-wrap gap-1.5 border-t border-gray-100 px-3 pt-2.5">
        {QUICK.map((q) => (
          <button
            key={q}
            onClick={() => send(q)}
            disabled={running}
            className="rounded-full border border-brand-200 bg-brand-50 px-2.5 py-1 text-[11px] font-medium text-brand-700 hover:bg-brand-100 disabled:opacity-50"
          >
            {q}
          </button>
        ))}
      </div>

      {/* Input */}
      <div className="flex items-center gap-2 px-3 py-2.5">
        <input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && send(input)}
          placeholder={running ? '실행 중…' : '에이전트에게 물어보세요…'}
          disabled={running}
          className="h-9 flex-1 rounded-lg border border-gray-200 bg-gray-50 px-3 text-[13px] outline-none focus:border-brand-400 focus:bg-white disabled:opacity-60"
        />
        <button
          onClick={() => send(input)}
          disabled={!input.trim() || running}
          className="flex h-9 w-9 items-center justify-center rounded-lg bg-brand-600 text-white hover:bg-brand-700 disabled:bg-brand-300"
        >
          <Icon name="send" size={15} />
        </button>
      </div>
    </div>
  )
}

/* ------------------------------------------------------------- sub-cards */

function ToolCard({ msg }: { msg: ToolMsg }) {
  return (
    <div className="rounded-lg border border-gray-200 bg-white">
      <div className="flex items-center gap-1.5 border-b border-gray-100 px-3 py-1.5 font-mono text-[11px] text-gray-500">
        <Icon name="zap" size={11} className="text-brand-500" />
        <span className="font-semibold text-gray-700">{msg.toolName}</span>
        <span className="text-gray-300">·</span>
        <span className="truncate text-gray-400">{msg.toolParams}</span>
      </div>
      {msg.result ? (
        <pre className="overflow-x-auto whitespace-pre px-3 py-2.5 font-mono text-[10.5px] leading-relaxed text-gray-700">
          {msg.result}
        </pre>
      ) : (
        <div className="flex items-center gap-2 px-3 py-2.5 text-[11.5px] text-gray-400">
          <Spinner size={12} />
          조회 중…
        </div>
      )}
    </div>
  )
}

function PlanCard({
  msg,
  onApprove,
  onCancel,
  disabled,
}: {
  msg: PlanMsg
  onApprove: () => void
  onCancel: () => void
  disabled: boolean
}) {
  const sc = msg.scenario
  return (
    <div className="rounded-xl border border-gray-200 bg-white">
      <div className="flex items-center gap-1.5 border-b border-gray-100 px-3 py-2 text-[12px] font-semibold text-gray-800">
        <Icon name="list" size={13} className="text-brand-500" />
        실행 계획 · {sc.title}
      </div>
      <div className="space-y-2 px-3 py-2.5">
        {sc.steps.map((s, i) => (
          <div key={i} className="flex gap-2 text-[12px]">
            <span className="flex h-4 w-4 shrink-0 items-center justify-center rounded-full bg-gray-100 text-[10px] font-semibold text-gray-500">
              {i + 1}
            </span>
            <div>
              <div className="font-medium text-gray-800">{s.label}</div>
              <div className="text-[11px] text-gray-500">{s.detail}</div>
            </div>
          </div>
        ))}
      </div>
      {sc.warning && (
        <div className="mx-3 mb-2.5 flex gap-1.5 rounded-md border border-amber-200 bg-amber-50 px-2.5 py-2 text-[11.5px] text-amber-700">
          <Icon name="alert" size={13} className="mt-0.5 shrink-0" />
          {sc.warning}
        </div>
      )}
      {msg.state === 'pending' ? (
        <div className="flex gap-2 border-t border-gray-100 px-3 py-2.5">
          <button
            onClick={onApprove}
            disabled={disabled}
            className="flex flex-1 items-center justify-center gap-1.5 rounded-md bg-brand-600 py-1.5 text-[12px] font-semibold text-white hover:bg-brand-700 disabled:bg-brand-300"
          >
            <Icon name="check" size={13} strokeWidth={3} />
            승인 후 실행
          </button>
          <button
            onClick={onCancel}
            disabled={disabled}
            className="rounded-md border border-gray-200 px-3 py-1.5 text-[12px] font-medium text-gray-600 hover:bg-gray-50"
          >
            취소
          </button>
        </div>
      ) : (
        <div
          className={cn(
            'border-t border-gray-100 px-3 py-2 text-[11.5px] font-medium',
            msg.state === 'approved' ? 'text-brand-600' : 'text-gray-400',
          )}
        >
          {msg.state === 'approved' ? '✓ 승인됨 — 실행 중' : '취소됨'}
        </div>
      )}
    </div>
  )
}

function ExecCard({ msg }: { msg: ExecMsg }) {
  const sc = msg.scenario
  return (
    <div className="rounded-xl border border-gray-200 bg-white">
      <div className="flex items-center gap-1.5 border-b border-gray-100 px-3 py-2 text-[12px] font-semibold text-gray-800">
        <Icon name="play-circle" size={13} className="text-brand-500" />
        실행 중 · {sc.title}
      </div>
      <div className="space-y-2 px-3 py-2.5">
        {sc.steps.map((s, i) => {
          const done = msg.current === -1 || i < msg.current
          const active = i === msg.current && msg.current !== -1
          return (
            <div key={i} className="flex items-center gap-2 text-[12px]">
              <span className="flex h-4 w-4 shrink-0 items-center justify-center">
                {done ? (
                  <Icon name="check" size={13} strokeWidth={3} className="text-emerald-500" />
                ) : active ? (
                  <Spinner size={13} />
                ) : (
                  <span className="h-2.5 w-2.5 rounded-full border border-gray-300" />
                )}
              </span>
              <span className={cn(done ? 'text-gray-700' : active ? 'font-medium text-brand-700' : 'text-gray-400')}>
                {s.label}
              </span>
            </div>
          )
        })}
      </div>
    </div>
  )
}
