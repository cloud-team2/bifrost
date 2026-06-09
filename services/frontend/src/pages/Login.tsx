import { useState } from 'react'
import { BrandMark } from '../components/BrandMark'
import { useApp } from '../store/AppStore'
import { DEMO_ACCOUNTS } from '../data/mock'
import { cn } from '../lib/format'

export function Login({ onRegister }: { onRegister: () => void }) {
  const app = useApp()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  async function submit() {
    if (loading) return
    setLoading(true)
    const ok = await app.login(email.trim(), password)
    setLoading(false)
    if (!ok) {
      setError('Invalid email or password. Try a demo account below.')
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-gradient-to-br from-zinc-100 via-white to-brand-50 p-6">
      <div className="w-full max-w-[400px]">
        <div className="mb-7 flex items-center justify-center gap-2.5">
          <BrandMark size={40} />
          <span className="text-[26px] font-bold lowercase tracking-tight text-gray-900">bifrost</span>
        </div>

        <div className="rounded-2xl border border-gray-200 bg-white p-7 shadow-sm">
          <h1 className="text-[17px] font-semibold text-gray-900">Sign in</h1>

          <div className="mt-5 space-y-3">
            <div>
              <label className="mb-1 block text-[12px] font-medium text-gray-600">Email</label>
              <input
                value={email}
                onChange={(e) => {
                  setEmail(e.target.value)
                  setError('')
                }}
                onKeyDown={(e) => e.key === 'Enter' && submit()}
                placeholder="you@company.com"
                className="h-10 w-full rounded-md border border-gray-300 px-3 text-sm outline-none focus:border-brand-600 focus:ring-1 focus:ring-brand-600"
              />
            </div>
            <div>
              <label className="mb-1 block text-[12px] font-medium text-gray-600">Password</label>
              <input
                type="password"
                value={password}
                onChange={(e) => {
                  setPassword(e.target.value)
                  setError('')
                }}
                onKeyDown={(e) => e.key === 'Enter' && submit()}
                placeholder="••••••••"
                className="h-10 w-full rounded-md border border-gray-300 px-3 text-sm outline-none focus:border-brand-600 focus:ring-1 focus:ring-brand-600"
              />
            </div>
          </div>

          {error && <div className="mt-3 text-[12px] font-medium text-rose-600">{error}</div>}

          <button
            onClick={submit}
            disabled={loading}
            className="mt-4 h-10 w-full rounded-md bg-brand-600 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-brand-700 disabled:bg-brand-300"
          >
            {loading ? 'Signing in…' : 'Sign In'}
          </button>

          <div className="mt-4 text-center text-[12px] text-gray-500">
            계정이 없으신가요?{' '}
            <button
              type="button"
              onClick={onRegister}
              className="font-semibold text-brand-700 transition-colors hover:text-brand-800"
            >
              회원가입
            </button>
          </div>

          <div className="mt-6 border-t border-gray-100 pt-4">
            <div className="mb-2 text-[11px] font-semibold uppercase tracking-wide text-gray-400">
              Demo accounts
            </div>
            <div className="space-y-2">
              {DEMO_ACCOUNTS.map((a) => (
                <button
                  key={a.user.email}
                  onClick={() => {
                    setEmail(a.user.email)
                    setPassword(a.password)
                    setError('')
                  }}
                  className="flex w-full items-center gap-2.5 rounded-lg border border-gray-200 px-3 py-2 text-left transition-colors hover:border-brand-300 hover:bg-brand-50/40"
                >
                  <div
                    className={cn(
                      'flex h-8 w-8 items-center justify-center rounded-full text-[12px] font-semibold text-white',
                      a.user.role === 'developer'
                        ? 'bg-gradient-to-br from-sky-400 to-indigo-500'
                        : 'bg-gradient-to-br from-violet-400 to-violet-600',
                    )}
                  >
                    {a.user.initial}
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-[13px] font-medium text-gray-800">{a.user.name}</div>
                    <div className="truncate text-[11px] text-gray-400">{a.user.email}</div>
                  </div>
                  <span
                    className={cn(
                      'rounded px-1.5 py-0.5 text-[10px] font-semibold capitalize',
                      a.user.role === 'developer'
                        ? 'bg-sky-50 text-sky-700'
                        : 'bg-violet-50 text-violet-700',
                    )}
                  >
                    {a.user.role}
                  </span>
                </button>
              ))}
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
