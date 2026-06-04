import { useState } from 'react'
import { Modal } from '../../components/Modal'
import { Icon } from '../../components/Icon'
import { TechIcon } from '../../components/TechIcon'
import { useToast } from '../../components/Toast'
import { useApp } from '../../store/AppStore'
import type { CapabilityCheck, DbTech } from '../../data/types'
import { cn } from '../../lib/format'
import { ApiError, api, type CdcStatus } from '../../lib/api'
import { cdcChecksToCapability, datasourceToNode, techToEngine } from '../../lib/mappers'

const READINESS_LABEL: Record<CdcStatus, string> = {
  OK: 'CDC 준비 완료 — 소스로 사용 가능',
  WARNING: '일부 경고 — 소스로 사용 가능(주의)',
  BLOCKED: '차단됨 — 소스로 선택 불가',
}

export function AddDatabaseModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const app = useApp()
  const toast = useToast()
  const [step, setStep] = useState(0)
  const [tech, setTech] = useState<DbTech | null>(null)
  const [alias, setAlias] = useState('')
  const [host, setHost] = useState('')
  const [port, setPort] = useState('5432')
  const [database, setDatabase] = useState('')
  const [user, setUser] = useState('')
  const [password, setPassword] = useState('')

  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState<{ success: boolean; message: string } | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [checks, setChecks] = useState<CapabilityCheck[]>([])
  const [overall, setOverall] = useState<CdcStatus | null>(null)

  const steps = ['Type', 'Connection', 'Capability']

  function reset() {
    setStep(0)
    setTech(null)
    setAlias('')
    setHost('')
    setPort('5432')
    setDatabase('')
    setUser('')
    setPassword('')
    setTesting(false)
    setTestResult(null)
    setBusy(false)
    setError('')
    setChecks([])
    setOverall(null)
  }
  function close() {
    onClose()
    setTimeout(reset, 200)
  }

  const wsId = app.currentProject?.id ?? null
  const canNext = step === 0 ? !!tech : !!(alias && host && database && user)

  async function testConnection() {
    if (!tech || testing) return
    setTesting(true)
    setTestResult(null)
    setError('')
    try {
      const res = await api.testConnection(wsId!, {
        engine: techToEngine(tech),
        host,
        port: Number(port),
        dbName: database,
        user,
        password,
      })
      setTestResult({ success: res.success, message: res.message })
    } catch (e) {
      setTestResult({ success: false, message: e instanceof ApiError ? e.message : '연결 테스트 실패' })
    } finally {
      setTesting(false)
    }
  }

  /** 등록 + CDC 준비도 점검(시나리오 2.2). 성공 시 Capability 단계로 진행. */
  async function registerAndCheck() {
    if (!tech || !wsId || busy) return
    setBusy(true)
    setError('')
    try {
      const db = await api.registerDatabase(wsId, {
        name: alias,
        engine: techToEngine(tech),
        host,
        port: Number(port),
        dbName: database,
        username: user,
        password,
      })
      app.addDatabaseNode(datasourceToNode(db, app.nodes.length))
      try {
        const readiness = await api.cdcReadiness(wsId, db.id)
        setChecks(cdcChecksToCapability(readiness))
        setOverall(readiness.overallStatus)
      } catch {
        setChecks([])
        setOverall(null)
      }
      setStep(2)
      toast(`Database "${db.name}" registered`)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '데이터베이스 등록에 실패했습니다')
    } finally {
      setBusy(false)
    }
  }

  function footer() {
    if (step === 2) {
      return (
        <button
          onClick={close}
          className="rounded-md bg-brand-600 px-4 py-1.5 text-[13px] font-semibold text-white hover:bg-brand-700"
        >
          Done
        </button>
      )
    }
    return (
      <>
        <button
          onClick={step === 0 ? close : () => setStep((s) => s - 1)}
          className="rounded-md border border-gray-200 px-3 py-1.5 text-[13px] font-medium text-gray-600 hover:bg-gray-50"
        >
          {step === 0 ? 'Cancel' : 'Back'}
        </button>
        {step === 0 ? (
          <button
            disabled={!canNext}
            onClick={() => setStep(1)}
            className="rounded-md bg-brand-600 px-4 py-1.5 text-[13px] font-semibold text-white hover:bg-brand-700 disabled:bg-brand-300"
          >
            Next
          </button>
        ) : (
          <button
            disabled={!canNext || busy}
            onClick={registerAndCheck}
            className="rounded-md bg-brand-600 px-4 py-1.5 text-[13px] font-semibold text-white hover:bg-brand-700 disabled:bg-brand-300"
          >
            {busy ? 'Registering…' : 'Register & Check'}
          </button>
        )}
      </>
    )
  }

  return (
    <Modal
      open={open}
      onClose={close}
      title="Register a Database"
      subtitle="Connect a PostgreSQL or MariaDB instance to this project"
      width={500}
      footer={footer()}
    >
      <div className="mb-4 flex items-center gap-1.5">
        {steps.map((s, i) => (
          <div key={s} className="flex flex-1 items-center gap-1.5">
            <div
              className={cn(
                'flex h-5 w-5 items-center justify-center rounded-full text-[10px] font-bold',
                i < step ? 'bg-emerald-500 text-white' : i === step ? 'bg-brand-600 text-white' : 'bg-gray-200 text-gray-500',
              )}
            >
              {i < step ? '✓' : i + 1}
            </div>
            <span className={cn('text-[11px] font-medium', i === step ? 'text-gray-800' : 'text-gray-400')}>{s}</span>
            {i < steps.length - 1 && <div className="h-px flex-1 bg-gray-200" />}
          </div>
        ))}
      </div>

      {step === 0 && (
        <div className="grid grid-cols-2 gap-3">
          {(['postgres', 'mariadb'] as DbTech[]).map((t) => (
            <button
              key={t}
              onClick={() => {
                setTech(t)
                setPort(t === 'postgres' ? '5432' : '3306')
              }}
              className={cn(
                'flex flex-col items-center gap-2 rounded-xl border p-5 transition-colors',
                tech === t ? 'border-brand-500 bg-brand-50' : 'border-gray-200 hover:border-gray-300',
              )}
            >
              <TechIcon kind={t} size={48} />
              <span className="text-[13px] font-semibold capitalize text-gray-800">
                {t === 'postgres' ? 'PostgreSQL' : 'MariaDB'}
              </span>
            </button>
          ))}
        </div>
      )}

      {step === 1 && (
        <div className="space-y-3">
          <Field label="Display name" value={alias} onChange={setAlias} placeholder="Orders Service DB" />
          <div className="grid grid-cols-[1fr_100px] gap-3">
            <Field label="Host" value={host} onChange={setHost} placeholder="10.20.0.11" />
            <Field label="Port" value={port} onChange={setPort} />
          </div>
          <Field label="Database" value={database} onChange={setDatabase} placeholder="orders" />
          <div className="grid grid-cols-2 gap-3">
            <Field label="User" value={user} onChange={setUser} placeholder="bifrost" />
            <Field label="Password" value={password} onChange={setPassword} type="password" />
          </div>
          <button
            onClick={testConnection}
            disabled={testing || !(host && database && user)}
            className="rounded-md border border-gray-300 px-3 py-1.5 text-[12.5px] font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
          >
            {testing ? 'Testing…' : 'Test Connection'}
          </button>
          {testResult && (
            <div
              className={cn(
                'flex items-center gap-1.5 text-[12.5px] font-medium',
                testResult.success ? 'text-emerald-600' : 'text-rose-600',
              )}
            >
              <Icon name={testResult.success ? 'check' : 'x'} size={14} strokeWidth={3} />
              {testResult.message}
            </div>
          )}
          {error && (
            <div className="flex items-center gap-1.5 text-[12.5px] font-medium text-rose-600">
              <Icon name="x" size={14} strokeWidth={3} />
              {error}
            </div>
          )}
        </div>
      )}

      {step === 2 && (
        <div className="space-y-4">
          {overall && (
            <div
              className={cn(
                'rounded-lg border px-3 py-2 text-[12.5px] font-semibold',
                overall === 'OK'
                  ? 'border-emerald-200 bg-emerald-50 text-emerald-700'
                  : overall === 'WARNING'
                    ? 'border-amber-200 bg-amber-50 text-amber-700'
                    : 'border-rose-200 bg-rose-50 text-rose-700',
              )}
            >
              {READINESS_LABEL[overall]}
            </div>
          )}
          {checks.length > 0 ? (
            <CheckGroup title="CDC readiness" checks={checks} />
          ) : (
            <div className="text-[12.5px] text-gray-500">CDC 준비도 정보를 가져오지 못했습니다.</div>
          )}
        </div>
      )}
    </Modal>
  )
}

function Field({
  label,
  value,
  onChange,
  placeholder,
  type = 'text',
}: {
  label: string
  value: string
  onChange: (v: string) => void
  placeholder?: string
  type?: string
}) {
  return (
    <div>
      <label className="mb-1 block text-[12px] font-medium text-gray-600">{label}</label>
      <input
        type={type}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        className="h-9 w-full rounded-md border border-gray-300 px-3 text-[13px] outline-none focus:border-brand-600 focus:ring-1 focus:ring-brand-600"
      />
    </div>
  )
}

function CheckGroup({ title, checks }: { title: string; checks: CapabilityCheck[] }) {
  return (
    <div className="rounded-lg border border-gray-200">
      <div className="border-b border-gray-100 px-3 py-2 text-[12px] font-semibold text-gray-700">{title}</div>
      <div className="divide-y divide-gray-50">
        {checks.map((c) => (
          <div key={c.label} className="flex gap-2.5 px-3 py-2">
            <Icon
              name={c.state === 'pass' ? 'check' : c.state === 'warn' ? 'alert' : 'x'}
              size={14}
              strokeWidth={c.state === 'pass' ? 3 : 2}
              className={cn(
                'mt-0.5 shrink-0',
                c.state === 'pass' ? 'text-emerald-500' : c.state === 'warn' ? 'text-amber-500' : 'text-rose-500',
              )}
            />
            <div>
              <div className="text-[12.5px] font-medium text-gray-800">{c.label}</div>
              <div className="text-[11.5px] text-gray-500">{c.detail}</div>
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}
