import { useEffect, useRef, useState } from 'react'
import { Icon } from '../../components/Icon'
import { Spinner } from '../../components/ui'
import { useToast } from '../../components/Toast'
import { useApp, type AgentTask } from '../../store/AppStore'
import { cn } from '../../lib/format'

interface Step {
  label: string
  detail: string
}
interface Scenario {
  match: RegExp
  title: string
  intro: string
  steps: Step[]
  warning?: string
  summary: string
}

const SCENARIOS: Scenario[] = [
  {
    match: /(rebalance|partition|리밸런|파티션)/i,
    title: '브로커 파티션 리밸런싱',
    intro:
      'broker-1이 리더 파티션 142개를 보유하며 CPU 78%를 유지하고 있습니다. broker-2와 broker-3로 리더십을 재분배할 수 있습니다.',
    steps: [
      { label: '파티션 분포 분석', detail: '3개 브로커의 리더/레플리카 분산 상태 스캔' },
      { label: '리밸런싱 계획 수립', detail: 'broker-1에서 리더 파티션 9개 이전' },
      { label: 'Preferred 리더 선출 실행', detail: '스로틀링을 적용하여 새 할당 적용' },
      { label: '결과 검증', detail: 'CPU 65% 미만 및 under-replicated 파티션 없음 확인' },
    ],
    warning: '리더 선출 중 해당 파티션의 produce가 잠시 중단됩니다 (~2초).',
    summary: '리밸런싱 완료. broker-1 CPU가 58%로 감소했고, 리더십이 균등하게 분산되었습니다 (138 / 137 / 137).',
  },
  {
    match: /(connector|커넥터|restart|재시작|users-connector)/i,
    title: '커넥터 재시작',
    intro:
      'audit-source 커넥터가 Task 1개 실패로 DEGRADED 상태입니다. 클린 재시작으로 복구할 수 있습니다.',
    steps: [
      { label: '커넥터 상태 확인', detail: 'Task 상태 및 마지막 오류 점검' },
      { label: '커넥터 일시정지', detail: '처리 중인 레코드를 안전하게 비우기' },
      { label: 'Task 재시작', detail: '새 Task 2개 기동' },
      { label: '30초간 모니터링', detail: 'RUNNING 상태 및 records/sec 회복 확인' },
    ],
    summary: 'audit-source가 다시 RUNNING 상태입니다. 두 Task 모두 정상이며, 처리량이 980 records/sec로 회복되었습니다.',
  },
  {
    match: /(lag|지연|fraud|detector|분석|consumer)/i,
    title: 'fraud-detector 지연 분석',
    intro:
      'cg-fraud-detector의 lag이 18,400건이며 그룹이 REBALANCING 루프에 빠져 있습니다. 데이터를 수집하여 해결 방안을 제안하겠습니다.',
    steps: [
      { label: 'Lag 메트릭 수집', detail: '최근 30분간 파티션별 lag 조회' },
      { label: '리밸런싱 이력 점검', detail: 'Pod 재시작과 리밸런싱의 상관관계 분석' },
      { label: '권장안 생성', detail: '스케일 아웃과 probe 타임아웃 옵션 비교' },
    ],
    summary:
      '근본 원인: liveness probe 실패로 약 40초마다 Pod 1개가 재시작되고 있습니다. 권장 조치: probe 타임아웃을 30초로 상향한 뒤 5 인스턴스로 스케일 아웃하세요. 해당 계획을 작성해 드릴까요?',
  },
]

interface PlanMsg {
  id: number
  kind: 'plan'
  scenario: Scenario
  state: 'pending' | 'approved' | 'cancelled'
}
interface ExecMsg {
  id: number
  kind: 'exec'
  scenario: Scenario
  current: number // -1 = done
}
interface TextMsg {
  id: number
  kind: 'text'
  role: 'user' | 'assistant'
  text: string
}
interface DoneMsg {
  id: number
  kind: 'done'
  text: string
}
type Msg = PlanMsg | ExecMsg | TextMsg | DoneMsg

const QUICK = ['브로커 파티션 리밸런싱', '커넥터 재시작', 'fraud-detector 지연 분석']

const RISK_LABEL: Record<AgentTask['risk'], string> = { low: '낮은', medium: '중간', high: '높은' }

export function OperatorAgentPanel() {
  const app = useApp()
  const toast = useToast()
  const [msgs, setMsgs] = useState<Msg[]>([
    { id: 1, kind: 'text', role: 'assistant', text: '운영 AI 에이전트입니다. 모든 조치는 HITL 모드로 동작합니다 — 계획을 먼저 제시하고 승인 후에만 실행합니다. Connector 재시작·파티션 리밸런싱·Consumer lag 해소를 지원합니다. 브로커 직접 조작과 토픽 삭제는 수행하지 않습니다.' },
  ])
  const [input, setInput] = useState('')
  const [running, setRunning] = useState(false)
  const seq = useRef(1)
  const scroll = useRef<HTMLDivElement>(null)
  const taskHandled = useRef(false)

  useEffect(() => {
    scroll.current?.scrollTo({ top: scroll.current.scrollHeight, behavior: 'smooth' })
  }, [msgs])

  // The Incidents tab "Run" button hands off a task — auto-start its verification plan.
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
          { id: ++seq.current, kind: 'text', role: 'assistant', text: '브로커 파티션 리밸런싱, 커넥터 재시작, 컨슈머 지연 분석을 도와드릴 수 있습니다. 아래 빠른 작업 중 하나를 선택해 보세요.' },
        ])
        return
      }
      setMsgs((m) => [
        ...m,
        { id: ++seq.current, kind: 'text', role: 'assistant', text: sc.intro },
        { id: ++seq.current, kind: 'plan', scenario: sc, state: 'pending' },
      ])
    }, 650)
  }

  /** Step through an execution card one verification step at a time. */
  function runSteps(execId: number, sc: Scenario, onComplete?: () => void) {
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

  function approve(planId: number, sc: Scenario) {
    setMsgs((m) => m.map((x) => (x.id === planId && x.kind === 'plan' ? { ...x, state: 'approved' } : x)))
    const execId = ++seq.current
    setMsgs((m) => [...m, { id: execId, kind: 'exec', scenario: sc, current: 0 }])
    runSteps(execId, sc, () => toast(`${sc.title} — 완료`))
  }

  /** Build a verification plan for an incident action handed off from the Incidents tab. */
  function buildTaskScenario(task: AgentTask): Scenario {
    return {
      match: /(?!)/,
      title: task.label,
      intro: `인시던트 '${task.incidentTitle}'의 권장 조치를 실행합니다. ${task.detail}`,
      steps: [
        { label: '인시던트 컨텍스트 수집', detail: `'${task.incidentTitle}' 관련 메트릭과 리소스 상태 조회` },
        { label: '사전 조건 검증', detail: `${RISK_LABEL[task.risk]} 리스크 조치의 안전 조건 확인 · 예상 ${task.estimatedTime}` },
        { label: '조치 실행', detail: task.label },
        { label: '사후 검증', detail: '조치 효과 측정 및 시스템 안정성 재확인' },
      ],
      warning: task.risk === 'low' ? undefined : '이 조치는 운영 트래픽에 일시적인 영향을 줄 수 있습니다.',
      summary: `'${task.label}' 조치를 완료했습니다. 사후 검증 결과 시스템이 안정 상태입니다. 추가 조치가 필요하면 아래에서 이어서 요청하세요.`,
    }
  }

  /** Open with an incident action: post intro + plan, then run verification automatically. */
  function startTask(task: AgentTask) {
    const sc = buildTaskScenario(task)
    const introId = ++seq.current
    const planId = ++seq.current
    const execId = ++seq.current
    setMsgs((m) => [
      ...m,
      { id: introId, kind: 'text', role: 'assistant', text: sc.intro },
      { id: planId, kind: 'plan', scenario: sc, state: 'approved' },
      { id: execId, kind: 'exec', scenario: sc, current: 0 },
    ])
    runSteps(execId, sc, () => {
      app.runIncidentAction(task.incidentId, task.actionId)
      toast(`${task.label} — 완료`)
    })
  }

  function cancel(planId: number) {
    setMsgs((m) => m.map((x) => (x.id === planId && x.kind === 'plan' ? { ...x, state: 'cancelled' } : x)))
  }

  return (
    <div className="flex flex-1 flex-col overflow-hidden">
      <div className="flex items-center gap-2 border-b border-gray-200 px-4 py-3">
        <div className="flex h-7 w-7 items-center justify-center rounded-md bg-gradient-to-br from-violet-400 to-violet-600 text-white">
          <Icon name="shield" size={15} />
        </div>
        <div className="flex-1">
          <div className="text-[13px] font-semibold text-gray-900">운영 AI 에이전트</div>
          <div className="text-[11px] text-amber-600">HITL · 실행 전 승인 필요</div>
        </div>
      </div>

      <div ref={scroll} className="flex-1 space-y-3 overflow-y-auto scroll-thin bg-gray-50 px-4 py-4">
        {msgs.map((m) => {
          if (m.kind === 'text')
            return (
              <div key={m.id} className={cn('flex', m.role === 'user' ? 'justify-end' : 'justify-start')}>
                <div
                  className={cn(
                    'max-w-[85%] rounded-xl px-3 py-2 text-[12.5px] leading-relaxed',
                    m.role === 'user'
                      ? 'rounded-br-sm bg-brand-600 text-white'
                      : 'rounded-bl-sm border border-gray-200 bg-white text-gray-700',
                  )}
                >
                  {m.text}
                </div>
              </div>
            )
          if (m.kind === 'plan') return <PlanCard key={m.id} msg={m} onApprove={() => approve(m.id, m.scenario)} onCancel={() => cancel(m.id)} disabled={running} />
          if (m.kind === 'exec') return <ExecCard key={m.id} msg={m} />
          return (
            <div key={m.id} className="rounded-xl border border-emerald-200 bg-emerald-50 px-3 py-2.5 text-[12.5px] leading-relaxed text-emerald-800">
              <div className="mb-1 flex items-center gap-1.5 font-semibold">
                <Icon name="check" size={13} strokeWidth={3} />
                실행 완료
              </div>
              {m.text}
            </div>
          )
        })}
      </div>

      <div className="flex flex-wrap gap-1.5 border-t border-gray-100 px-3 pt-2.5">
        {QUICK.map((q) => (
          <button
            key={q}
            onClick={() => send(q)}
            disabled={running}
            className="rounded-full border border-violet-200 bg-violet-50 px-2.5 py-1 text-[11px] font-medium text-violet-700 hover:bg-violet-100 disabled:opacity-50"
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
          placeholder={running ? '실행 중…' : '작업을 설명하세요…'}
          disabled={running}
          className="h-9 flex-1 rounded-lg border border-gray-200 bg-gray-50 px-3 text-[13px] outline-none focus:border-brand-400 focus:bg-white disabled:opacity-60"
        />
        <button
          onClick={() => send(input)}
          disabled={!input.trim() || running}
          className="flex h-9 w-9 items-center justify-center rounded-lg bg-violet-600 text-white hover:bg-violet-700 disabled:bg-violet-300"
        >
          <Icon name="send" size={15} />
        </button>
      </div>
    </div>
  )
}

function PlanCard({ msg, onApprove, onCancel, disabled }: { msg: PlanMsg; onApprove: () => void; onCancel: () => void; disabled: boolean }) {
  const sc = msg.scenario
  return (
    <div className="rounded-xl border border-gray-200 bg-white">
      <div className="flex items-center gap-1.5 border-b border-gray-100 px-3 py-2 text-[12px] font-semibold text-gray-800">
        <Icon name="list" size={13} className="text-violet-500" />
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
            className="flex flex-1 items-center justify-center gap-1.5 rounded-md bg-violet-600 py-1.5 text-[12px] font-semibold text-white hover:bg-violet-700 disabled:bg-violet-300"
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
        <div className={cn('border-t border-gray-100 px-3 py-2 text-[11.5px] font-medium', msg.state === 'approved' ? 'text-violet-600' : 'text-gray-400')}>
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
        <Icon name="play-circle" size={13} className="text-violet-500" />
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
              <span className={cn(done ? 'text-gray-700' : active ? 'font-medium text-violet-700' : 'text-gray-400')}>
                {s.label}
              </span>
            </div>
          )
        })}
      </div>
    </div>
  )
}
