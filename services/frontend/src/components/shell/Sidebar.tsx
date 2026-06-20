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
 * 좌측 메뉴(#784): 기본 접힘(아이콘만). 좌상단 토글 버튼으로 수동으로 펼치고/접는다.
 * 펼치면 스페이서 폭(56→208px)이 함께 늘어 우측 본문이 그만큼 밀리고, 접으면 복귀한다.
 * 토글은 펼침·접힘 모두 같은 위치(레일 좌상단 56×56).
 */
export function Sidebar({ onCreateProject }: { onCreateProject: () => void }) {
  const app = useApp()
  const { currentUser, view, incidents } = app
  const [expanded, setExpanded] = useState(false)
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
    <div className={cn('relative shrink-0 transition-[width] duration-200', expanded ? 'w-52' : 'w-14')}>
      <aside
        className={cn(
          'bifrost-rail absolute left-0 top-0 z-40 flex h-full flex-col text-[#878da3] transition-[width] duration-200',
          expanded ? 'w-52' : 'w-14',
        )}
      >
        {/* 토글(펼침·접힘 동일 위치) + 브랜드 */}
        <div className="relative flex h-14 shrink-0 items-center">
          <button
            onClick={() => setExpanded((o) => !o)}
            title={expanded ? '메뉴 접기' : '메뉴 펼치기'}
            className="flex h-14 w-14 shrink-0 items-center justify-center text-[#878da3] transition-colors hover:text-white"
          >
            <Icon name="panel" size={18} />
          </button>
          {expanded && (
            <div className="flex items-center gap-2">
              <BrandMark size={22} />
              <span className="font-display text-[17px] font-bold lowercase tracking-tight text-white">bifrost</span>
            </div>
          )}
        </div>

        {expanded ? (
          <ProjectSwitcher onCreate={onCreateProject} />
        ) : (
          <div className="relative mx-2 mb-1 flex items-center justify-center rounded-md border border-[#1b1f2e] bg-white/[0.03] py-2">
            <Icon name="layers" size={15} className="text-[#878da3]" />
          </div>
        )}

        <nav className={cn('relative flex-1 py-3', expanded ? 'px-3' : 'px-2')}>
          {nav.map((item) => {
            const active = isActive(item.view)
            return (
              <button
                key={item.view}
                onClick={() => app.setView(item.view)}
                title={!expanded ? item.label : undefined}
                className={cn(
                  'relative flex w-full items-center rounded-md text-[12.5px] font-medium transition-colors',
                  expanded ? 'gap-2.5 px-2.5 py-2' : 'justify-center py-2.5',
                  active
                    ? 'bg-white/[0.07] text-white'
                    : 'text-[#878da3] hover:bg-white/5 hover:text-white',
                )}
              >
                {active && (
                  <span className="bifrost-spectrum pointer-events-none absolute bottom-1.5 left-0 top-1.5 w-[3px] rounded-full" />
                )}
                <Icon name={item.icon} size={16} />
                {expanded && <span className="flex-1 text-left">{item.label}</span>}
                {item.badge ? (
                  expanded ? (
                    <span className="flex h-4 min-w-4 items-center justify-center rounded-full bg-[#c0392b] px-1 text-[10px] font-bold text-white">
                      {item.badge}
                    </span>
                  ) : (
                    <span className="absolute right-1.5 top-1.5 h-2 w-2 rounded-full bg-[#c0392b] ring-2 ring-[#0e1019]" />
                  )
                ) : null}
              </button>
            )
          })}
        </nav>

        {/* user */}
        <button
          onClick={() => app.setView('settings')}
          title={!expanded ? currentUser?.name ?? '설정' : undefined}
          className={cn(
            'mb-2 flex items-center rounded-md transition-colors hover:bg-white/5',
            expanded ? 'mx-3 gap-2.5 px-2.5 py-2' : 'mx-2 justify-center py-2',
            view === 'settings' && 'bg-white/[0.06]',
          )}
        >
          <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-[#191d2b] text-[12px] font-semibold text-white ring-1 ring-white/10">
            {currentUser?.initial}
          </div>
          {expanded && (
            <>
              <div className="min-w-0 flex-1 text-left">
                <div className="truncate text-[12px] font-medium text-white">{currentUser?.name}</div>
                <div className="truncate text-[10.5px] capitalize text-[#878da3]">{currentUser?.role}</div>
              </div>
              <Icon name="settings" size={14} className="text-[#878da3]" />
            </>
          )}
        </button>

        {expanded && (
          <div className="flex items-center gap-1.5 border-t border-[#1b1f2e] px-4 py-2.5 text-[10.5px] text-[#6b6f84]">
            <span className="h-1.5 w-1.5 rounded-full bg-[#36d399]" />
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
        className="flex w-full items-center gap-2 rounded-md border border-[#1b1f2e] bg-white/[0.03] px-2.5 py-2 text-left transition-colors hover:border-white/20"
      >
        <Icon name="layers" size={15} className="text-[#878da3]" />
        <span className="flex-1 truncate text-[12.5px] font-semibold text-white">
          {app.currentProject?.name ?? '프로젝트 선택'}
        </span>
        <Icon name="chevron-down" size={14} className="text-[#878da3]" />
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
