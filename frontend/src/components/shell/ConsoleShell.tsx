import { useState, type ReactNode } from 'react'
import { Sidebar } from './Sidebar'
import { Header } from './Header'
import { StatusBar } from './StatusBar'
import { BifrostAgent } from '../../views/ai/BifrostAgent'
import { CreateProjectModal } from '../../views/Projects'
import { useApp } from '../../store/AppStore'
import { Icon } from '../Icon'
import { cn } from '../../lib/format'

export function ConsoleShell({ viewLabel, children }: { viewLabel: string; children: ReactNode }) {
  const app = useApp()
  const [projModal, setProjModal] = useState(false)

  return (
    <div className="flex h-screen overflow-hidden">
      <Sidebar onCreateProject={() => setProjModal(true)} />

      <div className="flex min-w-0 flex-1 flex-col overflow-hidden">
        <Header viewLabel={viewLabel} />

        <div className="flex flex-1 overflow-hidden">
          <main className="flex-1 overflow-y-auto scroll-thin bg-zinc-50">{children}</main>

          {/* AI drawer — always mounted, width animates open/closed */}
          <div
            className={cn(
              'flex shrink-0 overflow-hidden border-l border-gray-200 transition-[width] duration-200 ease-in-out',
              app.aiPanelOpen ? 'w-[388px]' : 'w-7',
            )}
          >
            {/* Toggle tab — always visible */}
            <button
              onClick={() => app.setAIPanel(!app.aiPanelOpen)}
              title={app.aiPanelOpen ? 'AI 패널 접기' : 'AI 패널 펼치기'}
              className="flex w-7 shrink-0 flex-col items-center justify-center gap-2 bg-white hover:bg-gray-50"
            >
              {!app.aiPanelOpen && (
                <div className="flex h-6 w-5 items-center justify-center rounded bg-gradient-to-br from-brand-500 to-violet-600">
                  <Icon name="zap" size={11} className="text-white" />
                </div>
              )}
              <Icon
                name={app.aiPanelOpen ? 'chevron-right' : 'chevron-left'}
                size={13}
                className="text-gray-400"
              />
            </button>

            {/* Panel body — 360px, clipped when closed */}
            <div className="flex w-[360px] shrink-0 flex-col border-l border-gray-100 bg-white">
              <BifrostAgent viewLabel={viewLabel} />
            </div>
          </div>
        </div>

        <StatusBar />
      </div>

      <CreateProjectModal open={projModal} onClose={() => setProjModal(false)} />
    </div>
  )
}
