import { AgentRunPanel } from './AgentRunPanel'
import { useApp } from '../../store/AppStore'

export function BifrostAgent({ viewLabel: _viewLabel }: { viewLabel: string }) {
  const app = useApp()
  return (
    <AgentRunPanel
      title="Bifrost Agent"
      subtitle={app.currentProject?.name ?? '—'}
      icon="zap"
      accent="brand"
      slashCommands
      multiThread
      inputPlaceholder="에이전트에게 물어보세요…"
      runningPlaceholder="Agent Run 실행 중…"
    />
  )
}
