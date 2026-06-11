# Contract — State Schema (§14)

> FastAPI Agent 계약 · 개요 [overview](../overview.md) · 원리 [agent-principles](../agent-principles.md). **계약**: [agent-roles](./contract-agent-roles.md) · [state-schema](./contract-state-schema.md) · [workflow-control](./contract-workflow-control.md) · [streaming-events](./contract-streaming-events.md) · [output-schemas](./contract-output-schemas.md)

## 14. Contract: State Schema


### 1. 목적

State는 Agent workflow의 단일 공유 컨텍스트다. 이 문서는 State namespace, 소유권, patch 규칙을 정의한다.

State를 namespace로 나누는 이유는 Agent별 책임을 분리하고, 서로의 결론을 임의로 덮어쓰지 못하게 하기 위해서다.

### 2. Namespace 목록

| Namespace | 소유자 | 내용 |
| --- | --- | --- |
| `run` | Supervisor (status), Planner (`run.plan`) | run id, status, current step, retry, `run.plan`(retrieval_plan) |
| `incident` | Classifier (현재 Router는 쓰지 않음) | incident id, severity, scope |
| `correlation` | Correlation Engine | alert group, common root cause 후보 (Classifier는 hydration으로 읽기만, 쓰기 권한 없음 — [§6](../catalog/catalog-failure-types.md#6-catalog-failure-types)) |
| `evidence` | Retrieval | evidence metadata와 store reference |
| `analysis` | Classifier / RCA | incident type, root cause 후보, confidence |
| `actions` | Remediation / Policy / Executor | action 후보, policy decision, execution result |
| `verification` | Verifier | pass/fail/needs_revision, 승인된 report 범위 |
| `report` | Report | draft와 final response |

> 현재 `RouteDecision`에는 `incident_id`/scope field가 없고 runner도 Router 결과로 `incident` namespace를 쓰지 않는다. `incident` namespace 초기화·정련은 Classifier/RCA 흐름에서 다룬다.

### 3. State Skeleton

```json
{
  "run": {
    "run_id": "run_001",
    "status": "running",
    "current_agent": "Retrieval",
    "retry_count": 0,
    "step_count": 6,
    "guards": {
      "revision_counts": { "root_cause": 0, "action": 0, "report": 0 },
      "gap_loops": 0,
      "scope_loops": 0,
      "revise_action_loops": 0,
      "fail_loops": 0
    },
    "plan": { "executed_plan_hashes": [] }
  },
  "incident": {
    "incident_id": "inc_001",
    "scope": "single",
    "severity": "CRITICAL"
  },
  "correlation": {
    "correlation_id": "corr_001",
    "related_alert_ids": []
  },
  "evidence": {
    "items": []
  },
  "analysis": {
    "incident_types": [],
    "root_cause_candidates": []
  },
  "actions": {
    "candidates": [],
    "policy_decisions": [],
    "approval_requests": [],
    "approved_actions": [],
    "change_management_records": [],
    "execution_results": []
  },
  "verification": {
    "verification_results": []
  },
  "report": {
    "draft": null,
    "final": null
  }
}
```

### 4. Evidence Item

State에는 raw evidence를 넣지 않는다. 현재 runner의 `_evidence_patch`는 `evidence_id`, `type`, `store_ref`, `summary`, `redaction_status`만 기록한다.

```json
{
  "evidence_id": "ev_log_001",
  "type": "pipeline_log",
  "store_ref": "evidence://run_001/ev_log_001",
  "summary": "extract_users task에서 source connection timeout 증가",
  "redaction_status": "redacted"
}
```

### 5. Patch 규칙

모든 State 변경은 patch로 기록한다.

```json
{
  "patch_id": "patch_001",
  "run_id": "run_001",
  "agent": "Retrieval",
  "namespace": "evidence",
  "operation": "append",
  "path": "/evidence/items",
  "value_ref": "ev_log_001",
  "created_at": "2026-06-01T00:15:00Z"
}
```

규칙:

1. append-only를 기본으로 한다.
2. 수정이 필요하면 새 version을 추가한다.
3. 삭제가 필요하면 tombstone patch를 남긴다.
4. Agent는 자기 namespace만 수정한다.
5. Supervisor만 workflow status를 바꿀 수 있다.

### 6. Namespace별 쓰기 권한

| Agent | 쓰기 가능 namespace |
| --- | --- |
| Supervisor | `run` |
| Router | 현재 state namespace write 없음 |
| Correlation Engine | `correlation` |
| Planner | `run.plan` (retrieval_plan만; `run.status`는 Supervisor 전용) |
| Retrieval | `evidence` |
| Classifier | `incident`, `analysis` |
| RCA | `analysis` |
| Remediation | `actions.candidates` |
| Policy Guard | `actions.policy_decisions`, `actions.approval_requests` |
| Human Approval Gate | `actions.approved_actions` |
| Change Management Gate | `actions.change_management_records` |
| Executor | `actions.execution_results` |
| Verifier | `verification` |
| Report | `report` |

### 7. Approval과 Execution

`approved_actions`는 Policy Guard 산출물이 아니다. 승인 gate 또는 change management gate의 결과다.

FastAPI State는 approval/change record의 정본이 아니다. FastAPI는 run 안에서 어떤 action이 어떤 approval 또는 change ticket과 연결되었는지 mirror하고, 현재 executor는 local `approved_actions`/change gate 결과로 ready candidate를 만든다. 단, 현재 `ToolContext.spring_headers()`는 `X-Approval-Id`나 change-ticket header를 전송하지 않으므로 Spring mutation의 approval 최종 검증에는 연결되지 않는다.

최소 구조:

```json
{
  "actions": {
    "approval_requests": [
      {
        "approval_id": "appr_001",
        "action_id": "act_001",
        "params_hash": "sha256:9f0b...",
        "status": "pending"
      }
    ],
    "approved_actions": [
      {
        "approval_id": "appr_001",
        "action_id": "act_001",
        "params_hash": "sha256:9f0b..."
      }
    ],
    "change_management_records": [
      {
        "change_ticket_id": "chg_001",
        "action_id": "act_002",
        "status": "linked"
      }
    ]
  }
}
```

필수 필드:

| 위치 | 필드 | 이유 |
| --- | --- | --- |
| `approval_requests[]` | 현재 runner patch 기준 `action_id`, `decision`, `status`, `required_approver` | 승인 요청과 action parameter를 묶는 설계 필드는 남아 있지만 현재 state patch에는 `approval_id`/`params_hash`가 없다 |
| `approved_actions[]` | `approval_id`, `action_id`, `params_hash` | 승인된 action과 실행 parameter가 바뀌지 않았는지 확인 |
| `change_management_records[]` | `change_ticket_id`, `action_id`, `status` | 변경관리 ticket과 action 연결 상태 표시 |

`status`는 FastAPI workflow-local gate 결과를 반영한다. Spring approval facade는 별도 SoT지만 현재 executor header 전파가 없어 mutation 실행 요청에 approval id가 실리지 않는다. change ticket의 `window`와 `rollback_plan`은 현재 FastAPI change-ticket repository/state에서 보관·검증하며, Spring change-ticket validate는 tenant와 `OPEN` status만 확인한다.

Executor는 `approved_actions` 또는 연결된 change ticket을 확인한 뒤 실행한다.

#### 7.1 Severity

`incident.severity`는 UI 표시와 policy escalation에 사용하며 FastAPI State enum 기준 **`WARNING`/`CRITICAL` 2단계**다. Spring `IncidentResponse.severity`는 현재 `WARN`/`ERROR` 문자열을 반환하므로 경계에서 동일 enum으로 취급하면 안 된다.

| Severity | 의미 | 기본 처리 |
| --- | --- | --- |
| `CRITICAL` | 고객 영향·데이터 손실 위험 또는 ERROR 이벤트 포함 | 즉시 Incident, approval 우선순위 높임 |
| `WARNING` | 임계 초과 경고가 누적(동일 리소스 30분 내 2건 이상) | window 내 correlation, Incident 분석 |

### 8. Verification Result

```json
{
  "verification_id": "ver_001",
  "status": "needs_revision",
  "target": "root_cause",
  "reason": "required evidence for SOURCE_DB_CONNECTION_TIMEOUT is missing",
  "approved_for_final_response": false,
  "next_agent": "Retrieval"
}
```

현재 runner는 Verifier 결과를 Supervisor에 기록한다. `approved_for_final_response=false`인 `fail`/`needs_revision` 결과는 책임 Agent loopback으로 이어지고, 예산 초과 시 Report 없이 `failed` 종료한다. Report snapshot이 생성되는 경우 `report_snapshot.verified`가 승인 여부를 반영한다.

### 9. Selective Hydration

LLM 호출 시 전체 State를 넣지 않는다. Agent별로 필요한 namespace와 evidence summary만 hydrate한다.

| Agent | Hydration 범위 |
| --- | --- |
| Classifier | incident, correlation, evidence summary |
| RCA | incident, evidence summary, root cause catalog |
| Remediation | root cause, runbook, policy hint |
| Verifier | analysis, actions, evidence summary |
| Report | 현재 runner는 `run_report(user_message, retrieval_out, mode, llm)`만 호출하므로 전체 retrieval evidence summary를 사용한다. verifier-approved summary hydration은 아직 없다 |

### 10. Redaction과 Tombstone

민감 정보가 포함된 evidence는 저장 전 redaction한다. 삭제가 필요하면 evidence item을 제거하지 않고 tombstone을 남긴다.

```json
{
  "evidence_id": "ev_log_001",
  "status": "tombstoned",
  "reason": "retention_expired",
  "tombstoned_at": "2026-07-01T00:00:00Z"
}
```

---
