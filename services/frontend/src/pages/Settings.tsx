import { useMemo, useState } from 'react'
import { Icon, type IconName } from '../components/Icon'
import { Panel, StatusBadge } from '../components/blocks'
import { Gauge } from '../components/blocks'
import { TrendChart, CHART_COLORS } from '../components/Charts'
import { Switch } from '../components/ui'
import { useToast } from '../components/Toast'
import { useApp } from '../store/AppStore'
import { genSeries } from '../data/helpers'
import type { Role } from '../data/types'
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

function GeneralSection() {
  const app = useApp()
  const toast = useToast()
  const [name, setName] = useState(app.currentProject?.name ?? app.settings.projectName)
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
              value={app.settings.timezone}
              onChange={(e) => app.updateSettings({ timezone: e.target.value })}
              className="h-9 w-full rounded-md border border-gray-300 px-3 text-[13px] outline-none focus:border-brand-600"
            >
              {['Asia/Seoul (GMT+9)', 'Asia/Singapore (GMT+8)', 'UTC', 'US/Eastern (GMT-5)'].map((t) => (
                <option key={t}>{t}</option>
              ))}
            </select>
          </Labeled>
          <div className="text-[12px] text-gray-400">생성일 {app.currentProject?.createdAt}</div>
        </div>
        <div className="flex justify-end border-t border-gray-100 px-5 py-3">
          <button
            onClick={() => toast('프로젝트 설정을 저장했습니다')}
            className="rounded-md bg-brand-600 px-3.5 py-1.5 text-[13px] font-semibold text-white hover:bg-brand-700"
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

function MembersSection() {
  const app = useApp()
  const toast = useToast()
  const [email, setEmail] = useState('')
  const [role, setRole] = useState<Role>('developer')
  return (
    <div>
      <Head title="멤버" sub="이 프로젝트에 접근할 수 있는 사용자" />
      <Panel title="팀">
        <table className="w-full text-[12.5px]">
          <tbody>
            {app.members.map((m) => (
              <tr key={m.email} className="border-b border-gray-50 last:border-0">
                <td className="px-4 py-2.5">
                  <div className="font-medium text-gray-800">{m.name}</div>
                  <div className="text-[11px] text-gray-400">{m.email}</div>
                </td>
                <td className="px-4 py-2.5">
                  <select
                    value={m.role}
                    onChange={(e) => app.changeMemberRole(m.email, e.target.value as Role)}
                    className="rounded-md border border-gray-200 px-2 py-1 text-[12px] outline-none"
                  >
                    <option value="developer">개발자</option>
                    <option value="operator">운영자</option>
                  </select>
                </td>
                <td className="px-4 py-2.5 text-gray-400">{m.joinedAt}</td>
                <td className="px-4 py-2.5 text-right">
                  <button
                    onClick={() => {
                      app.removeMember(m.email)
                      toast('멤버를 삭제했습니다', 'info')
                    }}
                    className="text-[11.5px] font-medium text-rose-600 hover:underline"
                  >
                    삭제
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="flex items-center gap-2 border-t border-gray-100 px-4 py-3">
          <input
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="teammate@company.com"
            className="h-9 flex-1 rounded-md border border-gray-300 px-3 text-[13px] outline-none focus:border-brand-600"
          />
          <select
            value={role}
            onChange={(e) => setRole(e.target.value as Role)}
            className="h-9 rounded-md border border-gray-300 px-2 text-[13px] outline-none"
          >
            <option value="developer">개발자</option>
            <option value="operator">운영자</option>
          </select>
          <button
            disabled={!email.includes('@')}
            onClick={() => {
              app.addMember(email, role)
              toast(`${email} 님에게 초대를 보냈습니다`)
              setEmail('')
            }}
            className="rounded-md bg-brand-600 px-3 py-2 text-[13px] font-semibold text-white hover:bg-brand-700 disabled:bg-brand-300"
          >
            초대
          </button>
        </div>
      </Panel>
    </div>
  )
}

function NotificationsSection() {
  const app = useApp()
  const toast = useToast()
  const { settings } = app
  const [recipient, setRecipient] = useState('')
  return (
    <div>
      <Head title="알림" sub="Bifrost가 알림을 보내는 채널" />
      <div className="space-y-4">
        <Panel title="Slack">
          <div className="space-y-3 px-5 py-4">
            <div className="flex items-center gap-3">
              <Switch checked={settings.slackEnabled} onChange={(v) => app.updateSettings({ slackEnabled: v })} />
              <span className="text-[13px] text-gray-700">Slack으로 알림 보내기</span>
            </div>
            <input
              value={settings.slackWebhook}
              onChange={(e) => app.updateSettings({ slackWebhook: e.target.value })}
              placeholder="https://hooks.slack.com/services/…"
              className="h-9 w-full rounded-md border border-gray-300 px-3 font-mono text-[12px] outline-none focus:border-brand-600"
            />
            <button
              onClick={() => toast(settings.slackWebhook ? 'Slack으로 테스트 메시지를 보냈습니다' : '먼저 Webhook URL을 입력하세요', settings.slackWebhook ? 'success' : 'error')}
              className="rounded-md border border-gray-300 px-3 py-1.5 text-[12.5px] font-medium text-gray-700 hover:bg-gray-50"
            >
              테스트 전송
            </button>
          </div>
        </Panel>

        <Panel title="이메일 수신자">
          <div className="divide-y divide-gray-50">
            {settings.emailRecipients.map((r) => (
              <div key={r} className="flex items-center px-4 py-2.5 text-[12.5px]">
                <span className="flex-1 text-gray-700">{r}</span>
                <button
                  onClick={() => app.updateSettings({ emailRecipients: settings.emailRecipients.filter((x) => x !== r) })}
                  className="text-[11.5px] font-medium text-rose-600 hover:underline"
                >
                  삭제
                </button>
              </div>
            ))}
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
                app.updateSettings({ emailRecipients: [...settings.emailRecipients, recipient] })
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
                  checked={settings.severity === v}
                  onChange={() => app.updateSettings({ severity: v })}
                  className="accent-brand-600"
                />
                {label}
              </label>
            ))}
          </div>
        </Panel>
      </div>
    </div>
  )
}

function ThresholdsSection() {
  const app = useApp()
  const toast = useToast()
  const { lagThresholds } = app.settings
  const [warning, setWarning] = useState(String(lagThresholds.warning))
  const [critical, setCritical] = useState(String(lagThresholds.critical))

  function save() {
    const w = parseInt(warning, 10)
    const c = parseInt(critical, 10)
    if (isNaN(w) || isNaN(c) || w <= 0 || c <= w) {
      toast('임계값을 올바르게 입력해주세요 (경고 < 위험)', 'error')
      return
    }
    app.updateSettings({ lagThresholds: { warning: w, critical: c } })
    toast('임계값을 저장했습니다')
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
            현재 설정: 경고 {lagThresholds.warning.toLocaleString()}건 · 위험 {lagThresholds.critical.toLocaleString()}건
          </p>
          <button
            onClick={save}
            className="rounded-md bg-brand-600 px-3.5 py-1.5 text-[13px] font-semibold text-white hover:bg-brand-700"
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
  const { settings } = app
  const usage = useMemo(() => genSeries([{ key: 'tokens', base: 28000, vary: 14000, drift: 600 }], 30), [])
  const used = 612
  const max = 1000
  return (
    <div>
      <Head title="AI 자동복구" sub="토큰 사용량 및 자동 복구 정책" />
      <div className="space-y-4">
        <Panel title="토큰 사용량">
          <div className="px-5 py-4">
            <Gauge label={`월 ${max}K 토큰 중 ${used}K 사용`} value={used} max={max} unit="K" />
            <div className="mt-4">
              <TrendChart
                data={usage}
                type="area"
                height={150}
                series={[{ key: 'tokens', label: '토큰', color: CHART_COLORS.brand }]}
              />
            </div>
          </div>
        </Panel>
        <Panel title="자동 복구">
          <div className="divide-y divide-gray-50">
            <ToggleRow
              on={settings.aiAutonomous}
              onToggle={(v) => app.updateSettings({ aiAutonomous: v })}
              title="자동 실행 허용"
              desc="운영 AI가 낮은 리스크의 조치를 승인 없이 실행하도록 허용합니다."
            />
            <div className="flex items-center justify-between px-5 py-3.5">
              <div>
                <div className="text-[13px] font-medium text-gray-800">승인 대기 시간</div>
                <div className="text-[12px] text-gray-500">에이전트가 타임아웃 전까지 사람의 승인을 기다리는 시간입니다.</div>
              </div>
              <select
                value={settings.aiApprovalWait}
                onChange={(e) => app.updateSettings({ aiApprovalWait: e.target.value })}
                className="h-9 rounded-md border border-gray-300 px-2 text-[13px] outline-none"
              >
                {['5분', '10분', '30분', '1시간'].map((t) => (
                  <option key={t}>{t}</option>
                ))}
              </select>
            </div>
            <ToggleRow
              on={settings.aiProdLock}
              onToggle={(v) => app.updateSettings({ aiProdLock: v })}
              title="프로덕션 환경 잠금"
              desc="프로덕션 리소스에 대한 모든 자동 조치를 차단합니다."
            />
          </div>
        </Panel>
      </div>
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
  const [principal, setPrincipal] = useState('')
  const [auth, setAuth] = useState<'SCRAM-SHA-512' | 'mTLS'>('SCRAM-SHA-512')
  return (
    <div>
      <Head title="Kafka 사용자" sub="클러스터 접속이 허용된 주체(Principal)" />
      <Panel title="주체(Principal)">
        <table className="w-full text-[12px]">
          <thead>
            <tr className="border-b border-gray-100 text-left text-[10.5px] uppercase tracking-wide text-gray-400">
              <th className="px-4 py-2 font-semibold">주체</th>
              <th className="px-3 py-2 font-semibold">인증</th>
              <th className="px-3 py-2 font-semibold">ACL</th>
              <th className="px-3 py-2 font-semibold">상태</th>
              <th className="px-3 py-2" />
            </tr>
          </thead>
          <tbody>
            {app.kafkaUsers.map((u) => (
              <tr key={u.id} className="border-b border-gray-50">
                <td className="px-4 py-2.5 font-mono font-medium text-gray-800">{u.principal}</td>
                <td className="px-3 py-2.5 text-gray-500">{u.auth}</td>
                <td className="px-3 py-2.5">
                  <div className="flex gap-1">
                    {(['read', 'write', 'admin'] as const).map((k) => (
                      <span
                        key={k}
                        className={cn(
                          'rounded px-1 py-0.5 text-[9.5px] font-bold uppercase',
                          u.acl[k] ? 'bg-brand-100 text-brand-700' : 'bg-gray-100 text-gray-300',
                        )}
                      >
                        {k[0]}
                      </span>
                    ))}
                  </div>
                </td>
                <td className="px-3 py-2.5">
                  <button onClick={() => app.toggleKafkaUser(u.id)}>
                    <StatusBadge status={u.status} />
                  </button>
                </td>
                <td className="px-3 py-2.5 text-right">
                  <button
                    onClick={() => {
                      app.removeKafkaUser(u.id)
                      toast('주체를 삭제했습니다', 'info')
                    }}
                    className="text-[11px] font-medium text-rose-600 hover:underline"
                  >
                    삭제
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="flex items-center gap-2 border-t border-gray-100 px-4 py-3">
          <input
            value={principal}
            onChange={(e) => setPrincipal(e.target.value)}
            placeholder="서비스 이름 (User: 접두사 자동 추가)"
            className="h-9 flex-1 rounded-md border border-gray-300 px-3 text-[13px] outline-none focus:border-brand-600"
          />
          <select
            value={auth}
            onChange={(e) => setAuth(e.target.value as typeof auth)}
            className="h-9 rounded-md border border-gray-300 px-2 text-[13px] outline-none"
          >
            <option>SCRAM-SHA-512</option>
            <option>mTLS</option>
          </select>
          <button
            disabled={!principal.trim()}
            onClick={() => {
              app.addKafkaUser(principal.trim(), auth, `${principal.trim()}-secret`)
              toast('Kafka 사용자를 생성했습니다')
              setPrincipal('')
            }}
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
  const app = useApp()
  const toast = useToast()
  const [name, setName] = useState('')
  const [type, setType] = useState<'SCRAM' | 'mTLS'>('SCRAM')
  const [revealed, setRevealed] = useState<string | null>(null)

  return (
    <div>
      <Head title="Kafka 시크릿" sub="커넥터와 컨슈머가 사용하는 자격 증명" />

      {revealed && (
        <div className="mb-4 rounded-lg border border-amber-300 bg-amber-50 p-3.5">
          <div className="flex items-center gap-1.5 text-[12px] font-bold text-amber-700">
            <Icon name="alert" size={13} />
            1회성 시크릿 값
          </div>
          <div className="mt-1.5 flex items-center gap-2">
            <code className="flex-1 rounded bg-white px-2 py-1.5 font-mono text-[12px] text-gray-800">{revealed}</code>
            <button
              onClick={() => {
                navigator.clipboard?.writeText(revealed)
                toast('시크릿을 복사했습니다')
              }}
              className="rounded-md border border-amber-300 bg-white px-2 py-1.5 text-[11.5px] font-medium text-amber-700"
            >
              복사
            </button>
          </div>
          <div className="mt-1.5 text-[11px] text-amber-700">
            이 값은 창을 닫으면 다시 확인할 수 없습니다.
          </div>
        </div>
      )}

      <Panel title="시크릿">
        <table className="w-full text-[12px]">
          <thead>
            <tr className="border-b border-gray-100 text-left text-[10.5px] uppercase tracking-wide text-gray-400">
              <th className="px-4 py-2 font-semibold">이름</th>
              <th className="px-3 py-2 font-semibold">유형</th>
              <th className="px-3 py-2 font-semibold">연결 수</th>
              <th className="px-3 py-2 font-semibold">교체일</th>
              <th className="px-3 py-2 font-semibold">상태</th>
              <th className="px-3 py-2" />
            </tr>
          </thead>
          <tbody>
            {app.kafkaSecrets.map((s) => (
              <tr key={s.id} className="border-b border-gray-50">
                <td className="px-4 py-2.5 font-mono font-medium text-gray-800">{s.name}</td>
                <td className="px-3 py-2.5">
                  <span className="rounded bg-gray-100 px-1.5 py-0.5 text-[10px] font-semibold text-gray-600">{s.type}</span>
                </td>
                <td className="px-3 py-2.5 text-gray-600">{s.connections}</td>
                <td className="px-3 py-2.5 text-gray-400">{s.lastRotated}</td>
                <td className="px-3 py-2.5"><StatusBadge status={s.status} /></td>
                <td className="px-3 py-2.5 text-right">
                  {s.status === 'active' && (
                    <button
                      onClick={() => {
                        app.revokeSecret(s.id)
                        toast('시크릿을 폐기했습니다', 'info')
                      }}
                      className="text-[11px] font-medium text-rose-600 hover:underline"
                    >
                      폐기
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="flex items-center gap-2 border-t border-gray-100 px-4 py-3">
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="시크릿 이름"
            className="h-9 flex-1 rounded-md border border-gray-300 px-3 text-[13px] outline-none focus:border-brand-600"
          />
          <select
            value={type}
            onChange={(e) => setType(e.target.value as typeof type)}
            className="h-9 rounded-md border border-gray-300 px-2 text-[13px] outline-none"
          >
            <option>SCRAM</option>
            <option>mTLS</option>
          </select>
          <button
            disabled={!name.trim()}
            onClick={() => {
              app.addSecret(name.trim(), type)
              setRevealed(`bfr_${Math.random().toString(36).slice(2, 12)}${Math.random().toString(36).slice(2, 10)}`)
              toast('시크릿을 생성했습니다')
              setName('')
            }}
            className="rounded-md bg-brand-600 px-3 py-2 text-[13px] font-semibold text-white hover:bg-brand-700 disabled:bg-brand-300"
          >
            생성
          </button>
        </div>
      </Panel>
    </div>
  )
}
