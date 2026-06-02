import { useState } from 'react'
import { Icon } from '../../components/Icon'
import { TechIcon, nodeKind } from '../../components/TechIcon'
import { PageHead, StatusBadge } from '../../components/blocks'
import { useApp } from '../../store/AppStore'
import { AddDatabaseModal } from '../modals/AddDatabaseModal'
import { nodeName } from '../../data/helpers'

export function Databases() {
  const app = useApp()
  const [query, setQuery] = useState('')
  const [modal, setModal] = useState(false)

  const dbs = app.nodes
    .filter((n) => n.type === 'database' && app.currentProject?.dbIds.includes(n.id))
    .filter((n) => nodeName(n).toLowerCase().includes(query.toLowerCase()))

  return (
    <div className="px-6 py-5">
      <PageHead
        title="Databases"
        actions={
          <button
            onClick={() => setModal(true)}
            className="flex items-center gap-1.5 rounded-md bg-brand-600 px-3.5 py-2 text-sm font-medium text-white hover:bg-brand-700"
          >
            <Icon name="plus" size={15} />
            Database 등록
          </button>
        }
      />

      <div className="mt-4 relative w-72">
        <Icon name="search" size={15} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Database 검색"
          className="h-9 w-full rounded-md border border-gray-200 bg-white pl-9 pr-3 text-sm outline-none focus:border-brand-400"
        />
      </div>

      <div className="mt-4 space-y-2.5">
        {dbs.map((d) => (
          <button
            key={d.id}
            onClick={() => app.openDatabase(d.id)}
            className="flex w-full items-center gap-4 rounded-xl border border-gray-200 bg-white px-5 py-3.5 text-left transition-shadow hover:shadow-[0_2px_14px_rgba(0,0,0,0.07)]"
          >
            <TechIcon kind={nodeKind(d)} size={44} />
            <div className="min-w-0">
              <div className="flex items-center gap-2">
                <span className="text-[14px] font-semibold text-gray-900">{nodeName(d)}</span>
                <span className="rounded bg-gray-100 px-1.5 py-0.5 text-[10px] font-medium text-gray-500">
                  {d.techLabel}
                </span>
              </div>
              <div className="mt-0.5 truncate font-mono text-[11.5px] text-gray-400">{d.host}</div>
            </div>
            <div className="flex-1" />
            <StatusBadge status={d.status} label={d.status === 'healthy' ? 'connected' : d.status} />
          </button>
        ))}
        {dbs.length === 0 && (
          <div className="rounded-xl border border-dashed border-gray-200 py-16 text-center text-sm text-gray-400">
            검색 결과가 없습니다.
          </div>
        )}
      </div>

      <AddDatabaseModal open={modal} onClose={() => setModal(false)} />
    </div>
  )
}
