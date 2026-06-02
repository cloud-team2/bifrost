import { useState } from 'react'
import { Modal } from '../../components/Modal'
import { Icon } from '../../components/Icon'
import { TechIcon } from '../../components/TechIcon'
import { useToast } from '../../components/Toast'
import { useApp } from '../../store/AppStore'
import type { CapabilityCheck, DbTech } from '../../data/types'
import { cn } from '../../lib/format'

function buildChecks(tech: DbTech): CapabilityCheck[] {
  if (tech === 'postgres')
    return [
      { label: 'wal_level = logical', state: 'pass', detail: 'Logical decoding is enabled.' },
      { label: 'REPLICATION privilege', state: 'pass', detail: 'The user can create replication slots.' },
      { label: 'Replication slot capacity', state: 'pass', detail: '2 of 10 slots in use.' },
      { label: 'REPLICA IDENTITY FULL', state: 'warn', detail: '3 tables still use DEFAULT — updates ship keys only.' },
    ]
  return [
    { label: 'binlog_format = ROW', state: 'pass', detail: 'Row-based binary logging is enabled.' },
    { label: 'REPLICATION privilege', state: 'pass', detail: 'The user has REPLICATION SLAVE.' },
    { label: 'GTID mode', state: 'warn', detail: 'GTID is not enabled — failover repositioning will be manual.' },
    { label: 'binlog retention', state: 'pass', detail: 'Retention is 7 days.' },
  ]
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
  const [tested, setTested] = useState(false)

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
    setTested(false)
  }
  function close() {
    onClose()
    setTimeout(reset, 200)
  }

  const canNext = step === 0 ? !!tech : step === 1 ? !!(alias && host && database && user) : true

  function register() {
    if (!tech) return
    app.addDatabase({
      label: database || alias.toLowerCase().replace(/\s+/g, '-'),
      alias,
      tech,
      techLabel: `${tech} ${tech === 'postgres' ? '16.0' : '11.2'}`,
      host: `${host}:${port}`,
      schema: { tables: 0, rows: '0', size: '0 MB' },
      cdc: {
        wal_level: tech === 'postgres' ? 'logical' : 'binlog/ROW',
        replication: 'granted',
        slots: tech === 'postgres' ? '0 / 10' : '—',
        wal_senders: tech === 'postgres' ? '0 / 10' : '0',
      },
      metrics: { tps: 0, lag_ms: 0 },
      checks: buildChecks(tech),
    })
    toast(`Database "${alias}" registered`)
    close()
  }

  return (
    <Modal
      open={open}
      onClose={close}
      title="Register a Database"
      subtitle="Connect a PostgreSQL or MariaDB instance to this project"
      width={500}
      footer={
        <>
          <button
            onClick={step === 0 ? close : () => setStep((s) => s - 1)}
            className="rounded-md border border-gray-200 px-3 py-1.5 text-[13px] font-medium text-gray-600 hover:bg-gray-50"
          >
            {step === 0 ? 'Cancel' : 'Back'}
          </button>
          {step < 2 ? (
            <button
              disabled={!canNext}
              onClick={() => setStep((s) => s + 1)}
              className="rounded-md bg-brand-600 px-4 py-1.5 text-[13px] font-semibold text-white hover:bg-brand-700 disabled:bg-brand-300"
            >
              Next
            </button>
          ) : (
            <button
              onClick={register}
              className="rounded-md bg-brand-600 px-4 py-1.5 text-[13px] font-semibold text-white hover:bg-brand-700"
            >
              Register Database
            </button>
          )}
        </>
      }
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
            onClick={() => setTested(true)}
            className="rounded-md border border-gray-300 px-3 py-1.5 text-[12.5px] font-medium text-gray-700 hover:bg-gray-50"
          >
            Test Connection
          </button>
          {tested && (
            <div className="flex items-center gap-1.5 text-[12.5px] font-medium text-emerald-600">
              <Icon name="check" size={14} strokeWidth={3} />
              Connection successful — server reachable
            </div>
          )}
        </div>
      )}

      {step === 2 && tech && (
        <div className="space-y-4">
          <CheckGroup title="Source role — CDC readiness" checks={buildChecks(tech)} />
          <CheckGroup
            title="Sink role — write access"
            checks={[
              { label: 'CREATE / INSERT privilege', state: 'pass', detail: 'The user can create and write to tables.' },
              { label: 'Schema write access', state: 'pass', detail: 'public schema is writable.' },
            ]}
          />
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
