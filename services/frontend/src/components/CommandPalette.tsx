import { useEffect, useRef, useState } from 'react'
import { Icon, type IconName } from './Icon'
import { cn } from '../lib/format'
import type { SlashToolCommand } from '../lib/slashCommands'

export interface CommandParamOption {
  value: string
  label: string
}

/**
 * 그룹형 명령 팔레트(#599 후속) — 평면 슬래시 목록을 도메인 그룹 → 기능 목록 → 바로 실행
 * 흐름으로 바꾼다. 실행은 기존 슬래시 인프라(runSlashCommand→executeAgentTool)를 재사용한다.
 * 파이프라인 생성만 도구가 아니라 기존 CreatePipelineModal을 연다.
 */

export interface CommandGroupDef {
  key: string
  icon: IconName
  name: string
  desc: string
}

const GROUPS: CommandGroupDef[] = [
  { key: 'pipeline', icon: 'share', name: '파이프라인', desc: '목록·토폴로지·배포 이력·생성' },
  { key: 'cluster', icon: 'server', name: '클러스터', desc: '커넥터 상태·컨슈머 lag·트레이스' },
  { key: 'incident', icon: 'alert', name: '인시던트', desc: '알림·요약·로그·지표' },
]

// 파라미터명 → 한국어 라벨(1-step 입력).
const PARAM_LABELS: Record<string, string> = {
  pipeline_id: '파이프라인 ID',
  connector_name: '커넥터명',
  consumer_group: '컨슈머 그룹',
  incident_id: '인시던트 ID',
  query: '검색어',
  metric: '메트릭',
}
const paramLabel = (p: string) => PARAM_LABELS[p] ?? p

// 파이프라인 생성 — 도구가 아니라 모달을 여는 합성 항목.
const CREATE_PIPELINE = '__create_pipeline__'

interface CommandPaletteProps {
  commands: SlashToolCommand[]
  loading: boolean
  error: string | null
  onRunTool: (command: SlashToolCommand, args: string[]) => void
  onCreatePipeline: () => void
  onClose: () => void
  // 파라미터(예: connector_name, pipeline_id) 드롭다운 옵션을 채운다.
  // null이면 자유 텍스트 입력으로 떨어진다.
  loadOptions?: (param: string) => Promise<CommandParamOption[]> | null
}

type View =
  | { kind: 'groups' }
  | { kind: 'funcs'; group: string }
  | { kind: 'params'; group: string; command: SlashToolCommand }

export function CommandPalette({
  commands,
  loading,
  error,
  onRunTool,
  onCreatePipeline,
  onClose,
  loadOptions,
}: CommandPaletteProps) {
  const [view, setView] = useState<View>({ kind: 'groups' })

  const grouped = (key: string) => commands.filter((c) => c.group === key)

  function pick(group: string, command: SlashToolCommand) {
    if (command.argParams.length > 0) {
      setView({ kind: 'params', group, command })
      return
    }
    onRunTool(command, [])
    onClose()
  }

  return (
    <div className="rounded-lg border border-gray-200 bg-white text-[12px] shadow-lg">
      <Header view={view} onBack={setView} onClose={onClose} />
      {loading ? (
        <div className="px-3 py-4 text-gray-500">기능 목록을 불러오는 중…</div>
      ) : error ? (
        <div className="break-words px-3 py-4 text-[#c0392b]">{error}</div>
      ) : view.kind === 'groups' ? (
        <GroupGrid onSelect={(g) => setView({ kind: 'funcs', group: g })} />
      ) : view.kind === 'funcs' ? (
        <FuncList
          group={view.group}
          commands={grouped(view.group)}
          onPick={pick}
          onCreatePipeline={() => {
            onCreatePipeline()
            onClose()
          }}
        />
      ) : (
        <ParamStep
          command={view.command}
          loadOptions={loadOptions}
          onRun={(args) => {
            onRunTool(view.command, args)
            onClose()
          }}
        />
      )}
    </div>
  )
}

function Header({
  view,
  onBack,
  onClose,
}: {
  view: View
  onBack: (v: View) => void
  onClose: () => void
}) {
  const title =
    view.kind === 'groups'
      ? '기능 그룹을 선택하세요'
      : view.kind === 'funcs'
        ? GROUPS.find((g) => g.key === view.group)?.name ?? ''
        : view.command.labelKo || view.command.description
  return (
    <div className="flex items-center gap-2 border-b border-gray-100 px-3 py-2 text-[11px] text-gray-400">
      {view.kind !== 'groups' && (
        <button
          type="button"
          onClick={() =>
            onBack(view.kind === 'params' ? { kind: 'funcs', group: view.group } : { kind: 'groups' })
          }
          className="flex items-center gap-0.5 font-semibold text-gray-700 hover:text-gray-900"
        >
          <Icon name="chevron-left" size={13} />
          뒤로
        </button>
      )}
      <span className="truncate">{title}</span>
      <button
        type="button"
        onClick={onClose}
        className="ml-auto text-gray-400 hover:text-gray-700"
        aria-label="명령 팔레트 닫기"
      >
        <Icon name="x" size={13} />
      </button>
    </div>
  )
}

function GroupGrid({ onSelect }: { onSelect: (group: string) => void }) {
  return (
    <div className="grid grid-cols-3 gap-2 p-2">
      {GROUPS.map((g) => (
        <button
          key={g.key}
          type="button"
          onClick={() => onSelect(g.key)}
          className="flex flex-col gap-1.5 rounded-lg border border-gray-200 p-3 text-left hover:border-gray-400 hover:bg-gray-50"
        >
          <span className="flex h-7 w-7 items-center justify-center rounded-md bg-gray-100 text-gray-700">
            <Icon name={g.icon} size={15} />
          </span>
          <span className="text-[13px] font-semibold text-gray-900">{g.name}</span>
          <span className="text-[11px] text-gray-400">{g.desc}</span>
        </button>
      ))}
    </div>
  )
}

function FuncList({
  group,
  commands,
  onPick,
  onCreatePipeline,
}: {
  group: string
  commands: SlashToolCommand[]
  onPick: (group: string, command: SlashToolCommand) => void
  onCreatePipeline: () => void
}) {
  return (
    <div className="max-h-72 overflow-y-auto p-1">
      {commands.map((c) => (
        <FuncRow
          key={c.toolName}
          label={c.labelKo || c.description}
          desc={c.description}
          onClick={() => onPick(group, c)}
        />
      ))}
      {group === 'pipeline' && (
        <FuncRow
          label="파이프라인 생성"
          desc="새 CDC/EDA 파이프라인을 생성합니다."
          action
          onClick={onCreatePipeline}
        />
      )}
      {commands.length === 0 && group !== 'pipeline' && (
        <div className="px-3 py-4 text-gray-400">사용 가능한 기능이 없습니다.</div>
      )}
    </div>
  )
}

function FuncRow({
  label,
  desc,
  action,
  onClick,
}: {
  label: string
  desc: string
  action?: boolean
  onClick: () => void
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="flex w-full items-center gap-2.5 rounded-md px-2 py-2 text-left hover:bg-gray-50"
    >
      <span className="shrink-0 rounded-md bg-gray-900 px-2.5 py-1 text-[12.5px] font-semibold text-white">
        {label}
      </span>
      <span className="min-w-0 flex-1 break-words text-[11.5px] leading-snug text-gray-500">{desc}</span>
      {action && (
        <span className="shrink-0 rounded-md border border-amber-200 bg-amber-50 px-1.5 py-0.5 text-[10px] font-semibold text-amber-600">
          액션
        </span>
      )}
    </button>
  )
}

type OptionState = CommandParamOption[] | null | 'loading'

function ParamStep({
  command,
  loadOptions,
  onRun,
}: {
  command: SlashToolCommand
  loadOptions?: (param: string) => Promise<CommandParamOption[]> | null
  onRun: (args: string[]) => void
}) {
  const [values, setValues] = useState<string[]>(() => command.argParams.map(() => ''))
  const [options, setOptions] = useState<Record<string, OptionState>>({})
  const ready = command.argParams.every((_, i) => values[i]?.trim())
  const setValue = (i: number, v: string) =>
    setValues((prev) => prev.map((cur, idx) => (idx === i ? v : cur)))

  useEffect(() => {
    let cancelled = false
    command.argParams.forEach((param) => {
      const pending = loadOptions?.(param)
      if (!pending) {
        setOptions((o) => ({ ...o, [param]: null }))
        return
      }
      setOptions((o) => ({ ...o, [param]: 'loading' }))
      pending
        .then((opts) => {
          if (!cancelled) setOptions((o) => ({ ...o, [param]: opts.length ? opts : null }))
        })
        .catch(() => {
          if (!cancelled) setOptions((o) => ({ ...o, [param]: null }))
        })
    })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [command])

  return (
    <div className="space-y-2 p-3">
      {command.argParams.map((param, i) => {
        const state = options[param]
        return (
          <div key={param}>
            <label className="mb-1 block text-[11px] text-gray-500">{paramLabel(param)}</label>
            {state === 'loading' ? (
              <div className="flex h-9 items-center rounded-lg border border-gray-200 bg-gray-50 px-3 text-[13px] text-gray-400">
                불러오는 중…
              </div>
            ) : Array.isArray(state) ? (
              <CustomSelect
                options={state}
                value={values[i]}
                placeholder={`${paramLabel(param)} 선택`}
                onChange={(v) => setValue(i, v)}
              />
            ) : (
              <input
                autoFocus={i === 0}
                value={values[i]}
                onChange={(e) => setValue(i, e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && ready) onRun(values.map((v) => v.trim()))
                }}
                placeholder={paramLabel(param)}
                className="h-9 w-full rounded-lg border border-gray-200 bg-white px-3 text-[13px] text-gray-800 outline-none focus:border-gray-400"
              />
            )}
          </div>
        )
      })}
      <button
        type="button"
        disabled={!ready}
        onClick={() => onRun(values.map((v) => v.trim()))}
        className={cn(
          'mt-1 h-9 w-full rounded-lg text-[13px] font-medium text-white',
          ready ? 'bg-gray-900 hover:bg-gray-800' : 'bg-gray-300',
        )}
      >
        바로 실행
      </button>
    </div>
  )
}

/** 테마 맞춤 커스텀 드롭다운 — 네이티브 select 팝업(파란 하이라이트)을 피하려 div 기반. */
function CustomSelect({
  options,
  value,
  placeholder,
  onChange,
}: {
  options: CommandParamOption[]
  value: string
  placeholder: string
  onChange: (value: string) => void
}) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  const selected = options.find((o) => o.value === value)

  useEffect(() => {
    if (!open) return
    const onDoc = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [open])

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        className={cn(
          'flex h-9 w-full items-center justify-between gap-2 rounded-lg border bg-white px-3 text-[13px] outline-none',
          open ? 'border-gray-400' : 'border-gray-200 hover:border-gray-300',
        )}
      >
        <span className={cn('truncate', selected ? 'text-gray-800' : 'text-gray-400')}>
          {selected ? selected.label : placeholder}
        </span>
        <Icon name="chevron-down" size={14} className="shrink-0 text-gray-400" />
      </button>
      {open && (
        <div className="absolute left-0 right-0 top-10 z-30 max-h-44 overflow-y-auto rounded-lg border border-gray-200 bg-white p-1 shadow-lg">
          {options.map((o) => {
            const sel = o.value === value
            return (
              <button
                key={o.value}
                type="button"
                onClick={() => {
                  onChange(o.value)
                  setOpen(false)
                }}
                className={cn(
                  'flex w-full items-center gap-1.5 rounded-md px-2 py-1.5 text-left text-[13px] hover:bg-gray-50',
                  sel ? 'font-semibold text-gray-900' : 'text-gray-700',
                )}
              >
                <span className="flex w-3.5 shrink-0 justify-center text-gray-900">
                  {sel && <Icon name="check" size={13} strokeWidth={3} />}
                </span>
                <span className="truncate">{o.label}</span>
              </button>
            )
          })}
        </div>
      )}
    </div>
  )
}
