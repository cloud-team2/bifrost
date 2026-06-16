import { useState } from 'react'
import { BrandMark } from '../BrandMark'
import { Icon, type IconName } from '../Icon'
import { useClickOutside } from '../ui'
import { useApp, type View } from '../../store/AppStore'
import { APP_VERSION } from '../../data/constants'
import { cn } from '../../lib/format'

interface NavItem {
  view: View
  label: string
  icon: IconName
  divider?: boolean
  badge?: number
}

/**
 * 좌측 메뉴(#784): 기본 접힘(아이콘만). 마우스를 올리면 펼쳐지고(오버레이 — 본문은
 * 밀리지 않음), 마우스를 떼면 다시 접힌다. 폭은 56px 스페이서로 항상 예약.
 */
export function Sidebar({ onCreateProject }: { onCreateProject: () => void }) {
  const app = useApp()
  const { currentUser, view, incidents } = app
  const [hovered, setHovered] = useState(false)
  const expanded = hovered
  const openIncidents = incidents.filter((i) => i.status.toUpperCase() !== 'RESOLVED').length

  const nav: NavItem[] = [
    { view: 'pipelines', label: '파이프라인', icon: 'route' },
    { view: 'databases', label: '데이터베이스', icon: 'database' },
    { view: 'cluster', label: '클러스터', icon: 'server' },
    { view: 'alerts', label: '인시던트', icon: 'bell', badge: openIncidents },
  ]

  const isActive = (v: View) =>
    view === v ||
    (v === 'pipelines' && view === 'pipeline-detail') ||
    (v === 'databases' && view === 'database-detail')

  return (
    <div className="relative w-14 shrink-0">
      <aside
        onMouseEnter={() => setHovered(true)}
        onMouseLeave={() => setHovered(false)}
        className={cn(
          'absolute left-0 top-0 z-40 flex h-full flex-col border-r border-[#ececec] bg-rail text-[#6b6b73] transition-[width] duration-200',
          expanded ? 'w-52 shadow-xl' : 'w-14',
        )}
      >
        {/* logo */}
        <div className={cn('flex items-center pt-4 pb-3', expanded ? 'gap-2 px-4' : 'justify-center px-3')}>
          <BrandMark size={expanded ? 28 : 24} />
          {expanded && (
            <span className="text-[17px] font-bold lowercase tracking-tight text-[#0d0d0d]">bifrost</span>
          )}
        </div>

        {expanded ? (
          <ProjectSwitcher onCreate={onCreateProject} />
        ) : (
          <div className="mx-2 mb-1 flex items-center justify-center rounded-md border border-[#ececec] bg-rail-hover py-2">
            <Icon name="layers" size={15} className="text-[#8a8a8a]" />
          </div>
        )}

        <nav className={cn('flex-1 py-3', expanded ? 'px-3' : 'px-2')}>
          {nav.map((item) => (
            <button
              key={item.view}
              onClick={() => app.setView(item.view)}
              title={!expanded ? item.label : undefined}
              className={cn(
                'relative flex w-full items-center rounded-md text-[12.5px] font-medium transition-colors',
                expanded ? 'gap-2.5 px-2.5 py-2' : 'justify-center py-2.5',
                isActive(item.view)
                  ? 'bg-[#0d0d0d] text-white'
                  : 'text-[#6b6b73] hover:bg-rail-hover hover:text-[#0d0d0d]',
              )}
            >
              <Icon name={item.icon} size={16} />
              {expanded && <span className="flex-1 text-left">{item.label}</span>}
              {item.badge ? (
                expanded ? (
                  <span className="flex h-4 min-w-4 items-center justify-center rounded-full bg-[#c0392b] px-1 text-[10px] font-bold text-white">
                    {item.badge}
                  </span>
                ) : (
                  <span className="absolute right-1.5 top-1.5 h-2 w-2 rounded-full bg-[#c0392b] ring-2 ring-white" />
                )
              ) : null}
            </button>
          ))}
        </nav>

        {/* user */}
        <button
          onClick={() => app.setView('settings')}
          title={!expanded ? currentUser?.name ?? '설정' : undefined}
          className={cn(
            'mb-2 flex items-center rounded-md transition-colors hover:bg-rail-hover',
            expanded ? 'mx-3 gap-2.5 px-2.5 py-2' : 'mx-2 justify-center py-2',
            view === 'settings' && 'bg-rail-hover',
          )}
        >
          <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-[#0d0d0d] text-[12px] font-semibold text-white">
            {currentUser?.initial}
          </div>
          {expanded && (
            <>
              <div className="min-w-0 flex-1 text-left">
                <div className="truncate text-[12px] font-medium text-[#0d0d0d]">{currentUser?.name}</div>
                <div className="truncate text-[10.5px] capitalize text-[#9a9a9a]">{currentUser?.role}</div>
              </div>
              <Icon name="settings" size={14} className="text-[#9a9a9a]" />
            </>
          )}
        </button>

        {expanded && (
          <div className="flex items-center gap-1.5 border-t border-[#ececec] px-4 py-2.5 text-[10.5px] text-[#9a9a9a]">
            <span className="h-1.5 w-1.5 rounded-full bg-[#c8c8c8]" />
            연결됨
            <span className="ml-auto font-mono">{APP_VERSION}</span>
          </div>
        )}
        <input type="hidden" value={app.currentProject?.id ?? ''} readOnly />
      </aside>
    </div>
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
        className="flex w-full items-center gap-2 rounded-md border border-[#ececec] bg-rail-hover px-2.5 py-2 text-left hover:border-[#d9d9d9]"
      >
        <Icon name="layers" size={15} className="text-[#8a8a8a]" />
        <span className="flex-1 truncate text-[12.5px] font-semibold text-[#0d0d0d]">
          {app.currentProject?.name ?? '프로젝트 선택'}
        </span>
        <Icon name="chevron-down" size={14} className="text-[#9a9a9a]" />
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
