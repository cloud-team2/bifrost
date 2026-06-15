import { useState } from 'react'
import { BrandMark } from '../components/BrandMark'
import { Icon } from '../components/Icon'
import { Modal } from '../components/Modal'
import { useToast } from '../components/Toast'
import { useApp } from '../store/AppStore'
import { cn } from '../lib/format'
import type { Edge, Project } from '../data/types'
import type { IncidentResponse } from '../lib/api'

/* ---------------------------------------------------- Project List */

export function ProjectListView() {
  const app = useApp()
  const [createOpen, setCreateOpen] = useState(false)
  const list = app.visibleProjects

  return (
    <div className="min-h-screen bg-zinc-50">
      <header className="flex h-14 items-center gap-2.5 border-b border-gray-200 bg-white px-6">
        <BrandMark size={28} />
        <span className="text-[17px] font-bold lowercase tracking-tight text-gray-900">bifrost</span>
        <div className="flex-1" />
        <span className="text-[13px] text-gray-500">{app.currentUser?.name}</span>
        <button
          onClick={app.logout}
          className="flex items-center gap-1.5 rounded-md px-2 py-1 text-[12.5px] text-gray-500 hover:bg-gray-100"
        >
          <Icon name="logout" size={14} />
          Sign out
        </button>
      </header>

      {list.length === 0 ? (
        <ProjectEmptyView onCreate={() => setCreateOpen(true)} />
      ) : (
        <div className="mx-auto max-w-[1080px] px-6 py-10">
          <div className="flex items-end justify-between">
            <div>
              <h1 className="text-[22px] font-semibold text-gray-900">Projects</h1>
              <p className="mt-0.5 text-sm text-gray-500">데이터 파이프라인 프로젝트 목록</p>
            </div>
            <button
              onClick={() => setCreateOpen(true)}
              className="flex items-center gap-1.5 rounded-md bg-brand-600 px-3.5 py-2 text-sm font-medium text-white hover:bg-brand-700"
            >
              <Icon name="plus" size={15} />
              New Project
            </button>
          </div>

          <div className="mt-6 grid grid-cols-3 gap-4">
            {list.map((p) => (
              <ProjectCard
                key={p.id}
                project={p}
                edges={app.edges}
                incidents={app.incidents}
                onClick={() => app.setProject(p)}
              />
            ))}
          </div>
        </div>
      )}

      <CreateProjectModal open={createOpen} onClose={() => setCreateOpen(false)} />
    </div>
  )
}

function ProjectEmptyView({ onCreate }: { onCreate: () => void }) {
  return (
    <div className="flex flex-col items-center justify-center px-6 py-32 text-center">
      <div className="flex h-16 w-16 items-center justify-center rounded-2xl bg-brand-50 text-brand-500">
        <Icon name="layers" size={30} />
      </div>
      <h2 className="mt-4 text-[18px] font-semibold text-gray-900">프로젝트가 없습니다</h2>
      <p className="mt-1 max-w-sm text-sm text-gray-500">
        프로젝트는 팀의 데이터베이스와 파이프라인을 함께 관리하는 단위입니다.
      </p>
      <button
        onClick={onCreate}
        className="mt-5 flex items-center gap-1.5 rounded-md bg-brand-600 px-4 py-2.5 text-sm font-medium text-white hover:bg-brand-700"
      >
        <Icon name="plus" size={15} />
        Create a Project
      </button>
    </div>
  )
}

/* ---------------------------------------------------- Project Card */

function ProjectCard({
  project, edges, incidents, onClick,
}: {
  project: Project
  edges: Edge[]
  incidents: IncidentResponse[]
  onClick: () => void
}) {
  const pEdges = edges.filter((e) => project.pipelineIds.includes(e.id))
  const issueEdges = pEdges.filter((e) => e.status === 'lag' || e.status === 'error')
  // Pipelines·Active는 백엔드 workspace 응답의 카운트를 쓴다(#105). 목록 화면에서는 edges가
  // 로드되지 않아 전역 edges 기반 계산이 0이 되던 문제를 해소.
  const pipelineCount = project.pipelineCount
  const activeCount = project.activeCount
  const openInc = incidents.filter(
    (i) => i.tenantId === project.id && i.status.toUpperCase() !== 'RESOLVED',
  )
  const critInc = openInc.filter((i) => ['CRITICAL', 'ERROR'].includes(i.severity.toUpperCase()))
  const health =
    pEdges.some((e) => e.status === 'error') || critInc.length > 0
      ? 'error'
      : issueEdges.length > 0 || openInc.length > 0
        ? 'warning'
        : 'healthy'

  return (
    <button
      onClick={onClick}
      className="rounded-xl border border-gray-200 bg-white p-5 text-left transition-all hover:border-brand-300 hover:shadow-md"
    >
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="truncate text-[15px] font-semibold text-gray-900">{project.name}</div>
          <div className="font-mono text-[11px] text-gray-400">{project.slug}</div>
        </div>
        <HealthBadge health={health} />
      </div>
      <div className="mt-4 grid grid-cols-4 gap-2 border-t border-gray-100 pt-3">
        <HealthStat label="Pipelines" value={pipelineCount} />
        <HealthStat label="Active" value={activeCount} tone="good" />
        <HealthStat label="Issues" value={issueEdges.length} tone={issueEdges.length > 0 ? 'warn' : undefined} />
        <HealthStat label="Incidents" value={openInc.length} tone={openInc.length > 0 ? 'bad' : undefined} />
      </div>
      <div className="mt-3 text-[11px] text-gray-400">생성일 {project.createdAt}</div>
    </button>
  )
}

function HealthBadge({ health }: { health: 'healthy' | 'warning' | 'error' }) {
  if (health === 'error')
    return <span className="shrink-0 rounded-full bg-[#fcf3f2] px-2 py-0.5 text-[11px] font-semibold text-[#c0392b]">Error</span>
  if (health === 'warning')
    return <span className="shrink-0 rounded-full bg-[#ededed] px-2 py-0.5 text-[11px] font-semibold text-[#6b6b73]">Warning</span>
  return <span className="shrink-0 rounded-full bg-[#ededed] px-2 py-0.5 text-[11px] font-semibold text-[#6b6b73]">Healthy</span>
}

function HealthStat({ label, value, tone }: { label: string; value: number; tone?: 'good' | 'warn' | 'bad' }) {
  const color =
    tone === 'bad' && value > 0
      ? 'text-[#c0392b]'
      : tone === 'warn' && value > 0
        ? 'text-[#6b6b73]'
        : tone === 'good'
          ? 'text-[#6b6b73]'
          : 'text-gray-900'
  return (
    <div>
      <div className={cn('text-[17px] font-bold', color)}>{value}</div>
      <div className="text-[10.5px] text-gray-400">{label}</div>
    </div>
  )
}

/* ---------------------------------------------------------- CreateProject */

export function CreateProjectModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const app = useApp()
  const toast = useToast()
  const [name, setName] = useState('')
  const slug = name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '')

  const [busy, setBusy] = useState(false)

  async function handleCreate() {
    if (busy || !name.trim()) return
    setBusy(true)
    const created = await app.createProject(name.trim())
    setBusy(false)
    if (!created) {
      toast('프로젝트 생성에 실패했습니다')
      return
    }
    toast(`프로젝트 "${created.name}" 생성됨`)
    onClose()
    setName('')
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="New Project"
      subtitle="데이터베이스와 파이프라인을 하나의 프로젝트로 묶어 관리합니다"
      width={460}
      footer={
        <>
          <button
            onClick={onClose}
            className="rounded-md border border-gray-200 px-3 py-1.5 text-[13px] font-medium text-gray-600 hover:bg-gray-50"
          >
            Cancel
          </button>
          <button
            disabled={!name.trim() || busy}
            onClick={handleCreate}
            className="rounded-md bg-brand-600 px-3.5 py-1.5 text-[13px] font-semibold text-white hover:bg-brand-700 disabled:bg-brand-300"
          >
            {busy ? 'Creating…' : 'Create'}
          </button>
        </>
      }
    >
      <div className="space-y-4">
        <div>
          <label className="mb-1 block text-[12px] font-medium text-gray-600">프로젝트 이름</label>
          <input
            autoFocus
            value={name}
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && name.trim() && handleCreate()}
            placeholder="e.g. Commerce Platform"
            className="h-10 w-full rounded-md border border-gray-300 px-3 text-sm outline-none focus:border-brand-600 focus:ring-1 focus:ring-brand-600"
          />
          <div className="mt-2 flex items-center gap-1.5 text-[12px] text-gray-400">
            <Icon name="globe" size={13} />
            Slug:
            <span className="font-mono text-gray-600">{slug || 'project-slug'}</span>
          </div>
        </div>
      </div>
    </Modal>
  )
}
