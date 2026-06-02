import { useEffect, useRef, useState } from 'react'
import { Icon } from '../../components/Icon'
import { useApp } from '../../store/AppStore'
import { pipelineLabel } from '../../data/helpers'
import { cn } from '../../lib/format'

interface ToolCall {
  name: string
  params: string
  result: string
}
interface Msg {
  id: number
  role: 'user' | 'assistant'
  text?: string
  tool?: ToolCall
}

const SUGGESTIONS = ['파이프라인 목록 보기', '데이터베이스 상태 확인', '이벤트 로그 분석']

export function DevAIChatPanel({ viewLabel }: { viewLabel: string }) {
  const app = useApp()
  const [msgs, setMsgs] = useState<Msg[]>([
    { id: 1, role: 'assistant', text: '안녕하세요 — Bifrost AI입니다. 파이프라인 조회·Pause/Resume, DB 상태 확인, 이벤트 로그 분석을 도와드립니다. Kafka·Connector 직접 조작은 운영 에이전트 영역입니다.' },
  ])
  const [input, setInput] = useState('')
  const [busy, setBusy] = useState(false)
  const seq = useRef(1)
  const scroll = useRef<HTMLDivElement>(null)

  useEffect(() => {
    scroll.current?.scrollTo({ top: scroll.current.scrollHeight, behavior: 'smooth' })
  }, [msgs, busy])

  function reply(text: string): Msg[] {
    const t = text.toLowerCase()
    const edges = app.edges.filter((e) => app.currentProject?.pipelineIds.includes(e.id))
    const id1 = ++seq.current
    const id2 = ++seq.current

    if (/(list|show|목록).*pipe|pipe.*(list|show)|파이프/.test(t)) {
      return [
        { id: id1, role: 'assistant', text: `이 워크스페이스에는 ${edges.length}개의 파이프라인이 있습니다:` },
        {
          id: id2,
          role: 'assistant',
          tool: {
            name: 'list_pipelines',
            params: `workspace="${app.currentProject?.slug}"`,
            result: edges.map((e) => `${pipelineLabel(e)} — ${e.status}`).join('\n') || '파이프라인 없음',
          },
        },
      ]
    }
    if (/(pause|일시정지|멈)/.test(t)) {
      const target = edges.find((e) => e.status === 'active' || e.status === 'lag')
      return [
        { id: id1, role: 'assistant', text: target ? `"${pipelineLabel(target)}" 파이프라인을 일시정지할 수 있습니다. 수집은 멈추지만 Kafka 토픽과 오프셋은 유지되므로 나중에 안전하게 재개할 수 있어요.` : '일시정지할 활성 파이프라인이 없습니다.' },
        ...(target ? [{ id: id2, role: 'assistant' as const, tool: { name: 'pause_pipeline', params: `id="${target.id}"`, result: '준비됨 — 적용하려면 확인하세요' } }] : []),
      ]
    }
    if (/(resume|재개|다시)/.test(t)) {
      return [
        { id: id1, role: 'assistant', text: '파이프라인을 재개하면 마지막으로 커밋된 오프셋부터 수집을 다시 시작합니다 — 데이터 손실은 없습니다.' },
        { id: id2, role: 'assistant', tool: { name: 'resume_pipeline', params: 'status="paused"', result: '준비됨 — 적용하려면 확인하세요' } },
      ]
    }
    if (/(delete|삭제|제거)/.test(t)) {
      return [
        { id: id1, role: 'assistant', text: '파이프라인을 삭제하면 커넥터가 제거됩니다. Kafka 토픽은 기본적으로 보존되므로 다운스트림 컨슈머는 데이터를 유지합니다.' },
        { id: id2, role: 'assistant', tool: { name: 'delete_pipeline', params: 'preserve_topic=true', result: '준비됨 — 적용하려면 확인하세요' } },
      ]
    }
    if (/(create|생성|만들|new)/.test(t)) {
      return [
        { id: id1, role: 'assistant', text: '파이프라인을 생성해 드릴 수 있습니다. 소스 DB와 테이블을 선택하시면 토픽 이름을 생성하고 활성화합니다 (~3초).' },
        { id: id2, role: 'assistant', tool: { name: 'create_pipeline', params: 'pattern="fan-out"', result: 'topic = <project>.<table>.events' } },
      ]
    }
    if (/(db|database|데이터베이스).*(status|상태|health)|status.*db/.test(t)) {
      const dbs = app.nodes.filter((n) => n.type === 'database' && app.currentProject?.dbIds.includes(n.id))
      return [
        { id: id1, role: 'assistant', text: '이 워크스페이스의 현재 데이터베이스 상태입니다:' },
        { id: id2, role: 'assistant', tool: { name: 'check_db_status', params: `count=${dbs.length}`, result: dbs.map((d) => `${d.alias ?? d.label} — ${d.status} (lag ${d.metrics?.lag_ms ?? 0}ms)`).join('\n') } },
      ]
    }
    if (/(log|analyze|분석|이벤트|incident)/.test(t)) {
      return [
        { id: id1, role: 'assistant', text: '최근 이벤트 로그를 분석했습니다. 가장 큰 이슈는 cg-audit 컨슈머 그룹이 반복적으로 REBALANCING 상태에 진입하면서 audit-stream 파이프라인이 약 7,400건 지연되고 있다는 점입니다. 원인은 session.timeout.ms가 배치 처리 시간보다 낮게 설정된 것으로 보입니다.' },
        { id: id2, role: 'assistant', tool: { name: 'analyze_activity_log', params: 'window="2h"', result: '경고 3건 · 오류 1건 · 근본 원인: 컨슈머 세션 타임아웃' } },
      ]
    }
    return [
      { id: id1, role: 'assistant', text: `${viewLabel}에서 파이프라인과 데이터베이스 작업을 도와드릴 수 있습니다. 예: 파이프라인 목록 보기, 데이터베이스 상태 확인, 이벤트 로그 분석.` },
    ]
  }

  function send(text: string) {
    const v = text.trim()
    if (!v || busy) return
    setMsgs((m) => [...m, { id: ++seq.current, role: 'user', text: v }])
    setInput('')
    setBusy(true)
    setTimeout(() => {
      setMsgs((m) => [...m, ...reply(v)])
      setBusy(false)
    }, 750)
  }

  return (
    <div className="flex flex-1 flex-col overflow-hidden">
      <div className="flex items-center gap-2 border-b border-gray-200 px-4 py-3">
        <div className="flex h-7 w-7 items-center justify-center rounded-md bg-gradient-to-br from-brand-400 to-brand-600 text-white">
          <Icon name="zap" size={15} />
        </div>
        <div className="flex-1">
          <div className="text-[13px] font-semibold text-gray-900">Bifrost AI</div>
          <div className="text-[11px] text-gray-400">컨텍스트: {viewLabel}</div>
        </div>
      </div>

      <div ref={scroll} className="flex-1 space-y-3 overflow-y-auto scroll-thin bg-gray-50 px-4 py-4">
        {msgs.map((m) =>
          m.tool ? (
            <ToolCard key={m.id} tool={m.tool} />
          ) : (
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
          ),
        )}
        {busy && (
          <div className="flex gap-1 rounded-xl rounded-bl-sm border border-gray-200 bg-white px-3 py-3 w-fit">
            {[0, 1, 2].map((i) => (
              <span
                key={i}
                className="h-1.5 w-1.5 animate-bounce rounded-full bg-gray-400"
                style={{ animationDelay: `${i * 0.15}s` }}
              />
            ))}
          </div>
        )}
      </div>

      <div className="flex flex-wrap gap-1.5 border-t border-gray-100 px-3 pt-2.5">
        {SUGGESTIONS.map((s) => (
          <button
            key={s}
            onClick={() => send(s)}
            className="rounded-full border border-brand-200 bg-brand-50 px-2.5 py-1 text-[11px] font-medium text-brand-700 hover:bg-brand-100"
          >
            {s}
          </button>
        ))}
      </div>
      <div className="flex items-center gap-2 px-3 py-2.5">
        <input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && send(input)}
          placeholder="Bifrost AI에게 물어보세요…"
          className="h-9 flex-1 rounded-lg border border-gray-200 bg-gray-50 px-3 text-[13px] outline-none focus:border-brand-400 focus:bg-white"
        />
        <button
          onClick={() => send(input)}
          disabled={!input.trim() || busy}
          className="flex h-9 w-9 items-center justify-center rounded-lg bg-brand-600 text-white hover:bg-brand-700 disabled:bg-brand-300"
        >
          <Icon name="send" size={15} />
        </button>
      </div>
    </div>
  )
}

function ToolCard({ tool }: { tool: ToolCall }) {
  return (
    <div className="rounded-lg border border-gray-200 bg-white">
      <div className="flex items-center gap-1.5 border-b border-gray-100 px-3 py-1.5 font-mono text-[11px] text-gray-500">
        <Icon name="zap" size={11} className="text-brand-500" />
        {tool.name}
        <span className="text-gray-300">·</span>
        <span className="truncate text-gray-400">{tool.params}</span>
      </div>
      <pre className="overflow-x-auto whitespace-pre-wrap px-3 py-2 font-mono text-[11px] leading-relaxed text-gray-700">
        {tool.result}
      </pre>
    </div>
  )
}
