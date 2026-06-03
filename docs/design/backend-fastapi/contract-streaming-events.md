# Contract — Streaming Events (§16)

> FastAPI Agent 계약 · 개요 [overview](./overview.md) · 원리 [agent-principles](./agent-principles.md). **계약**: [agent-roles](./contract-agent-roles.md) · [state-schema](./contract-state-schema.md) · [workflow-control](./contract-workflow-control.md) · [streaming-events](./contract-streaming-events.md) · [output-schemas](./contract-output-schemas.md)

## 16. Contract: Streaming Events


### 1. 목적

이 문서는 Agent 진행 상태를 Frontend에 전달하기 위한 이벤트 계약을 정의한다.

LLM token streaming과 Agent progress streaming은 다르다. Token streaming은 모델 응답 조각이고, progress streaming은 애플리케이션이 생성하는 workflow 상태 이벤트다.

### 2. 전송 방식

v1은 SSE를 기본으로 한다.

| 상황 | 방식 |
| --- | --- |
| 분석 진행 표시 | SSE |
| tool call 시작·완료 | SSE |
| approval required 표시 | SSE |
| 사용자의 승인·거절 | REST |
| 중단, 재시도, 양방향 제어 | 추후 WebSocket |

### 3. 공통 Event Envelope

```json
{
  "event_id": "evt_001",
  "run_id": "run_001",
  "timestamp": "2026-06-01T00:15:00Z",
  "type": "tool_call_completed",
  "agent": "Retrieval",
  "message": "pipeline log evidence 저장 완료",
  "payload": {}
}
```

### 4. Event Types

| Type | 설명 | 사용자 노출 |
| --- | --- | --- |
| `run_started` | run 시작 | yes |
| `agent_started` | Agent 단계 시작 | yes |
| `agent_completed` | Agent 단계 완료 | yes |
| `tool_call_started` | tool 호출 시작 | yes |
| `tool_call_completed` | tool 호출 완료 | yes |
| `tool_call_failed` | tool 호출 실패 | yes |
| `evidence_collected` | evidence metadata 추가 | yes |
| `report_preview_available` | Verifier 통과 전 중간 RCA preview 사용 가능([§4.2](contract-workflow-control.md#42-지연-최소화latency-원칙), `report/preview`). "검증 전" 표시 | yes |
| `partial_result` | 부분 결과(단계 완료 시점의 중간 결론·진행 요약) | yes |
| `approval_required` | 승인 필요 | yes |
| `change_management_required` | 변경관리 필요 | yes |
| `execution_started` | action 실행 시작 | yes |
| `execution_completed` | action 실행 완료 | yes |
| `verification_completed` | 검증 완료 | yes |
| `run_completed` | 최종 완료 | yes |
| `debug_trace` | 내부 debug | no |

### 5. 예시

#### 5.1 Retrieval 시작

```text
event: agent_started
data: {"run_id":"run_001","agent":"Retrieval","message":"근거 수집을 시작했습니다"}
```

#### 5.2 Source metric 수집

```text
event: tool_call_completed
data: {"run_id":"run_001","agent":"Retrieval","tool":"get_metrics","message":"source DB timeout 지표를 수집했습니다","evidence_id":"ev_metric_001"}
```

#### 5.3 RCA 완료

```text
event: agent_completed
data: {"run_id":"run_001","agent":"RCA","message":"SOURCE_DB_CONNECTION_TIMEOUT 후보를 검증했습니다","confidence":0.82}
```

#### 5.4 승인 필요

```text
event: approval_required
data: {"run_id":"run_001","action_id":"act_001","message":"connector task restart는 승인 후 실행할 수 있습니다"}
```

### 6. 노출 금지

다음은 streaming event에 포함하지 않는다.

- raw log 전문
- secret, token, connection string
- 고객사 개인정보
- internal prompt
- LLM hidden reasoning
- stack trace 전문
- 승인되지 않은 조치 실행 URL

### 7. 사용자 표시 원칙

사용자에게는 “무엇을 하고 있는지”와 “왜 기다리는지”를 보여준다.

좋은 메시지:

- `source connection timeout 지표를 확인하고 있습니다`
- `pipeline extract task 로그를 evidence로 저장했습니다`
- `조치 실행 전 승인이 필요합니다`

피해야 할 메시지:

- 내부 prompt 또는 chain-of-thought
- raw exception 전문
- API key 또는 endpoint secret

### 8. 상태 요약 예시

```text
● Router: 완료
● Retrieval: source metric과 pipeline log 수집 완료
● Classifier: SOURCE_CONNECTION_TIMEOUT 유형 분류
● RCA: SOURCE_DB_CONNECTION_TIMEOUT 후보 검증 중
○ Remediation: 대기 중
○ Policy Guard: 대기 중
○ Executor: 대기 중
```

---

