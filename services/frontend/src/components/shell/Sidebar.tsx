import { useState } from 'react'
import { BrandMark } from '../BrandMark'
import { Icon, type IconName } from '../Icon'
import { useClickOutside } from '../ui'
import { useApp, type View } from '../../store/AppStore'
import { APP_VERSION } from '../../data/mock'
import { cn } from '../../lib/format'

interface NavItem {
  view: View
  label: string
  icon: IconName
  divider?: boolean
  badge?: number
}

export function Sidebar({ onCreateProject }: { onCreateProject: () => void }) {
  const app = useApp()
  const { currentUser, view, incidents } = app
  const openIncidents = incidents.filter((i) => i.status.toUpperCase() !== 'RESOLVED').length

  const nav: NavItem[] = [
    { view: 'pipelines', label: 'Pipeline', icon: 'route' },
    { view: 'databases', label: 'Database', icon: 'database' },
    { view: 'cluster', label: '클러스터', icon: 'server' },
    { view: 'activity-log', label: '이벤트 로그', icon: 'log' },
    { view: 'alerts', label: '인시던트', icon: 'bell', badge: openIncidents },
  ]

  const isActive = (v: View) =>
    view === v ||
    (v === 'pipelines' && view === 'pipeline-detail') ||
    (v === 'databases' && view === 'database-detail')

  return (
    <aside className="flex w-52 shrink-0 flex-col bg-rail text-gray-300">
      {/* logo */}
      <div className="flex items-center gap-2 px-4 pt-4 pb-3">
        <BrandMark size={28} />
        <span className="text-[17px] font-bold lowercase tracking-tight text-white">bifrost</span>
      </div>

      <ProjectSwitcher onCreate={onCreateProject} />

      <nav className="flex-1 px-3 py-3">
        {nav.map((item) => (
          <div key={item.view}>
            {item.divider && <div className="my-2 border-t border-white/8" />}
            <button
              onClick={() => app.setView(item.view)}
              className={cn(
                'flex w-full items-center gap-2.5 rounded-md px-2.5 py-2 text-[12.5px] font-medium transition-colors',
                isActive(item.view)
                  ? 'bg-brand-600 text-white'
                  : 'text-gray-400 hover:bg-rail-hover hover:text-gray-100',
              )}
            >
              <Icon name={item.icon} size={16} />
              <span className="flex-1 text-left">{item.label}</span>
              {item.badge ? (
                <span className="flex h-4 min-w-4 items-center justify-center rounded-full bg-rose-500 px-1 text-[10px] font-bold text-white">
                  {item.badge}
                </span>
              ) : null}
            </button>
          </div>
        ))}
      </nav>

      {/* user */}
      <button
        onClick={() => app.setView('settings')}
        className={cn(
          'mx-3 mb-2 flex items-center gap-2.5 rounded-md px-2.5 py-2 transition-colors hover:bg-rail-hover',
          view === 'settings' && 'bg-rail-hover',
        )}
      >
        <div className="flex h-7 w-7 items-center justify-center rounded-full bg-gradient-to-br from-sky-400 to-indigo-500 text-[12px] font-semibold text-white">
          {currentUser?.initial}
        </div>
        <div className="min-w-0 flex-1 text-left">
          <div className="truncate text-[12px] font-medium text-white">{currentUser?.name}</div>
          <div className="truncate text-[10.5px] capitalize text-gray-500">{currentUser?.role}</div>
        </div>
        <Icon name="settings" size={14} className="text-gray-500" />
      </button>

      <div className="flex items-center gap-1.5 border-t border-white/8 px-4 py-2.5 text-[10.5px] text-gray-500">
        <span className="h-1.5 w-1.5 rounded-full bg-emerald-400" />
        연결됨
        <span className="ml-auto font-mono">{APP_VERSION}</span>
      </div>
      <input type="hidden" value={app.currentProject?.id ?? ''} readOnly />
    </aside>
  )
}

function ProjectSwitcher({ onCreate }: { onCreate: () => void }) {
  const app = useApp()
  const [open, setOpen] = useState(false)
  const ref = useClickOutside<HTMLDivElement>(() => setOpen(false))

  return (
    <div className="relative px-3 pb-1" ref={ref}>
      <button
        onClick={() => setOpen((o) => !o)}
        className="flex w-full items-center gap-2 rounded-md border border-white/10 bg-rail-hover px-2.5 py-2 text-left hover:border-white/20"
      >
        <Icon name="layers" size={15} className="text-brand-400" />
        <span className="flex-1 truncate text-[12.5px] font-semibold text-white">
          {app.currentProject?.name ?? '프로젝트 선택'}
        </span>
        <Icon name="chevron-down" size={14} className="text-gray-500" />
      </button>

      {open && (
        <div className="bifrost-fade absolute left-3 right-3 z-40 mt-1 overflow-hidden rounded-lg border border-gray-200 bg-white py-1 text-gray-700 shadow-xl">
          {app.visibleProjects.map((p) => (
            <button
              key={p.id}
              onClick={() => {
                app.setProject(p)
                setOpen(false)
              }}
              className="flex w-full items-center gap-2 px-3 py-2 text-left text-[12.5px] hover:bg-gray-50"
            >
              <span className="flex-1 truncate">{p.name}</span>
              {app.currentProject?.id === p.id && (
                <span className="rounded bg-brand-100 px-1.5 py-0.5 text-[10px] font-semibold text-brand-700">
                  현재
                </span>
              )}
            </button>
          ))}
          <div className="my-1 border-t border-gray-100" />
          <button
            onClick={() => {
              setOpen(false)
              onCreate()
            }}
            className="flex w-full items-center gap-2 px-3 py-2 text-left text-[12.5px] font-medium text-brand-600 hover:bg-gray-50"
          >
            <Icon name="plus" size={14} />
            새 프로젝트
          </button>
        </div>
      )}
    </div>
  )
}
