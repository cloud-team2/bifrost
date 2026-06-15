import { useApp } from '../../store/AppStore'

export function StatusBar() {
  const app = useApp()
  return (
    <div className="flex h-6 shrink-0 items-center gap-2 border-t border-gray-200 bg-white px-4 font-mono text-[10.5px] text-gray-400">
      <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-[#c8c8c8]" />
      <span>Realtime · refreshes every 5s</span>
      <div className="flex-1" />
      <span>
        {app.currentProject?.slug ?? 'all-projects'} · cluster: bifrost-primary
      </span>
    </div>
  )
}
