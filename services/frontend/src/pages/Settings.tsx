import { Fragment, useEffect, useState } from 'react'
import { Icon, type IconName } from '../components/Icon'
import { Panel, StatusBadge } from '../components/blocks'
import { Switch } from '../components/ui'
import { useToast } from '../components/Toast'
import { useApp } from '../store/AppStore'
import {
  api,
  ApiError,
  type AiPolicySettingsResponse,
  type KafkaPrincipalResponse,
  type KafkaPrincipalSecretResponse,
  type NotificationSettingsResponse,
  type NotificationSeverityPolicy,
  type ProjectMemberResponse,
  type ThresholdSettingsResponse,
  type WorkspaceMemberRole,
} from '../lib/api'
import { cn } from '../lib/format'

const SECTIONS: { id: string; label: string; icon: IconName }[] = [
  { id: 'account', label: '내 계정', icon: 'users' },
  { id: 'general', label: '일반', icon: 'settings' },
  { id: 'members', label: '멤버', icon: 'users' },
  { id: 'notifications', label: '알림', icon: 'bell' },
  { id: 'thresholds', label: '임계값', icon: 'alert' },
  { id: 'ai', label: 'AI 자동복구', icon: 'zap' },
  { id: 'kafka-users', label: 'Kafka 사용자', icon: 'key' },
]

function accountDate(value: string | null): string {
  return value ? value.slice(0, 10) : '—'
}

function accountDateTime(value: string | null): string {
  return value ? value.replace('T', ' ').slice(0, 16) : '—'
}

export function Settings() {
  const [section, setSection] = useState('account')
  return (
    <div className="flex min-h-full">
      <nav className="w-56 shrink-0 border-r border-gray-200 bg-white p-3">
        <div className="px-2 pb-2 text-[15px] font-semibold text-gray-900">설정</div>
        {SECTIONS.map((s) => (
          <button
            key={s.id}
            onClick={() => setSection(s.id)}
            className={cn(
              'flex w-full items-center gap-2.5 rounded-lg px-3 py-2 text-[13px] transition-colors',
              section === s.id ? 'bg-gray-100 font-semibold text-gray-900' : 'text-gray-500 hover:bg-gray-50',
            )}
          >
            <Icon name={s.icon} size={15} className={section === s.id ? 'text-gray-700' : 'text-gray-400'} />
            {s.label}
          </button>
        ))}
      </nav>
      <div className="flex-1 px-8 py-6">
        <div className="mx-auto max-w-[720px]">
          {section === 'account' && <AccountSection />}
          {section === 'general' && <GeneralSection />}
          {section === 'members' && <MembersSection />}
          {section === 'notifications' && <NotificationsSection />}
          {section === 'thresholds' && <ThresholdsSection />}
          {section === 'ai' && <AiSection />}
          {section === 'kafka-users' && <KafkaUsersSection />}
        </div>
      </div>
    </div>
  )
}

function Head({ title, sub }: { title: string; sub: string }) {
  return (
    <div className="mb-4">
      <h1 className="text-[18px] font-semibold text-gray-900">{title}</h1>
      <p className="text-[13px] text-gray-500">{sub}</p>
    </div>
  )
}

function AccountSection() {
  const app = useApp()
  const u = app.currentUser!
  return (
    <div>
      <Head title="내 계정" sub="개인 프로필 및 세션 정보" />
      <Panel>
        <div className="flex items-center gap-3 border-b border-gray-100 px-5 py-4">
          <div
            className={cn(
              'flex h-12 w-12 items-center justify-center rounded-full text-[16px] font-semibold text-white',
              u.role === 'MEMBER'
                ? 'bg-[#0d0d0d]'
                : 'bg-[#0d0d0d]',
            )}
          >
            {u.initial}
          </div>
          <div>
            <div className="text-[15px] font-semibold text-gray-900">{u.name}</div>
            <div className="text-[12.5px] text-gray-500">{u.role}</div>
          </div>
        </div>
        <dl className="divide-y divide-gray-50">
          <Field label="이름" value={u.name} />
          <Field label="이메일" value={u.email} />
          <Field label="역할" value={u.role} />
          <Field label="가입일" value={accountDate(u.joinedAt)} />
          <Field label="마지막 로그인" value={accountDateTime(u.lastLoginAt)} />
        </dl>
      </Panel>
      <button
        onClick={app.logout}
        className="mt-4 flex items-center gap-1.5 rounded-md border border-[#c0392b] px-3 py-2 text-[13px] font-medium text-[#c0392b] hover:bg-[#fcf3f2]"
      >
        <Icon name="logout" size={14} />
        로그아웃
      </button>
    </div>
  )
}

function Field({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="flex items-center px-5 py-3 text-[13px]">
      <dt className="w-40 text-gray-500">{label}</dt>
      <dd className="font-medium text-gray-800">{value}</dd>
    </div>
  )
}

const TZ_OPTIONS = ['Asia/Seoul', 'Asia/Singapore', 'UTC', 'US/Eastern']

function GeneralSection() {
  const app = useApp()
  const toast = useToast()
  const wsId = app.currentProject?.id
  const [name, setName] = useState(app.currentProject?.name ?? '')
  const [timezone, setTimezone] = useState('')
  const [saving, setSaving] = useState(false)
  // #954 워크스페이스 삭제(danger zone) — 이름 확인 후 삭제. OWNER/ADMIN 만.
  const [confirmName, setConfirmName] = useState('')
  const [deleting, setDeleting] = useState(false)
  const canDelete = app.currentUser?.role === 'OWNER' || app.currentUser?.role === 'ADMIN'

  // 진입 시 워크스페이스 현재 설정(timezone) 로드 (#145)
  useEffect(() => {
    if (!wsId) return
    let alive = true
    api
      .getWorkspace(wsId)
      .then((w) => {
        if (!alive) return
        setName(w.name)
        setTimezone(w.timezone ?? '')
      })
      .catch(() => {})
    return () => {
      alive = false
    }
  }, [wsId])

  async function save() {
    if (!wsId) return
    setSaving(true)
    try {
      await api.updateWorkspace(wsId, { name: name.trim() || undefined, timezone: timezone || null })
      toast('프로젝트 설정을 저장했습니다')
    } catch (e) {
      toast(e instanceof ApiError ? `저장 실패: ${e.message}` : '설정 저장에 실패했습니다', 'error')
    } finally {
      setSaving(false)
    }
  }

  async function handleDelete() {
    if (!wsId) return
    setDeleting(true)
    try {
      await api.deleteWorkspace(wsId)
      toast('워크스페이스를 삭제했습니다')
      // 현재 워크스페이스가 사라졌으므로 프로젝트 목록·선택을 깨끗이 재초기화한다.
      setTimeout(() => window.location.reload(), 700)
    } catch (e) {
      toast(e instanceof ApiError ? `삭제 실패: ${e.message}` : '워크스페이스 삭제에 실패했습니다', 'error')
      setDeleting(false)
    }
  }

  // 로드된 timezone이 옵션에 없으면 함께 노출
  const tzOptions = Array.from(new Set([timezone, ...TZ_OPTIONS].filter(Boolean)))
  const projectName = app.currentProject?.name ?? ''

  return (
    <div>
      <Head title="일반" sub="프로젝트 단위 설정" />
      <Panel>
        <div className="space-y-4 px-5 py-4">
          <Labeled label="프로젝트 이름">
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="h-9 w-full rounded-md border border-gray-300 px-3 text-[13px] outline-none focus:border-brand-600"
            />
          </Labeled>
          <Labeled label="슬러그 (읽기 전용)">
            <input
              readOnly
              value={app.currentProject?.slug ?? 'project'}
              className="h-9 w-full rounded-md border border-gray-200 bg-gray-50 px-3 font-mono text-[13px] text-gray-500"
            />
          </Labeled>
          <Labeled label="시간대">
            <select
              value={timezone}
              onChange={(e) => setTimezone(e.target.value)}
              className="h-9 w-full rounded-md border border-gray-300 px-3 text-[13px] outline-none focus:border-brand-600"
            >
              <option value="">(미설정)</option>
              {tzOptions.map((t) => (
                <option key={t} value={t}>
                  {t}
                </option>
              ))}
            </select>
          </Labeled>
          <div className="text-[12px] text-gray-400">생성일 {app.currentProject?.createdAt}</div>
        </div>
        <div className="flex justify-end border-t border-gray-100 px-5 py-3">
          <button
            onClick={save}
            disabled={saving || !wsId}
            className="rounded-md bg-brand-600 px-3.5 py-1.5 text-[13px] font-semibold text-white hover:bg-brand-700 disabled:bg-brand-300"
          >
            저장
          </button>
        </div>
      </Panel>

      {canDelete && (
        <Panel className="mt-4 border-[#e7c3bd]">
          <div className="space-y-3 px-5 py-4">
            <div className="text-[13px] font-semibold text-[#c0392b]">위험 구역</div>
            <p className="text-[12.5px] leading-relaxed text-gray-500">
              이 워크스페이스(프로젝트)를 영구 삭제합니다. 등록된 DB·네임스페이스 등 외부 리소스까지 제거되며{' '}
              <b className="text-gray-700">되돌릴 수 없습니다</b>. 파이프라인이 남아 있으면 삭제할 수 없으니 먼저 파이프라인을 삭제하세요.
            </p>
            <Labeled label={`확인을 위해 프로젝트 이름 "${projectName}" 을(를) 입력하세요`}>
              <input
                value={confirmName}
                onChange={(e) => setConfirmName(e.target.value)}
                placeholder={projectName}
                className="h-9 w-full rounded-md border border-gray-300 px-3 text-[13px] outline-none focus:border-[#c0392b]"
              />
            </Labeled>
            <div className="flex justify-end">
              <button
                onClick={handleDelete}
                disabled={deleting || !wsId || confirmName.trim() !== projectName.trim() || !projectName}
                className="rounded-md bg-[#c0392b] px-3.5 py-1.5 text-[13px] font-semibold text-white hover:bg-[#a93226] disabled:bg-[#e0a9a2]"
              >
                {deleting ? '삭제 중…' : '워크스페이스 삭제'}
              </button>
            </div>
          </div>
        </Panel>
      )}
    </div>
  )
}

function Labeled({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <label className="mb-1 block text-[12px] font-medium text-gray-600">{label}</label>
      {children}
    </div>
  )
}

const MEMBER_ROLE_LABEL: Record<WorkspaceMemberRole, string> = {
  OWNER: '소유자',
  ADMIN: '관리자',
  MEMBER: '멤버',
}

function MembersSection() {
  const app = useApp()
  const toast = useToast()
  const wsId = app.currentProject?.id
  const myEmail = app.currentUser?.email ?? ''

  const [members, setMembers] = useState<ProjectMemberResponse[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [email, setEmail] = useState('')
  const [role, setRole] = useState<WorkspaceMemberRole>('MEMBER')
  const [busy, setBusy] = useState(false)

  async function reload(ws: string) {
    setLoading(true)
    setLoadError(null)
    try {
      setMembers(await api.listMembers(ws))
    } catch (e) {
      setMembers([])
      setLoadError(e instanceof ApiError ? e.message : '멤버 목록을 불러오지 못했습니다')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    if (!wsId) {
      setMembers([])
      setLoadError(null)
      setEmail('')
      setRole('MEMBER')
      setBusy(false)
      setLoading(false)
      return
    }
    let alive = true
    setLoading(true)
    setLoadError(null)
    api
      .listMembers(wsId)
      .then((data) => {
        if (alive) setMembers(data)
      })
      .catch((e) => {
        if (!alive) return
        setMembers([])
        setLoadError(e instanceof ApiError ? e.message : '멤버 목록을 불러오지 못했습니다')
      })
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
  }, [wsId])

  // 현재 사용자의 워크스페이스 역할로 관리 권한 판별 (OWNER/ADMIN만 변경·제거·초대)
  const myRole = members.find((m) => m.email === myEmail)?.role
  const canManage = !!wsId && (myRole === 'OWNER' || myRole === 'ADMIN')

  async function invite() {
    if (!wsId) return
    setBusy(true)
    try {
      await api.addMember(wsId, email.trim(), role)
      toast(`${email.trim()} 님을 추가했습니다`)
      setEmail('')
      await reload(wsId)
    } catch (e) {
      let msg = '초대에 실패했습니다'
      if (e instanceof ApiError) {
        msg =
          e.code === 'USER_NOT_FOUND_BY_EMAIL'
            ? '가입된 사용자가 아닙니다 (먼저 회원가입 필요)'
            : e.code === 'MEMBER_ALREADY_EXISTS'
              ? '이미 멤버입니다'
              : e.message
      }
      toast(msg, 'error')
    } finally {
      setBusy(false)
    }
  }

  async function changeRole(userId: string, next: WorkspaceMemberRole) {
    if (!wsId) return
    try {
      await api.updateMemberRole(wsId, userId, next)
      await reload(wsId)
    } catch (e) {
      toast(e instanceof ApiError ? e.message : '역할 변경에 실패했습니다', 'error')
    }
  }

  async function remove(userId: string) {
    if (!wsId) return
    try {
      await api.removeMember(wsId, userId)
      toast('멤버를 제거했습니다', 'info')
      await reload(wsId)
    } catch (e) {
      toast(e instanceof ApiError ? e.message : '멤버 제거에 실패했습니다', 'error')
    }
  }

  return (
    <div>
      <Head title="멤버" sub="이 프로젝트에 접근할 수 있는 사용자" />
      <Panel title="팀">
        {!wsId ? (
          <div className="px-4 py-10 text-center text-[13px] text-gray-400">워크스페이스를 먼저 선택하세요.</div>
        ) : loading ? (
          <div className="px-4 py-6 text-center text-[12.5px] text-gray-400">불러오는 중…</div>
        ) : loadError ? (
          <div className="px-4 py-6 text-center text-[12.5px] text-[#c0392b]">{loadError}</div>
        ) : (
          <table className="w-full text-[12.5px]">
            <tbody>
              {members.map((m) => {
                const editable = canManage && m.role !== 'OWNER'
                return (
                  <tr key={m.userId} className="border-b border-gray-50 last:border-0">
                    <td className="px-4 py-2.5">
                      <div className="font-medium text-gray-800">{m.email?.split('@')[0] ?? m.userId}</div>
                      <div className="text-[11px] text-gray-400">{m.email ?? '(이메일 없음)'}</div>
                    </td>
                    <td className="px-4 py-2.5">
                      {editable ? (
                        <select
                          value={m.role}
                          onChange={(e) => changeRole(m.userId, e.target.value as WorkspaceMemberRole)}
                          className="rounded-md border border-gray-200 px-2 py-1 text-[12px] outline-none"
                        >
                          <option value="ADMIN">관리자</option>
                          <option value="MEMBER">멤버</option>
                        </select>
                      ) : (
                        <span className="text-gray-600">{MEMBER_ROLE_LABEL[m.role]}</span>
                      )}
                    </td>
                    <td className="px-4 py-2.5 text-gray-400">{m.joinedAt?.slice(0, 10)}</td>
                    <td className="px-4 py-2.5 text-right">
                      {editable && (
                        <button
                          onClick={() => remove(m.userId)}
                          className="text-[11.5px] font-medium text-[#c0392b] hover:underline"
                        >
                          제거
                        </button>
                      )}
                    </td>
                  </tr>
                )
              })}
              {members.length === 0 && (
                <tr>
                  <td colSpan={4} className="px-4 py-6 text-center text-[12.5px] text-gray-400">
                    멤버가 없습니다
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        )}
        {wsId && canManage && (
          <div className="flex items-center gap-2 border-t border-gray-100 px-4 py-3">
            <input
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="teammate@company.com"
              className="h-9 flex-1 rounded-md border border-gray-300 px-3 text-[13px] outline-none focus:border-brand-600"
            />
            <select
              value={role}
              onChange={(e) => setRole(e.target.value as WorkspaceMemberRole)}
              className="h-9 rounded-md border border-gray-300 px-2 text-[13px] outline-none"
            >
              <option value="ADMIN">관리자</option>
              <option value="MEMBER">멤버</option>
            </select>
            <button
              disabled={!email.includes('@') || busy}
              onClick={invite}
              className="rounded-md bg-brand-600 px-3 py-2 text-[13px] font-semibold text-white hover:bg-brand-700 disabled:bg-brand-300"
            >
              초대
            </button>
          </div>
        )}
      </Panel>
      {wsId && !loading && !loadError && !canManage && (
        <p className="mt-2 px-1 text-[11.5px] text-gray-400">
          멤버 초대·역할 변경·제거는 소유자/관리자만 가능합니다.
        </p>
      )}
    </div>
  )
}

function SettingsLoadState({
  loading,
  error,
  workspaceRequired = false,
}: {
  loading: boolean
  error: string | null
  workspaceRequired?: boolean
}) {
  if (workspaceRequired) {
    return <div className="rounded-xl border border-gray-200 bg-white py-12 text-center text-[13px] text-gray-400">워크스페이스를 먼저 선택하세요.</div>
  }
  if (loading) {
    return <div className="rounded-xl border border-gray-200 bg-white py-12 text-center text-[13px] text-gray-400">불러오는 중…</div>
  }
  if (error) {
    return <div className="rounded-xl border border-[#c0392b] bg-[#fcf3f2] px-4 py-6 text-center text-[13px] text-[#c0392b]">{error}</div>
  }
  return null
}

function NotificationsSection() {
  const app = useApp()
  const toast = useToast()
  const wsId = app.currentProject?.id
  const [draft, setDraft] = useState<NotificationSettingsResponse | null>(null)
  const [recipient, setRecipient] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    if (!wsId) {
      setDraft(null)
      setRecipient('')
      setError(null)
      setSaving(false)
      setLoading(false)
      return
    }
    let alive = true
    setLoading(true)
    setError(null)
    api
      .getNotificationSettings(wsId)
      .then((data) => {
        if (alive) setDraft(data)
      })
      .catch((e) => {
        if (alive) setError(e instanceof ApiError ? e.message : '알림 설정을 불러오지 못했습니다')
      })
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
  }, [wsId])

  async function save() {
    if (!wsId || !draft) return
    setSaving(true)
    try {
      const saved = await api.updateNotificationSettings(wsId, {
        slackEnabled: draft.slackEnabled,
        slackWebhookUrl: draft.slackWebhookUrl,
        emailRecipients: draft.emailRecipients,
        severity: draft.severity,
      })
      setDraft(saved)
      toast('알림 설정을 저장했습니다')
    } catch (e) {
      toast(e instanceof ApiError ? e.message : '알림 설정 저장에 실패했습니다', 'error')
    } finally {
      setSaving(false)
    }
  }

  const loadState = SettingsLoadState({ loading, error, workspaceRequired: !wsId })
  if (loadState) {
    return (
      <div>
        <Head title="알림" sub="Bifrost가 알림을 보내는 채널" />
        {loadState}
      </div>
    )
  }
  if (!draft) return null

  const update = (patch: Partial<NotificationSettingsResponse>) => setDraft((prev) => (prev ? { ...prev, ...patch } : prev))

  return (
    <div>
      <Head title="알림" sub="Bifrost가 알림을 보내는 채널" />
      <div className="space-y-4">
        <Panel title="Slack">
          <div className="space-y-3 px-5 py-4">
            <div className="flex items-center gap-3">
              <Switch checked={draft.slackEnabled} onChange={(v) => update({ slackEnabled: v })} />
              <span className="text-[13px] text-gray-700">Slack으로 알림 보내기</span>
            </div>
            <input
              value={draft.slackWebhookUrl ?? ''}
              onChange={(e) => update({ slackWebhookUrl: e.target.value })}
              placeholder="https://hooks.slack.com/services/…"
              className="h-9 w-full rounded-md border border-gray-300 px-3 font-mono text-[12px] outline-none focus:border-brand-600"
            />
            <p className="text-[11.5px] text-gray-400">테스트 전송 API는 아직 없으므로 저장된 Webhook 정책만 관리합니다.</p>
          </div>
        </Panel>

        <Panel title="이메일 수신자">
          <div className="divide-y divide-gray-50">
            {draft.emailRecipients.map((r) => (
              <div key={r} className="flex items-center px-4 py-2.5 text-[12.5px]">
                <span className="flex-1 text-gray-700">{r}</span>
                <button
                  onClick={() => update({ emailRecipients: draft.emailRecipients.filter((x) => x !== r) })}
                  className="text-[11.5px] font-medium text-[#c0392b] hover:underline"
                >
                  삭제
                </button>
              </div>
            ))}
            {draft.emailRecipients.length === 0 && (
              <div className="px-4 py-6 text-center text-[12.5px] text-gray-400">수신자가 없습니다</div>
            )}
          </div>
          <div className="flex gap-2 border-t border-gray-100 px-4 py-3">
            <input
              value={recipient}
              onChange={(e) => setRecipient(e.target.value)}
              placeholder="alerts@company.com"
              className="h-9 flex-1 rounded-md border border-gray-300 px-3 text-[13px] outline-none focus:border-brand-600"
            />
            <button
              disabled={!recipient.includes('@')}
              onClick={() => {
                update({ emailRecipients: [...draft.emailRecipients, recipient.trim()] })
                setRecipient('')
              }}
              className="rounded-md bg-brand-600 px-3 py-2 text-[13px] font-semibold text-white hover:bg-brand-700 disabled:bg-brand-300"
            >
              추가
            </button>
          </div>
        </Panel>

        <Panel title="심각도 기준">
          <div className="space-y-2 px-5 py-4">
            {(
              [
                ['all', '모든 이벤트'],
                ['warning', '경고 이상'],
                ['error', '오류만'],
              ] as const
            ).map(([v, label]) => (
              <label key={v} className="flex items-center gap-2.5 text-[13px] text-gray-700">
                <input
                  type="radio"
                  checked={draft.severity === v}
                  onChange={() => update({ severity: v as NotificationSeverityPolicy })}
                  className="accent-brand-600"
                />
                {label}
              </label>
            ))}
          </div>
        </Panel>
        <div className="flex justify-end">
          <button
            onClick={save}
            disabled={saving || !wsId}
            className="rounded-md bg-brand-600 px-3.5 py-1.5 text-[13px] font-semibold text-white hover:bg-brand-700 disabled:bg-brand-300"
          >
            저장
          </button>
        </div>
      </div>
    </div>
  )
}

function ThresholdsSection() {
  const app = useApp()
  const toast = useToast()
  const wsId = app.currentProject?.id
  const [settings, setSettings] = useState<ThresholdSettingsResponse | null>(null)
  const [warning, setWarning] = useState('')
  const [critical, setCritical] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    if (!wsId) {
      setSettings(null)
      setWarning('')
      setCritical('')
      setError(null)
      setSaving(false)
      setLoading(false)
      return
    }
    let alive = true
    setLoading(true)
    setError(null)
    api
      .getThresholdSettings(wsId)
      .then((data) => {
        if (!alive) return
        setSettings(data)
        setWarning(String(data.warning))
        setCritical(String(data.critical))
      })
      .catch((e) => {
        if (alive) setError(e instanceof ApiError ? e.message : '임계값 설정을 불러오지 못했습니다')
      })
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
  }, [wsId])

  async function save() {
    if (!wsId) return
    const w = parseInt(warning, 10)
    const c = parseInt(critical, 10)
    if (isNaN(w) || isNaN(c) || w <= 0 || c <= w) {
      toast('임계값을 올바르게 입력해주세요 (경고 < 위험)', 'error')
      return
    }
    setSaving(true)
    try {
      const saved = await api.updateThresholdSettings(wsId, { warning: w, critical: c })
      setSettings(saved)
      setWarning(String(saved.warning))
      setCritical(String(saved.critical))
      toast('임계값을 저장했습니다')
    } catch (e) {
      toast(e instanceof ApiError ? e.message : '임계값 저장에 실패했습니다', 'error')
    } finally {
      setSaving(false)
    }
  }

  const loadState = SettingsLoadState({ loading, error, workspaceRequired: !wsId })
  if (loadState) {
    return (
      <div>
        <Head title="임계값" sub="파이프라인 Lag 경보 기준을 설정합니다" />
        {loadState}
      </div>
    )
  }

  return (
    <div>
      <Head title="임계값" sub="파이프라인 Lag 경보 기준을 설정합니다" />
      <Panel>
        <div className="divide-y divide-gray-50">
          <div className="flex items-center justify-between px-5 py-4">
            <div>
              <div className="text-[13px] font-medium text-gray-800">경고 임계값</div>
              <div className="text-[12px] text-gray-500">이 값을 초과하면 파이프라인이 lag 상태로 표시됩니다</div>
            </div>
            <div className="flex items-center gap-2">
              <input
                type="number"
                value={warning}
                onChange={(e) => setWarning(e.target.value)}
                className="h-9 w-28 rounded-md border border-gray-300 px-3 text-right font-mono text-[13px] outline-none focus:border-brand-600"
              />
              <span className="text-[12px] text-gray-500">건</span>
            </div>
          </div>
          <div className="flex items-center justify-between px-5 py-4">
            <div>
              <div className="text-[13px] font-medium text-gray-800">위험 임계값</div>
              <div className="text-[12px] text-gray-500">이 값을 초과하면 인시던트가 자동 생성됩니다</div>
            </div>
            <div className="flex items-center gap-2">
              <input
                type="number"
                value={critical}
                onChange={(e) => setCritical(e.target.value)}
                className="h-9 w-28 rounded-md border border-gray-300 px-3 text-right font-mono text-[13px] outline-none focus:border-brand-600"
              />
              <span className="text-[12px] text-gray-500">건</span>
            </div>
          </div>
        </div>
        <div className="flex items-center justify-between border-t border-gray-100 px-5 py-3">
          <p className="text-[11.5px] text-gray-400">
            현재 설정: 경고 {settings?.warning.toLocaleString() ?? '—'}건 · 위험 {settings?.critical.toLocaleString() ?? '—'}건
          </p>
          <button
            onClick={save}
            disabled={saving || !wsId}
            className="rounded-md bg-brand-600 px-3.5 py-1.5 text-[13px] font-semibold text-white hover:bg-brand-700 disabled:bg-brand-300"
          >
            저장
          </button>
        </div>
      </Panel>
    </div>
  )
}

function AiSection() {
  const app = useApp()
  const toast = useToast()
  const wsId = app.currentProject?.id
  const [policy, setPolicy] = useState<AiPolicySettingsResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    if (!wsId) {
      setPolicy(null)
      setError(null)
      setSaving(false)
      setLoading(false)
      return
    }
    let alive = true
    setLoading(true)
    setError(null)
    api
      .getAiPolicySettings(wsId)
      .then((data) => {
        if (alive) setPolicy(data)
      })
      .catch((e) => {
        if (alive) setError(e instanceof ApiError ? e.message : 'AI 정책을 불러오지 못했습니다')
      })
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
  }, [wsId])

  async function save() {
    if (!wsId || !policy) return
    setSaving(true)
    try {
      const saved = await api.updateAiPolicySettings(wsId, policy)
      setPolicy(saved)
      toast('AI 자동복구 정책을 저장했습니다')
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'AI 정책 저장에 실패했습니다', 'error')
    } finally {
      setSaving(false)
    }
  }

  const loadState = SettingsLoadState({ loading, error, workspaceRequired: !wsId })
  if (loadState) {
    return (
      <div>
        <Head title="AI 자동복구" sub="자동 복구 정책" />
        {loadState}
      </div>
    )
  }
  if (!policy) return null

  const update = (patch: Partial<AiPolicySettingsResponse>) => setPolicy((prev) => (prev ? { ...prev, ...patch } : prev))

  return (
    <div>
      <Head title="AI 자동복구" sub="자동 복구 정책" />
      <Panel title="자동 복구">
        <div className="divide-y divide-gray-50">
          <ToggleRow
            on={policy.autonomous}
            onToggle={(v) => update({ autonomous: v })}
            title="자동 실행 허용"
            desc="운영 AI가 낮은 리스크의 조치를 승인 없이 실행하도록 허용합니다."
          />
          <div className="flex items-center justify-between px-5 py-3.5">
            <div>
              <div className="text-[13px] font-medium text-gray-800">승인 대기 시간</div>
              <div className="text-[12px] text-gray-500">에이전트가 타임아웃 전까지 사람의 승인을 기다리는 시간입니다.</div>
            </div>
            <select
              value={policy.approvalWaitMinutes}
              onChange={(e) => update({ approvalWaitMinutes: Number(e.target.value) })}
              className="h-9 rounded-md border border-gray-300 px-2 text-[13px] outline-none"
            >
              {[5, 10, 30, 60].map((minutes) => (
                <option key={minutes} value={minutes}>{minutes}분</option>
              ))}
            </select>
          </div>
          <ToggleRow
            on={policy.prodLock}
            onToggle={(v) => update({ prodLock: v })}
            title="프로덕션 환경 잠금"
            desc="프로덕션 리소스에 대한 모든 자동 조치를 차단합니다."
          />
        </div>
        <div className="flex justify-end border-t border-gray-100 px-5 py-3">
          <button
            onClick={save}
            disabled={saving || !wsId}
            className="rounded-md bg-brand-600 px-3.5 py-1.5 text-[13px] font-semibold text-white hover:bg-brand-700 disabled:bg-brand-300"
          >
            저장
          </button>
        </div>
      </Panel>
    </div>
  )
}

function ToggleRow({ on, onToggle, title, desc }: { on: boolean; onToggle: (v: boolean) => void; title: string; desc: string }) {
  return (
    <div className="flex items-center justify-between px-5 py-3.5">
      <div className="pr-4">
        <div className="text-[13px] font-medium text-gray-800">{title}</div>
        <div className="text-[12px] text-gray-500">{desc}</div>
      </div>
      <Switch checked={on} onChange={onToggle} />
    </div>
  )
}

export function KafkaUsersSection() {
  const app = useApp()
  const toast = useToast()
  const wsId = app.currentProject?.id
  const [rows, setRows] = useState<KafkaPrincipalResponse[]>([])
  const [username, setUsername] = useState('')
  const [secretRef, setSecretRef] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)
  const [secretLoadingId, setSecretLoadingId] = useState<string | null>(null)
  const [secretById, setSecretById] = useState<Record<string, KafkaPrincipalSecretResponse>>({})
  const [creating, setCreating] = useState(false)

  async function reload(ws: string) {
    setLoading(true)
    setError(null)
    try {
      setRows(await api.listKafkaPrincipals(ws))
      setSecretById({})
    } catch (e) {
      setRows([])
      setError(e instanceof ApiError ? e.message : 'Kafka 사용자를 불러오지 못했습니다')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    if (!wsId) {
      setRows([])
      setUsername('')
      setSecretRef('')
      setError(null)
      setBusyId(null)
      setSecretLoadingId(null)
      setSecretById({})
      setCreating(false)
      setLoading(false)
      return
    }
    let alive = true
    setLoading(true)
    setError(null)
    api
      .listKafkaPrincipals(wsId)
      .then((data) => {
        if (alive) setRows(data)
      })
      .catch((e) => {
        if (!alive) return
        setRows([])
        setError(e instanceof ApiError ? e.message : 'Kafka 사용자를 불러오지 못했습니다')
      })
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
  }, [wsId])

  async function create() {
    if (!wsId || !username.trim()) return
    setCreating(true)
    try {
      await api.createKafkaPrincipal(wsId, { username: username.trim(), secretRef: secretRef.trim() || null })
      toast('Kafka 사용자를 생성했습니다')
      setUsername('')
      setSecretRef('')
      await reload(wsId)
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'Kafka 사용자 생성에 실패했습니다', 'error')
    } finally {
      setCreating(false)
    }
  }

  async function action(id: string, kind: 'deactivate' | 'revoke' | 'rotate') {
    if (!wsId) return
    setBusyId(id)
    try {
      const updated =
        kind === 'deactivate'
          ? await api.deactivateKafkaPrincipal(wsId, id)
          : kind === 'revoke'
            ? await api.revokeKafkaPrincipal(wsId, id)
            : await api.rotateKafkaPrincipal(wsId, id)
      setRows((prev) => prev.map((row) => (row.id === id ? updated : row)))
      setSecretById((prev) => {
        const next = { ...prev }
        delete next[id]
        return next
      })
      toast(kind === 'rotate' ? 'Kafka 사용자 시크릿 참조를 교체했습니다' : 'Kafka 사용자 상태를 변경했습니다')
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'Kafka 사용자 작업에 실패했습니다', 'error')
    } finally {
      setBusyId(null)
    }
  }

  async function viewSecret(id: string) {
    if (!wsId) return
    setSecretLoadingId(id)
    try {
      const secret = await api.kafkaPrincipalSecret(wsId, id)
      setSecretById((prev) => ({ ...prev, [id]: secret }))
      toast('Kafka Secret 참조를 불러왔습니다')
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'Kafka Secret 조회에 실패했습니다', 'error')
    } finally {
      setSecretLoadingId(null)
    }
  }

  function copySecret(value: string, label: string) {
    navigator.clipboard?.writeText(value)
    toast(`${label} copied`)
  }

  const loadState = SettingsLoadState({ loading, error, workspaceRequired: !wsId })
  if (loadState) {
    return (
      <div>
        <Head title="Kafka 사용자" sub="클러스터 접속이 허용된 주체(Principal)" />
        {loadState}
      </div>
    )
  }

  return (
    <div>
      <Head title="Kafka 사용자" sub="클러스터 접속이 허용된 주체(Principal)" />
      <Panel title="주체(Principal)">
        <div className="overflow-x-auto">
          <table className="min-w-[780px] w-full text-[12px]">
            <thead>
              <tr className="border-b border-gray-100 text-left text-[10.5px] uppercase tracking-wide text-gray-400">
                <th className="px-4 py-2 font-semibold">Username</th>
                <th className="px-3 py-2 font-semibold">Secret ref</th>
                <th className="px-3 py-2 font-semibold">Created</th>
                <th className="px-3 py-2 font-semibold">상태</th>
                <th className="px-3 py-2" />
              </tr>
            </thead>
            <tbody>
              {rows.map((u) => (
                <Fragment key={u.id}>
                  <tr className="border-b border-gray-50">
                    <td className="max-w-[180px] truncate px-4 py-2.5 font-mono font-medium text-gray-800">{u.username}</td>
                    <td className="max-w-[260px] truncate px-3 py-2.5 font-mono text-[11px] text-gray-500">{u.secretRef ?? '—'}</td>
                    <td className="px-3 py-2.5 text-gray-400">{u.createdAt.slice(0, 10)}</td>
                    <td className="px-3 py-2.5"><StatusBadge status={u.status} /></td>
                    <td className="px-3 py-2.5 text-right">
                      <div className="flex flex-wrap justify-end gap-2">
                        {u.status === 'ACTIVE' && (
                          <button
                            disabled={secretLoadingId === u.id}
                            onClick={() => viewSecret(u.id)}
                            className="inline-flex items-center gap-1 text-[11px] font-medium text-gray-700 hover:underline disabled:opacity-50"
                          >
                            <Icon name="eye" size={12} />
                            {secretLoadingId === u.id ? '조회중' : 'Secret'}
                          </button>
                        )}
                        {u.status === 'ACTIVE' && (
                          <button
                            disabled={busyId === u.id}
                            onClick={() => action(u.id, 'deactivate')}
                            className="text-[11px] font-medium text-gray-600 hover:underline disabled:opacity-50"
                          >
                            비활성화
                          </button>
                        )}
                        {u.status !== 'REVOKED' && (
                          <>
                            <button
                              disabled={busyId === u.id}
                              onClick={() => action(u.id, 'rotate')}
                              className="text-[11px] font-medium text-brand-600 hover:underline disabled:opacity-50"
                            >
                              교체
                            </button>
                            <button
                              disabled={busyId === u.id}
                              onClick={() => action(u.id, 'revoke')}
                              className="text-[11px] font-medium text-[#c0392b] hover:underline disabled:opacity-50"
                            >
                              폐기
                            </button>
                          </>
                        )}
                      </div>
                    </td>
                  </tr>
                  {secretById[u.id] && (
                    <tr className="border-b border-gray-100 bg-gray-50/70">
                      <td colSpan={5} className="px-4 py-3">
                        <KafkaPrincipalSecretPanel
                          secret={secretById[u.id]}
                          onCopy={copySecret}
                        />
                      </td>
                    </tr>
                  )}
                </Fragment>
              ))}
              {rows.length === 0 && (
                <tr>
                  <td colSpan={5} className="px-4 py-6 text-center text-[12.5px] text-gray-400">
                    Kafka 사용자가 없습니다
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
        <div className="grid grid-cols-1 items-center gap-2 border-t border-gray-100 px-4 py-3 md:grid-cols-[1fr_1fr_auto]">
          <input
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            placeholder="username (영문/숫자/_/-)"
            className="h-9 rounded-md border border-gray-300 px-3 text-[13px] outline-none focus:border-brand-600"
          />
          <input
            value={secretRef}
            onChange={(e) => setSecretRef(e.target.value)}
            placeholder="secretRef (선택)"
            className="h-9 rounded-md border border-gray-300 px-3 font-mono text-[12px] outline-none focus:border-brand-600"
          />
          <button
            disabled={!username.trim() || creating}
            onClick={create}
            className="rounded-md bg-brand-600 px-3 py-2 text-[13px] font-semibold text-white hover:bg-brand-700 disabled:bg-brand-300"
          >
            추가
          </button>
        </div>
      </Panel>
    </div>
  )
}

function KafkaPrincipalSecretPanel({
  secret,
  onCopy,
}: {
  secret: KafkaPrincipalSecretResponse
  onCopy: (value: string, label: string) => void
}) {
  const secretPath = `${secret.namespace}/${secret.secretName}`
  return (
    <div className="grid gap-3 rounded-lg border border-gray-200 bg-white p-3 md:grid-cols-[1.2fr_1fr_1fr]">
      <SecretField label="Secret" value={secretPath} mono onCopy={() => onCopy(secretPath, 'Secret ref')} />
      <SecretField label="Password" value={secret.passwordMasked} mono />
      <SecretField label="Policy" value={secret.exposurePolicy} />
      <div className="md:col-span-2">
        <div className="mb-1 text-[10.5px] uppercase tracking-wide text-gray-400">Keys</div>
        <div className="flex flex-wrap gap-1.5">
          {secret.availableKeys.map((key) => (
            <span key={key} className="rounded border border-gray-200 bg-gray-50 px-2 py-0.5 font-mono text-[11px] text-gray-600">
              {key}
            </span>
          ))}
        </div>
      </div>
      <SecretField label="Retrieved" value={accountDateTime(secret.retrievedAt)} />
    </div>
  )
}

function SecretField({
  label,
  value,
  mono,
  onCopy,
}: {
  label: string
  value: string
  mono?: boolean
  onCopy?: () => void
}) {
  return (
    <div className="min-w-0">
      <div className="mb-1 text-[10.5px] uppercase tracking-wide text-gray-400">{label}</div>
      <div className="flex min-w-0 items-center gap-2">
        <span className={cn('truncate text-[12px] font-semibold text-gray-800', mono && 'font-mono')}>{value}</span>
        {onCopy && (
          <button
            type="button"
            onClick={onCopy}
            className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded border border-gray-200 text-gray-500 hover:bg-gray-50"
            aria-label={`${label} copy`}
          >
            <Icon name="copy" size={12} />
          </button>
        )}
      </div>
    </div>
  )
}
