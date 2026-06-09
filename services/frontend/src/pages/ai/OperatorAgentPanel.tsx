import { AgentRunPanel } from './AgentRunPanel'

const QUICK = ['브로커 파티션 리밸런싱', '커넥터 재시작', 'fraud-detector 지연 분석']

export function OperatorAgentPanel() {
  return (
    <AgentRunPanel
      title="운영 AI 에이전트"
      subtitle="HITL · 실행 전 승인 필요"
      icon="shield"
      accent="violet"
      hitlLabel="HITL"
      quickActions={QUICK}
      inputPlaceholder="작업을 설명하세요…"
      runningPlaceholder="Agent Run 실행 중…"
      initialMessage="운영 AI 에이전트입니다. 입력한 작업은 실제 Agent Run으로 생성되고, 진행 상황은 SSE 이벤트로 표시됩니다. 승인 필요한 조치는 OWNER/ADMIN 승인 후 반영됩니다."
    />
  )
}
