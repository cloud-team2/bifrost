import { useState, type FormEvent, type InputHTMLAttributes } from 'react'
import { BrandMark } from '../components/BrandMark'
import { useToast } from '../components/Toast'
import { ApiError } from '../lib/api'
import { cn } from '../lib/format'
import { useApp } from '../store/AppStore'

type RegisterForm = {
  email: string
  name: string
  password: string
  workspaceName: string
  namespace: string
}

type RegisterField = keyof RegisterForm
type FieldErrors = Partial<Record<RegisterField, string>>

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const NAMESPACE_RE = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/
const REGISTER_FIELDS: RegisterField[] = ['email', 'name', 'password', 'workspaceName', 'namespace']
const CONFLICT_FIELD_BY_CODE: Record<string, RegisterField> = {
  '10001': 'email',
  'EMAIL_ALREADY_USED': 'email',
  '20001': 'workspaceName',
  'WORKSPACE_NAME_CONFLICT': 'workspaceName',
  '20002': 'namespace',
  'WORKSPACE_NAMESPACE_CONFLICT': 'namespace',
}

const initialForm: RegisterForm = {
  email: '',
  name: '',
  password: '',
  workspaceName: '',
  namespace: '',
}

export function Register({ onSignIn }: { onSignIn: () => void }) {
  const app = useApp()
  const toast = useToast()
  const [form, setForm] = useState<RegisterForm>(initialForm)
  const [errors, setErrors] = useState<FieldErrors>({})
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const update = (field: RegisterField, value: string) => {
    setForm((prev) => ({ ...prev, [field]: value }))
    setErrors((prev) => {
      const next = { ...prev }
      delete next[field]
      return next
    })
    setError('')
  }

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    if (loading) return

    const nextErrors = validate(form)
    setErrors(nextErrors)
    if (Object.keys(nextErrors).length > 0) {
      setError('입력값을 확인해주세요.')
      return
    }

    setLoading(true)
    setError('')
    try {
      await app.register({
        email: form.email.trim(),
        name: form.name.trim() || null,
        password: form.password,
        workspaceName: form.workspaceName.trim(),
        namespace: form.namespace.trim(),
      })
      toast('회원가입이 완료되었습니다')
    } catch (e) {
      const message = e instanceof ApiError ? apiErrorMessage(e) : '회원가입에 실패했습니다'
      if (e instanceof ApiError) setErrors(apiFieldErrors(e))
      setError(message)
      toast(message, 'error')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-gradient-to-br from-zinc-100 via-white to-[#ededed] p-6">
      <div className="w-full max-w-[440px]">
        <div className="mb-7 flex items-center justify-center gap-2.5">
          <BrandMark size={40} />
          <span className="text-[26px] font-bold lowercase tracking-tight text-gray-900">bifrost</span>
        </div>

        <form onSubmit={submit} className="rounded-2xl border border-gray-200 bg-white p-7 shadow-sm">
          <h1 className="text-[17px] font-semibold text-gray-900">Sign up</h1>
          <p className="mt-1 text-[12px] text-gray-500">
            새 계정과 첫 워크스페이스를 생성합니다.
          </p>

          <div className="mt-5 space-y-3">
            <Field
              label="Email"
              value={form.email}
              onChange={(e) => update('email', e.target.value)}
              placeholder="you@company.com"
              error={errors.email}
            />
            <Field
              label="Name"
              value={form.name}
              onChange={(e) => update('name', e.target.value)}
              placeholder="Your name"
              error={errors.name}
              hint="비워두면 이메일 앞부분을 이름으로 사용합니다."
            />
            <Field
              label="Password"
              type="password"
              value={form.password}
              onChange={(e) => update('password', e.target.value)}
              placeholder="8자 이상"
              error={errors.password}
            />
            <Field
              label="Workspace name"
              value={form.workspaceName}
              onChange={(e) => update('workspaceName', e.target.value)}
              placeholder="Team workspace"
              error={errors.workspaceName}
            />
            <Field
              label="Namespace"
              value={form.namespace}
              onChange={(e) => update('namespace', e.target.value)}
              placeholder="team-workspace"
              error={errors.namespace}
              hint="3~63자, 소문자/숫자/하이픈만 가능하며 처음과 끝은 영숫자여야 합니다."
            />
          </div>

          {error && <div className="mt-3 text-[12px] font-medium text-[#c0392b]">{error}</div>}

          <button
            type="submit"
            disabled={loading}
            className="mt-4 h-10 w-full rounded-md bg-brand-600 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-brand-700 disabled:bg-brand-300"
          >
            {loading ? 'Signing up…' : 'Create account'}
          </button>

          <div className="mt-4 text-center text-[12px] text-gray-500">
            이미 계정이 있으신가요?{' '}
            <button
              type="button"
              onClick={onSignIn}
              className="font-semibold text-brand-700 transition-colors hover:text-brand-800"
            >
              Sign in
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

function validate(form: RegisterForm): FieldErrors {
  const next: FieldErrors = {}
  const email = form.email.trim()
  const name = form.name.trim()
  const workspaceName = form.workspaceName.trim()
  const namespace = form.namespace.trim()

  if (!email) next.email = '이메일을 입력해주세요.'
  else if (email.length > 255 || !EMAIL_RE.test(email)) next.email = '올바른 이메일을 입력해주세요.'

  if (name.length > 100) next.name = '이름은 100자 이하로 입력해주세요.'

  if (!form.password) next.password = '비밀번호를 입력해주세요.'
  else if (form.password.length < 8) next.password = '비밀번호는 8자 이상이어야 합니다.'
  else if (form.password.length > 128) next.password = '비밀번호는 128자 이하로 입력해주세요.'

  if (!workspaceName) next.workspaceName = '워크스페이스 이름을 입력해주세요.'
  else if (workspaceName.length < 2) next.workspaceName = '워크스페이스 이름은 2자 이상이어야 합니다.'
  else if (workspaceName.length > 100) next.workspaceName = '워크스페이스 이름은 100자 이하로 입력해주세요.'

  if (!namespace) next.namespace = 'namespace를 입력해주세요.'
  else if (namespace.length < 3) next.namespace = 'namespace는 3자 이상이어야 합니다.'
  else if (namespace.length > 63) next.namespace = 'namespace는 63자 이하로 입력해주세요.'
  else if (!NAMESPACE_RE.test(namespace)) {
    next.namespace = 'namespace는 소문자/숫자/하이픈만 가능하며 처음과 끝은 영숫자여야 합니다.'
  }

  return next
}

function apiErrorMessage(e: ApiError): string {
  return e.code ? `[${e.code}] ${e.message}` : e.message
}

function apiFieldErrors(e: ApiError): FieldErrors {
  const next: FieldErrors = {}
  e.details.forEach((detail) => {
    if (isRegisterField(detail.field)) next[detail.field] = detail.reason
  })
  const conflictField = CONFLICT_FIELD_BY_CODE[e.code]
  if (conflictField && !next[conflictField]) next[conflictField] = e.message
  return next
}

function isRegisterField(field: string): field is RegisterField {
  return REGISTER_FIELDS.includes(field as RegisterField)
}

function Field({
  label,
  error,
  hint,
  className,
  ...inputProps
}: InputHTMLAttributes<HTMLInputElement> & { label: string; error?: string; hint?: string }) {
  return (
    <div>
      <label className="mb-1 block text-[12px] font-medium text-gray-600">{label}</label>
      <input
        className={cn(
          'h-10 w-full rounded-md border px-3 text-sm outline-none focus:ring-1',
          error
            ? 'border-[#c0392b] focus:border-[#c0392b] focus:ring-[#c0392b]'
            : 'border-gray-300 focus:border-brand-600 focus:ring-brand-600',
          className,
        )}
        {...inputProps}
      />
      {error ? (
        <div className="mt-1 text-[11px] font-medium text-[#c0392b]">{error}</div>
      ) : hint ? (
        <div className="mt-1 text-[11px] text-gray-400">{hint}</div>
      ) : null}
    </div>
  )
}
