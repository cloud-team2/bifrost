import { AgentRunPanel } from './AgentRunPanel'
import { useApp } from '../../store/AppStore'

const QUICK = ['파이프라인 상태 확인', 'Consumer Group lag 분석', 'Connector 상태 조회', '이벤트 로그 분석']

export function BifrostAgent({ viewLabel: _viewLabel }: { viewLabel: string }) {
  const app = useApp()
  return (
    <AgentRunPanel
      title="Bifrost Agent"
      subtitle={app.currentProject?.name ?? '—'}
      icon="zap"
      accent="brand"
      quickActions={QUICK}
      inputPlaceholder="에이전트에게 물어보세요…"
      runningPlaceholder="Agent Run 실행 중…"
      initialMessage="질문이나 운영 조치를 입력하면 FastAPI Agent Run을 생성하고 SSE 이벤트를 실시간으로 표시합니다. Tool 결과, Evidence, Report preview, 승인 요청은 수신된 이벤트 그대로 카드로 렌더링됩니다."
    />
  )
}
