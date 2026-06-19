import { Icon } from '../Icon'
import { useApp } from '../../store/AppStore'

export function Header({ viewLabel }: { viewLabel: string }) {
  const app = useApp()
  const openIncidents = app.incidents.filter((i) => i.status.toUpperCase() !== 'RESOLVED').length

  return (
    <header className="bifrost-header-glow flex h-11 shrink-0 items-center gap-3 overflow-hidden border-b border-gray-200 bg-white px-4">
      <span className="font-mono text-[12px] text-gray-400">
        {app.currentProject?.slug ?? 'bifrost'}
        <span className="mx-1 text-gray-300">/</span>
        <span className="text-gray-700">{viewLabel}</span>
      </span>

      <div className="flex-1" />

      {openIncidents > 0 && (
        <button
          onClick={() => app.setView('alerts')}
          className="flex items-center gap-1.5 rounded-md border border-[#c0392b] bg-[#c0392b] px-2.5 py-1 text-[11.5px] font-semibold text-white hover:bg-[#a93226]"
        >
          <Icon name="alert" size={13} />
          {openIncidents} open incident{openIncidents > 1 ? 's' : ''}
        </button>
      )}

      <button
        onClick={app.logout}
        className="flex items-center gap-1.5 rounded-md px-2 py-1 text-[12px] text-gray-500 hover:bg-gray-100 hover:text-gray-700"
      >
        <Icon name="logout" size={14} />
        Sign out
      </button>
    </header>
  )
}
