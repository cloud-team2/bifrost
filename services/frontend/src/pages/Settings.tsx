import { useEffect, useState } from 'react'
import { Icon, type IconName } from '../components/Icon'
import { Panel, StatusBadge } from '../components/blocks'
import { Switch } from '../components/ui'
import { useToast } from '../components/Toast'
import { useApp } from '../store/AppStore'
import type { Role } from '../data/types'
import {
  api,
  ApiError,
  type AiPolicySettingsResponse,
  type KafkaPrincipalResponse,
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
  { id: 'kafka-secrets', label: 'Kafka 시크릿', icon: 'lock' },
]

const ROLE_LABEL: Record<Role, string> = {
  developer: '개발자',
  operator: '운영자',
  admin: '관리자',
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
          {section === 'kafka-secrets' && <KafkaSecretsSection />}
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
              u.role === 'developer'
                ? 'bg-gradient-to-br from-sky-400 to-indigo-500'
                : 'bg-gradient-to-br from-violet-400 to-violet-600',
            )}
          >
            {u.initial}
          </div>
          <div>
            <div className="text-[15px] font-semibold text-gray-900">{u.name}</div>
            <div className="text-[12.5px] text-gray-500">{ROLE_LABEL[u.role]}</div>
          </div>
        </div>
        <dl className="divide-y divide-gray-50">
          <Field label="이름" value={u.name} />
          <Field label="이메일" value={u.email} />
          <Field label="역할" value={ROLE_LABEL[u.role]} />
          <Field label="가입일" value="2026-02-11" />
          <Field label="마지막 로그인" value="2026-05-22 09:02" />
        </dl>
      </Panel>
      <button
        onClick={app.logout}
        className="mt-4 flex items-center gap-1.5 rounded-md border border-rose-200 px-3 py-2 text-[13px] font-medium text-rose-600 hover:bg-rose-50"
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
  const [name, setName] = useState(app.currentProject?.name ?? app.settings.projectName)
  const [timezone, setTimezone] = useState('')
  const [saving, setSaving] = useState(false)

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

  // 로드된 timezone이 옵션에 없으면 함께 노출
  const tzOptions = Array.from(new Set([timezone, ...TZ_OPTIONS].filter(Boolean)))

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
    if (wsId) reload(wsId)
  }, [wsId])

  // 현재 사용자의 워크스페이스 역할로 관리 권한 판별 (OWNER/ADMIN만 변경·제거·초대)
  const myRole = members.find((m) => m.email === myEmail)?.role
  const canManage = myRole === 'OWNER' || myRole === 'ADMIN'

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
        {loading ? (
          <div className="px-4 py-6 text-center text-[12.5px] text-gray-400">불러오는 중…</div>
        ) : loadError ? (
          <div className="px-4 py-6 text-center text-[12.5px] text-rose-500">{loadError}</div>
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
                          className="text-[11.5px] font-medium text-rose-600 hover:underline"
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
        {canManage && (
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
      {!loading && !loadError && !canManage && (
        <p className="mt-2 px-1 text-[11.5px] text-gray-400">
          멤버 초대·역할 변경·제거는 소유자/관리자만 가능합니다.
        </p>
      )}
    </div>
  )
}

function SettingsLoadState({ loading, error }: { loading: boolean; error: string | null }) {
  if (loading) {
    return <div className="rounded-xl border border-gray-200 bg-white py-12 text-center text-[13px] text-gray-400">불러오는 중…</div>
  }
  if (error) {
    return <div className="rounded-xl border border-rose-200 bg-rose-50 px-4 py-6 text-center text-[13px] text-rose-700">{error}</div>
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
    if (!wsId) return
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

  const loadState = SettingsLoadState({ loading, error })
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
                  className="text-[11.5px] font-medium text-rose-600 hover:underline"
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
    if (!wsId) return
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

  const loadState = SettingsLoadState({ loading, error })
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
    if (!wsId) return
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

  const loadState = SettingsLoadState({ loading, error })
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
  const [creating, setCreating] = useState(false)

  async function reload(ws: string) {
    setLoading(true)
    setError(null)
    try {
      setRows(await api.listKafkaPrincipals(ws))
    } catch (e) {
      setRows([])
      setError(e instanceof ApiError ? e.message : 'Kafka 사용자를 불러오지 못했습니다')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    if (wsId) reload(wsId)
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
      toast(kind === 'rotate' ? 'Kafka 사용자 시크릿 참조를 교체했습니다' : 'Kafka 사용자 상태를 변경했습니다')
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'Kafka 사용자 작업에 실패했습니다', 'error')
    } finally {
      setBusyId(null)
    }
  }

  const loadState = SettingsLoadState({ loading, error })
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
        <table className="w-full text-[12px]">
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
              <tr key={u.id} className="border-b border-gray-50">
                <td className="px-4 py-2.5 font-mono font-medium text-gray-800">{u.username}</td>
                <td className="px-3 py-2.5 font-mono text-[11px] text-gray-500">{u.secretRef ?? '—'}</td>
                <td className="px-3 py-2.5 text-gray-400">{u.createdAt.slice(0, 10)}</td>
                <td className="px-3 py-2.5"><StatusBadge status={u.status} /></td>
                <td className="px-3 py-2.5 text-right">
                  <div className="flex justify-end gap-2">
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
                          className="text-[11px] font-medium text-rose-600 hover:underline disabled:opacity-50"
                        >
                          폐기
                        </button>
                      </>
                    )}
                  </div>
                </td>
              </tr>
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
        <div className="grid grid-cols-[1fr_1fr_auto] items-center gap-2 border-t border-gray-100 px-4 py-3">
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

export function KafkaSecretsSection() {
  return (
    <div>
      <Head title="Kafka 시크릿" sub="커넥터와 컨슈머가 사용하는 자격 증명" />
      <Panel title="이번 PR 제외">
        <div className="space-y-2 px-5 py-5 text-[13px] leading-relaxed text-gray-600">
          <p>Kafka 시크릿은 전용 백엔드 API가 아직 없어 mock 목록·생성·폐기 UI를 제거했습니다.</p>
          <p className="text-[12px] text-gray-400">백엔드 API가 추가되면 이 탭을 실데이터로 연결합니다.</p>
        </div>
      </Panel>
    </div>
  )
}
