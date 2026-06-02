import { useMemo, useState } from 'react'
import { Modal } from '../../components/Modal'
import { Icon } from '../../components/Icon'
import { TechIcon, nodeKind } from '../../components/TechIcon'
import { StatusBadge } from '../../components/blocks'
import { useToast } from '../../components/Toast'
import { useApp } from '../../store/AppStore'
import type { EdgePattern, Node } from '../../data/types'
import { nodeName } from '../../data/helpers'
import { cn } from '../../lib/format'

const TABLE_POOL: { name: string; status: 'ok' | 'warning' | 'blocked' }[] = [
  { name: 'orders', status: 'ok' },
  { name: 'order_items', status: 'ok' },
  { name: 'customers', status: 'ok' },
  { name: 'payments', status: 'warning' },
  { name: 'audit_log', status: 'ok' },
  { name: 'legacy_events', status: 'blocked' },
]

/* ---------------------------------------------------------------- Glossary tooltip */

function GlossaryTooltip({ term, children }: { term: 'EDA' | 'CDC'; children: React.ReactNode }) {
  const [show, setShow] = useState(false)
  const content = {
    EDA: {
      title: 'EDA — Event-Driven Architecture',
      desc: 'DB 변경이 일어날 때마다 이벤트를 발행(publish)하고, 여러 서비스가 각자 구독(subscribe)하는 방식입니다. 알림 서비스, 검색 인덱서, 감사 로그 등 여러 수신자가 있을 때 적합합니다.',
      analogy: '라디오 방송 — 한 번 송출, 여러 명이 청취',
    },
    CDC: {
      title: 'CDC — Change Data Capture',
      desc: 'Source DB의 변경을 실시간으로 다른 DB에 그대로 복제합니다. 분석용 데이터마트, 읽기 전용 복제본을 만들 때 적합합니다.',
      analogy: '거울 복사 — Source DB가 바뀌면 Sink DB도 즉시 반영',
    },
  }
  const c = content[term]
  return (
    <span className="relative inline-flex" onMouseEnter={() => setShow(true)} onMouseLeave={() => setShow(false)}>
      {children}
      {show && (
        <div className="absolute bottom-full left-1/2 z-50 mb-2 w-64 -translate-x-1/2 rounded-lg border border-gray-200 bg-white p-3 shadow-xl">
          <div className="mb-1 text-[11px] font-bold text-gray-900">{c.title}</div>
          <div className="text-[11.5px] leading-relaxed text-gray-600">{c.desc}</div>
          <div className="mt-2 flex items-start gap-1.5 rounded-md bg-gray-50 px-2 py-1.5">
            <Icon name="info" size={11} className="mt-0.5 shrink-0 text-gray-400" />
            <span className="text-[10.5px] text-gray-500">{c.analogy}</span>
          </div>
          <div className="absolute left-1/2 top-full h-2 w-2 -translate-x-1/2 -translate-y-1 rotate-45 border-b border-r border-gray-200 bg-white" />
        </div>
      )}
    </span>
  )
}

/* ---------------------------------------------------------------- Modal */

export function CreatePipelineModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const app = useApp()
  const toast = useToast()
  const [step, setStep] = useState(0)
  const [pattern, setPattern] = useState<EdgePattern | null>(null)
  const [sourceId, setSourceId] = useState('')
  const [sinkId, setSinkId] = useState('')
  const [table, setTable] = useState('')
  const [name, setName] = useState('')

  const dbs = useMemo(
    () => app.nodes.filter((n) => n.type === 'database' && app.currentProject?.dbIds.includes(n.id)),
    [app.nodes, app.currentProject],
  )
  const steps = pattern === 'direct'
    ? ['연결 방식', 'Source DB', 'Sink DB', '테이블', '확인']
    : ['연결 방식', 'Source DB', '테이블', '확인']

  function reset() {
    setStep(0)
    setPattern(null)
    setSourceId('')
    setSinkId('')
    setTable('')
    setName('')
  }
  function close() {
    onClose()
    setTimeout(reset, 200)
  }

  const topic = pattern === 'direct'
    ? `cdc.${table || 'table'}.cdc`
    : `eda.${table || 'table'}.events`
  const stepKey = steps[step]

  const canNext =
    stepKey === '연결 방식'
      ? !!pattern
      : stepKey === 'Source DB'
        ? !!sourceId
        : stepKey === 'Sink DB'
          ? !!sinkId
          : stepKey === '테이블'
            ? !!table
            : !!name.trim()

  function create() {
    if (!pattern || !sourceId) return
    app.createPipeline({
      name: name.trim().toLowerCase().replace(/\s+/g, '-'),
      alias: name.trim(),
      pattern,
      source: sourceId,
      sink: pattern === 'direct' ? sinkId : null,
      consumers: pattern === 'fan-out' ? [] : undefined,
      table: { schema: 'public', name: table },
      topic,
      partitions: 3,
      metrics: { produce_rate: 0, consume_rate: 0, lag: 0, error_pct: 0 },
    })
    toast('파이프라인 생성됨 — 약 3초 후 활성화됩니다')
    close()
  }

  return (
    <Modal
      open={open}
      onClose={close}
      title="파이프라인 연결"
      subtitle="DB 데이터를 실시간으로 다른 서비스나 DB에 전달합니다"
      width={560}
      footer={
        <>
          <button
            onClick={step === 0 ? close : () => setStep((s) => s - 1)}
            className="rounded-md border border-gray-200 px-3 py-1.5 text-[13px] font-medium text-gray-600 hover:bg-gray-50"
          >
            {step === 0 ? '취소' : '이전'}
          </button>
          {step < steps.length - 1 ? (
            <button
              disabled={!canNext}
              onClick={() => setStep((s) => s + 1)}
              className="rounded-md bg-brand-600 px-4 py-1.5 text-[13px] font-semibold text-white hover:bg-brand-700 disabled:bg-brand-300"
            >
              다음
            </button>
          ) : (
            <button
              disabled={!canNext}
              onClick={create}
              className="rounded-md bg-brand-600 px-4 py-1.5 text-[13px] font-semibold text-white hover:bg-brand-700 disabled:bg-brand-300"
            >
              파이프라인 생성
            </button>
          )}
        </>
      }
    >
      {/* stepper */}
      <div className="mb-5 flex items-center gap-1.5">
        {steps.map((s, i) => (
          <div key={s} className="flex flex-1 items-center gap-1.5">
            <div
              className={cn(
                'flex h-5 w-5 shrink-0 items-center justify-center rounded-full text-[10px] font-bold',
                i < step ? 'bg-emerald-500 text-white' : i === step ? 'bg-brand-600 text-white' : 'bg-gray-200 text-gray-500',
              )}
            >
              {i < step ? '✓' : i + 1}
            </div>
            <span className={cn('text-[11px] font-medium', i === step ? 'text-gray-800' : 'text-gray-400')}>
              {s}
            </span>
            {i < steps.length - 1 && <div className="h-px flex-1 bg-gray-200" />}
          </div>
        ))}
      </div>

      {/* ── Step 1: 연결 방식 ── */}
      {stepKey === '연결 방식' && (
        <div>
          <p className="mb-3 text-[12.5px] text-gray-500">데이터를 어떻게 전달하고 싶으신가요?</p>
          <div className="grid grid-cols-2 gap-3">
            {/* EDA */}
            <button
              onClick={() => setPattern('fan-out')}
              className={cn(
                'rounded-xl border p-4 text-left transition-colors',
                pattern === 'fan-out' ? 'border-brand-500 bg-brand-50' : 'border-gray-200 hover:border-gray-300',
              )}
            >
              <div className="flex items-center gap-2">
                <Icon name="share" size={20} className="text-violet-500" />
                <GlossaryTooltip term="EDA">
                  <span className="rounded bg-violet-100 px-1.5 py-0.5 text-[9.5px] font-bold text-violet-700 cursor-help">
                    EDA ？
                  </span>
                </GlossaryTooltip>
              </div>
              <div className="mt-2.5 text-[14px] font-semibold text-gray-900">
                여러 서비스에 실시간 전달
              </div>
              <div className="mt-1 text-[12px] leading-relaxed text-gray-500">
                DB 변경이 생기면 여러 서비스가 동시에 받아볼 수 있습니다. 알림, 검색 인덱싱, 로그 수집 등에 적합합니다.
              </div>
              <div className="mt-3 flex items-center gap-1 font-mono text-[10.5px] text-gray-400">
                <span className="rounded bg-emerald-50 px-1.5 py-0.5 text-emerald-700">DB</span>
                <span>→</span>
                <span className="rounded bg-violet-50 px-1.5 py-0.5 text-violet-700">Topic</span>
                <span>→</span>
                <span className="rounded bg-gray-100 px-1.5 py-0.5 text-gray-600">서비스 A, B, C…</span>
              </div>
            </button>

            {/* CDC */}
            <button
              onClick={() => setPattern('direct')}
              className={cn(
                'rounded-xl border p-4 text-left transition-colors',
                pattern === 'direct' ? 'border-brand-500 bg-brand-50' : 'border-gray-200 hover:border-gray-300',
              )}
            >
              <div className="flex items-center gap-2">
                <Icon name="route" size={20} className="text-sky-500" />
                <GlossaryTooltip term="CDC">
                  <span className="rounded bg-sky-100 px-1.5 py-0.5 text-[9.5px] font-bold text-sky-700 cursor-help">
                    CDC ？
                  </span>
                </GlossaryTooltip>
              </div>
              <div className="mt-2.5 text-[14px] font-semibold text-gray-900">
                다른 DB에 실시간 복제
              </div>
              <div className="mt-1 text-[12px] leading-relaxed text-gray-500">
                Source DB의 변경을 다른 DB에 그대로 동기화합니다. 분석용 DB, 읽기 전용 복제본 구축에 적합합니다.
              </div>
              <div className="mt-3 flex items-center gap-1 font-mono text-[10.5px] text-gray-400">
                <span className="rounded bg-emerald-50 px-1.5 py-0.5 text-emerald-700">Source DB</span>
                <span>→→</span>
                <span className="rounded bg-sky-50 px-1.5 py-0.5 text-sky-700">Sink DB</span>
              </div>
            </button>
          </div>
        </div>
      )}

      {/* ── Step 2/3: DB 선택 ── */}
      {(stepKey === 'Source DB' || stepKey === 'Sink DB') && (
        <DbPicker
          dbs={stepKey === 'Sink DB' ? dbs.filter((d) => d.id !== sourceId) : dbs}
          selected={stepKey === 'Sink DB' ? sinkId : sourceId}
          onSelect={(id) => (stepKey === 'Sink DB' ? setSinkId(id) : setSourceId(id))}
          label={stepKey === 'Sink DB' ? '데이터를 받을 DB를 선택하세요' : '변경을 감지할 DB를 선택하세요'}
        />
      )}

      {/* ── 테이블 선택 ── */}
      {stepKey === '테이블' && (
        <div>
          <div className="mb-2 text-[12.5px] text-gray-500">변경을 감지할 테이블을 하나 선택하세요</div>
          <div className="space-y-1.5">
            {TABLE_POOL.map((t) => {
              const selected = table === t.name
              const blocked  = t.status === 'blocked'
              return (
                <label
                  key={t.name}
                  className={cn(
                    'flex w-full cursor-pointer items-center gap-3 rounded-lg border px-3 py-2.5 transition-colors',
                    blocked      ? 'cursor-not-allowed border-gray-100 bg-gray-50 opacity-50'
                    : selected   ? 'border-brand-500 bg-brand-50'
                    : 'border-gray-200 hover:border-gray-300 hover:bg-gray-50',
                  )}
                >
                  <input
                    type="radio"
                    name="table"
                    value={t.name}
                    disabled={blocked}
                    checked={selected}
                    onChange={() => setTable(t.name)}
                    className="sr-only"
                  />
                  {/* custom radio indicator */}
                  <span className={cn(
                    'flex h-4 w-4 shrink-0 items-center justify-center rounded-full border-2 transition-colors',
                    blocked   ? 'border-gray-300'
                    : selected ? 'border-brand-600 bg-brand-600'
                    : 'border-gray-300',
                  )}>
                    {selected && <span className="h-1.5 w-1.5 rounded-full bg-white" />}
                  </span>
                  <Icon name="table" size={14} className={selected ? 'text-brand-500' : 'text-gray-400'} />
                  <span className={cn(
                    'flex-1 font-mono text-[12.5px]',
                    selected ? 'font-semibold text-brand-700' : 'text-gray-800',
                  )}>
                    public.{t.name}
                  </span>
                  {t.status === 'ok'      && <StatusBadge status="healthy" label="사용 가능" />}
                  {t.status === 'warning' && <StatusBadge status="warning" label="확인 권장" />}
                  {t.status === 'blocked' && <StatusBadge status="error"   label="사용 불가" />}
                </label>
              )
            })}
          </div>
        </div>
      )}

      {/* ── 확인 ── */}
      {stepKey === '확인' && (
        <div>
          <div className="flex items-center justify-center gap-2 rounded-lg border border-gray-200 bg-gray-50 px-3 py-4">
            <FlowNode node={app.nodes.find((n) => n.id === sourceId)} caption={`[${table}]`} />
            <Icon name="arrow-right" size={16} className="text-gray-300" />
            <div className="flex flex-col items-center">
              <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-zinc-800 text-white">
                <Icon name="server" size={17} />
              </div>
              <span className="mt-1 font-mono text-[10px] text-gray-500">pipeline</span>
            </div>
            <Icon name="arrow-right" size={16} className="text-gray-300" />
            {pattern === 'direct' ? (
              <FlowNode node={app.nodes.find((n) => n.id === sinkId)} caption="복제 대상" />
            ) : (
              <div className="flex flex-col items-center">
                <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-violet-100 text-violet-600">
                  <Icon name="users" size={17} />
                </div>
                <span className="mt-1 font-mono text-[10px] text-gray-500">구독 서비스들</span>
              </div>
            )}
          </div>
          <div className="mt-4">
            <label className="mb-1 block text-[12px] font-medium text-gray-600">파이프라인 이름</label>
            <input
              autoFocus
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="예: 주문 이벤트 스트림"
              className="h-10 w-full rounded-md border border-gray-300 px-3 text-sm outline-none focus:border-brand-600 focus:ring-1 focus:ring-brand-600"
            />
          </div>
        </div>
      )}
    </Modal>
  )
}

function DbPicker({
  dbs,
  selected,
  onSelect,
  label,
}: {
  dbs: Node[]
  selected: string
  onSelect: (id: string) => void
  label: string
}) {
  return (
    <div>
      <div className="mb-2 text-[12.5px] text-gray-500">{label}</div>
      <div className="space-y-1.5">
        {dbs.map((d) => (
          <button
            key={d.id}
            onClick={() => onSelect(d.id)}
            className={cn(
              'flex w-full items-center gap-2.5 rounded-lg border px-3 py-2 text-left transition-colors',
              selected === d.id ? 'border-brand-500 bg-brand-50' : 'border-gray-200 hover:border-gray-300',
            )}
          >
            <TechIcon kind={nodeKind(d)} size={32} />
            <div className="min-w-0 flex-1">
              <div className="truncate text-[13px] font-medium text-gray-800">{nodeName(d)}</div>
              <div className="truncate font-mono text-[11px] text-gray-400">{d.host}</div>
            </div>
            <span className="rounded bg-gray-100 px-1.5 py-0.5 text-[10px] font-medium text-gray-500">
              {d.tech}
            </span>
          </button>
        ))}
        {dbs.length === 0 && (
          <div className="rounded-lg border border-dashed border-gray-200 px-3 py-6 text-center text-[12.5px] text-gray-400">
            등록된 데이터베이스가 없습니다. 먼저 DB를 등록해주세요.
          </div>
        )}
      </div>
    </div>
  )
}

function FlowNode({ node, caption }: { node?: Node; caption: string }) {
  return (
    <div className="flex flex-col items-center">
      {node ? <TechIcon kind={nodeKind(node)} size={36} /> : <div className="h-9 w-9 rounded-lg bg-gray-200" />}
      <span className="mt-1 max-w-[80px] truncate font-mono text-[10px] text-gray-500">{caption}</span>
    </div>
  )
}
