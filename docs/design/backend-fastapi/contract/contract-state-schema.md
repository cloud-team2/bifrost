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
| `incident` | Router / Classifier | incident id, severity, scope |
| `correlation` | Correlation Engine | alert group, common root cause 후보 (Classifier는 hydration으로 읽기만, 쓰기 권한 없음 — [§6](../catalog/catalog-failure-types.md#6-catalog-failure-types)) |
| `evidence` | Retrieval | evidence metadata와 store reference |
| `analysis` | Classifier / RCA | incident type, root cause 후보, confidence |
| `actions` | Remediation / Policy / Executor | action 후보, policy decision, execution result |
| `verification` | Verifier | pass/fail/needs_revision, 승인된 report 범위 |
| `report` | Report | draft와 final response |

> `incident` namespace는 Router(초기 incident id·scope 설정)와 Classifier(유형 분류 후 scope·severity 정련)가 **시점을 달리해** 쓰며, 같은 필드를 동시에 덮어쓰지 않는다.

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

State에는 raw evidence를 넣지 않는다.

```json
{
  "evidence_id": "ev_log_001",
  "type": "pipeline_log",
  "store_ref": "evidence://run_001/ev_log_001",
  "summary": "extract_users task에서 source connection timeout 증가",
  "redaction_status": "redacted",
  "collected_by": "Retrieval",
  "collected_at": "2026-06-01T00:15:00Z"
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
| Router | `incident` |
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

FastAPI State는 approval/change record의 정본이 아니다. 정본은 Spring Boot이며, FastAPI는 run 안에서 어떤 action이 어떤 approval 또는 change ticket과 연결되었는지 최소 정보만 mirror한다. Executor는 State의 `approval_id`/`change_ticket_id`와 `params_hash`를 확인한 뒤, 실행 직전에 Spring Boot에 최종 검증을 다시 요청한다.

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
| `approval_requests[]` | `approval_id`, `action_id`, `params_hash`, `status` | 승인 요청과 action parameter를 묶어 UI와 실행 전 precheck에 사용 |
| `approved_actions[]` | `approval_id`, `action_id`, `params_hash` | 승인된 action과 실행 parameter가 바뀌지 않았는지 확인 |
| `change_management_records[]` | `change_ticket_id`, `action_id`, `status` | 변경관리 ticket과 action 연결 상태 표시 |

`status`는 Spring 응답을 반영한 캐시이며, 최종 실행 가능 여부는 Spring Boot Approval/Change Management API가 다시 검증한다. 만료 시각, 승인자, 실행 window, rollback plan 같은 상세 정보는 Spring이 보유하며 FastAPI State의 필수 필드로 중복 저장하지 않는다.

Executor는 `approved_actions` 또는 연결된 change ticket을 확인한 뒤 실행한다.

#### 7.1 Severity

`incident.severity`는 UI 표시와 policy escalation에 사용하며 플랫폼과 동일한 **`WARNING`/`CRITICAL` 2단계**다([기능명세서 부록 B.7](../../../spec.md#b7-인시던트-자동-생성-및-그룹화-규칙)).

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

Report는 `approved_for_final_response`가 true인 결과만 사용한다.

### 9. Selective Hydration

LLM 호출 시 전체 State를 넣지 않는다. Agent별로 필요한 namespace와 evidence summary만 hydrate한다.

| Agent | Hydration 범위 |
| --- | --- |
| Classifier | incident, correlation, evidence summary |
| RCA | incident, evidence summary, root cause catalog |
| Remediation | root cause, runbook, policy hint |
| Verifier | analysis, actions, evidence summary |
| Report | verification-approved summary |

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
