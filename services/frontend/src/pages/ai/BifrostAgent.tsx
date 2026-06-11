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
      inputPlaceholder="에이전트에게 물어보세요…"
      runningPlaceholder="Agent Run 실행 중…"
      initialMessage="질문이나 운영 조치를 입력하면 Agent Run을 생성하고 진행 상황과 최종 답변을 실시간으로 표시합니다. 승인 필요한 조치는 승인 절차로 이어집니다."
    />
  )
}
