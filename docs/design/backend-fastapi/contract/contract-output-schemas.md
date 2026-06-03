# Contract — Output Schemas (§17)

> FastAPI Agent 계약 · 개요 [overview](../overview.md) · 원리 [agent-principles](../agent-principles.md). **계약**: [agent-roles](./contract-agent-roles.md) · [state-schema](./contract-state-schema.md) · [workflow-control](./contract-workflow-control.md) · [streaming-events](./contract-streaming-events.md) · [output-schemas](./contract-output-schemas.md)

## 17. Contract: Output Schemas


### 1. 목적

이 문서는 주요 Agent의 구조화 출력 schema를 정의한다. 실제 구현에서는 Pydantic, JSON Schema, 또는 equivalent schema validation을 적용한다.

### 2. Router Output

```json
{
  "route_decision": {
    "mode": "incident_analysis",
    "remediation_requested": false,
    "reuse_existing_analysis": false,
    "reason": "user asked for the cause only",
    "required_flow": ["router", "correlation", "planner", "retrieval", "classifier", "rca", "verifier", "report"]
  }
}
```

허용 mode:

- `simple_query`
- `incident_analysis`
- `action_execution`
- `approval_decision`

Router는 매 사용자 메시지마다 mode를 재판정한다. `incident_analysis`는 기본 `diagnose_only`이며, `remediation_requested=true`일 때만 Remediation·Policy Guard와 실행 단계가 `required_flow`에 추가된다. `reuse_existing_analysis=true`인 후속 turn은 기존 State(`analysis`, action 후보)를 재사용하고 Retrieval·Classifier·RCA를 다시 실행하지 않는다.

`approval_or_change_gate`와 `executor`는 실행 가능한 action이 있을 때만 활성화된다. action이 없거나 정책상 deny면 Verifier와 Report로 진행한다.

### 3. Planner Output

```json
{
  "retrieval_plan": [
    {
      "step_id": "plan_001",
      "tool_name": "get_pipeline_logs",
      "purpose": "extract task timeout evidence",
      "required": true
    },
    {
      "step_id": "plan_002",
      "tool_name": "get_metrics",
      "purpose": "source connection timeout metric",
      "required": true
    }
  ]
}
```

### 4. Retrieval Output

```json
{
  "evidence_items": [
    {
      "evidence_id": "ev_log_001",
      "type": "pipeline_log",
      "store_ref": "evidence://run_001/ev_log_001",
      "summary": "daily_user_sync extract_users task에서 source connection timeout 증가",
      "redaction_status": "redacted",
      "collected_at": "2026-06-01T00:15:00Z"
    }
  ]
}
```

Retrieval output에는 raw `content`를 넣지 않는다.

### 5. Classifier Output

```json
{
  "classification": {
    "incident_scope": "single",
    "incident_types": [
      {
        "type": "SOURCE_CONNECTION_TIMEOUT",
        "confidence": 0.78,
        "evidence_ids": ["ev_log_001", "ev_metric_001"]
      }
    ],
    "needs_incident_group_analysis": false
  }
}
```

### 6. RCA Output

```json
{
  "root_cause_candidates": [
    {
      "root_cause_id": "SOURCE_DB_CONNECTION_TIMEOUT",
      "confidence": 0.82,
      "required_evidence_satisfied": true,
      "supporting_evidence_ids": ["ev_log_001", "ev_metric_001"],
      "negative_evidence_ids": [],
      "evidence_gap": [],
      "explanation": "source timeout metric and extract task timeout logs increased in the same window"
    }
  ]
}
```

### 7. Remediation Output

```json
{
  "action_candidates": [
    {
      "action_id": "act_001",
      "action_type": "runtime_tool",
      "action_name": "restart_connector_task",
      "tool_name": "restart_connector_task",
      "root_cause_id": "CONNECTOR_TASK_FAILED",
      "risk": "medium",
      "reason": "connector task is FAILED and no schema/config regression evidence found",
      "expected_effect": "clear transient task failure",
      "rollback_plan": "pause connector if task fails again",
      "estimated_duration": "약 30초"
    }
  ]
}
```

`action_type`은 다음 중 하나다.

| action_type | 의미 | `tool_name` |
| --- | --- | --- |
| `runtime_tool` | Spring Boot Operations API로 실행되는 tool | 필수 |
| `workflow_action` | approval, 추가 evidence 계획, report 상태 변경 등 FastAPI 내부 action | 선택 |
| `composite_action` | 여러 runtime tool 후보로 분해해야 하는 조치 의도 | 선택 |
| `notification` | 운영자 알림 | 선택 |
| `escalation` | 고객사/플랫폼/운영자에게 evidence 전달 | 선택 |

### 8. Policy Guard Output

```json
{
  "policy_decisions": [
    {
      "action_id": "act_001",
      "action_type": "runtime_tool",
      "tool_name": "restart_connector_task",
      "risk": "medium",
      "decision": "require_approval",
      "reason": "connector task restart changes runtime state",
      "required_approver": "project_operator"
    }
  ]
}
```

Policy Guard는 승인 완료를 기록하지 않는다. 승인 결과는 approval gate가 State에 기록한다.

### 9. Executor Output

```json
{
  "execution_results": [
    {
      "action_id": "act_001",
      "tool_name": "restart_connector_task",
      "status": "accepted",
      "audit_event_id": "audit_001",
      "before_evidence_id": "ev_before_001",
      "after_evidence_id": "ev_after_001",
      "summary": "connector task restart requested"
    }
  ]
}
```

### 10. Verifier Output

```json
{
  "verification_results": [
    {
      "verification_id": "ver_001",
      "target": "root_cause",
      "status": "pass",
      "approved_for_final_response": true,
      "reason": "required evidence is present and no negative evidence overrides it"
    }
  ]
}
```

Status enum:

- `pass`
- `fail`
- `needs_revision`

### 11. Report Output

```json
{
  "final_response": {
    "incident_id": "inc_001",
    "summary": "source DB connection timeout 가능성이 가장 높습니다",
    "root_cause_id": "SOURCE_DB_CONNECTION_TIMEOUT",
    "confidence": 0.82,
    "evidence": [
      {
        "evidence_id": "ev_log_001",
        "summary": "extract task timeout log"
      }
    ],
    "actions": [
      {
        "action_id": "act_001",
        "risk": "medium",
        "estimated_duration": "약 30초",
        "status": "approval_required"
      }
    ],
    "limitations": [
      "고객사 DB 내부 상태는 직접 확인하지 않았습니다"
    ]
  }
}
```

### 12. Validation 실패 처리

| 실패 | 처리 |
| --- | --- |
| required field 누락 | 1회 schema repair |
| catalog에 없는 root cause | RCA 재실행 또는 unknown 처리 |
| catalog에 없는 `runtime_tool` | Policy deny |
| `workflow_action`/`composite_action`/`notification`/`escalation`인데 action catalog에 없음 | Remediation output reject |
| raw evidence inline 포함 | Retrieval output reject |
| verifier approval 없는 report | Report reject |
