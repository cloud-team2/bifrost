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
    "required_flow": ["planner", "retrieval", "verifier", "report"]
  }
}
```

허용 mode:

- `simple_query`
- `incident_analysis`
- `action_execution`
- `approval_decision`

Router는 매 사용자 메시지마다 mode를 재판정한다. 현재 router는 **lightweight LLM으로 mode를 분류**하고(#483) LLM 미가용 시 keyword fallback하며, `/` 슬래시 입력은 결정적 단축 경로로 처리한다(#504). `required_flow`는 transition table에서 계산한다. 조치 후보 제시 요청은 `remediation_requested=true`, `action_execution`/`approval_decision`은 `reuse_existing_analysis=true`를 반환한다.

현재 transition table 기준 `incident_analysis` + `remediation_requested=true`는 `remediation`과 `policy_guard`까지만 추가한 뒤 Verifier와 Report로 진행한다. `approval_or_change_gate`/`executor` 실행 경로는 이 mode의 current flow에 들어 있지 않다.

**[현재]** `RouteDecision`(`schemas/outputs.py:43-48`)의 현재 필드는 `mode`, `remediation_requested`, `reuse_existing_analysis`, `reason`, `required_flow`가 전부다. 실행 깊이·tool budget·이력 범위를 통제하는 필드는 아직 없다.

**[계획 §1]** Router 출력에 실행 깊이 제어 필드를 추가한다(다른 브랜치가 `RouteDecision`에 이 이름 그대로 구현한다). 근거: [rca-standards-review §2.5, §6.4.1, §6.6, §7-item1](../../rca-standards-review.md).

```json
{
  "route_decision": {
    "mode": "incident_analysis",
    "remediation_requested": false,
    "reuse_existing_analysis": false,
    "reason": "user asked for the cause only",
    "required_flow": ["planner", "retrieval", "verifier", "report"],
    "execution_depth": "incident_diagnosis",
    "max_tool_calls": 6,
    "allow_react_loop": true,
    "history_policy": "summary"
  }
}
```

| 필드 | 타입/값 | 의미 |
| --- | --- | --- |
| `execution_depth` | `direct_answer` / `single_lookup` / `bounded_lookup` / `incident_diagnosis` / `remediation_planning` / `action_execution` | 이 turn에서 허용되는 최대 실행 깊이. depth→허용 stage 매핑의 **정본은 [§15 Workflow Control](./contract-workflow-control.md#401-실행-깊이execution_depth별-허용-stage-정본-계획-1)** 에 둔다. |
| `max_tool_calls` | int | 이 turn에서 허용되는 read-only tool 호출 수 |
| `allow_react_loop` | bool | Retrieval ReAct tool loop 허용 여부 |
| `history_policy` | `none` / `summary` / `full` | 다음 agent에 전달할 대화 이력 범위 |

`execution_depth` 기본 매핑(서비스 기준 초기값, [§6.4.1](../../rca-standards-review.md)):

| 사용자 의도(예) | `execution_depth` | `max_tool_calls` | `allow_react_loop` |
| --- | --- | --- | --- |
| "DLQ가 뭐야?"(용어/지식) | `direct_answer` | 0 | false |
| "파이프라인 목록 보여줘" | `single_lookup` | 1 | false |
| "현재 상태 요약해줘" | `bounded_lookup` | 2 | false |
| "왜 장애났는지 분석해줘" | `incident_diagnosis` | 4~6 | true |
| "원인과 조치 후보 알려줘" | `remediation_planning` | 4~6 | true |
| "재시작해줘"/"승인할게" | `action_execution` | 0(read-only), mutation은 governance 경로 | false |

`simple_query` mode는 `direct_answer`/`single_lookup`/`bounded_lookup` 3분기로 세분되고, `incident_analysis`는 `incident_diagnosis`/`remediation_planning`, action 경로는 `action_execution`에 대응한다.

### 3. Planner Output

```json
{
  "retrieval_plan": [
    {
      "step_id": "plan_001",
      "tool_name": "search_logs",
      "params": {
        "pipeline_id": "daily_user_sync",
        "time_range": { "from": "now-30m", "to": "now" }
      },
      "purpose": "extract task timeout evidence",
      "required": true,
      "depends_on": [],
      "plan_hash": "sha256:1b7c..."
    },
    {
      "step_id": "plan_002",
      "tool_name": "get_connector_status",
      "params": {
        "connector_name": "daily-user-sync-source"
      },
      "purpose": "source connector status",
      "required": true,
      "depends_on": [],
      "plan_hash": "sha256:94aa..."
    }
  ]
}
```

Planner output은 Retrieval이 바로 실행할 수 있는 최소 plan이어야 한다.

| 필드 | 설명 |
| --- | --- |
| `params` | Tool Client Registry가 검증할 tool parameter. secret 원문은 넣지 않는다. |
| `depends_on` | 설계 필드로 남아 있지만 현재 Retrieval 구현은 의존 그래프를 해석하지 않고 모든 planned tool을 `asyncio.gather(...)`로 동시에 호출한다. |
| `plan_hash` | 동일 조회 중복 실행을 막기 위한 hash. 계산 기준은 [§15 Workflow Control](./contract-workflow-control.md#51-루프-방지와-종료-보장)을 따른다. |

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
      "tool_name": "restart_connector",
      "root_cause_id": "CONNECTOR_TASK_FAILED",
      "risk": "high",
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

**[현재]** `ActionCandidateOutput`(`schemas/outputs.py:97-114`)의 `rollback_plan`은 자유 텍스트 string 한 필드뿐이다. Change Gate는 ticket의 `rollback_plan` 존재 여부만 검증하고(`workflow/stages/change_gate.py:115`), **자동 롤백 실행을 위한 구조화 필드(스냅샷 참조, rollback action id, 상태)는 없다**.

**[계획 §5]** Verifier 실패 시 자동 롤백을 실행하려면 후보 단계에서 rollback 메타데이터를 구조화한다(필드 이름 정본). 근거: [rca-standards-review §2.5, §5.2 "조치 실패 시 자동 롤백", §7-item5](../../rca-standards-review.md).

```json
{
  "action_candidates": [
    {
      "action_id": "act_001",
      "action_type": "runtime_tool",
      "action_name": "restart_connector_task",
      "tool_name": "restart_connector",
      "root_cause_id": "CONNECTOR_TASK_FAILED",
      "risk": "high",
      "reason": "connector task is FAILED and no schema/config regression evidence found",
      "expected_effect": "clear transient task failure",
      "rollback_plan": "pause connector if task fails again",
      "rollback_action_id": "act_001_rb",
      "estimated_duration": "약 30초"
    }
  ]
}
```

| 필드 | 의미 |
| --- | --- |
| `rollback_action_id` | Verifier 실패 시 실행할 보상 action 후보 id. `rollback_plan`(자유 텍스트)을 실행 가능한 action으로 묶는다 |

risk-tier 자동 롤백 정책: `low`/`read-only`와 일부 `medium` 조치는 rollback을 자동 실행하고, `high` 위험 조치는 rollback 실행도 승인 대상으로 둔다(정본은 [§15 Verifier 실패 → rollback stage](./contract-workflow-control.md#92-verifier-실패-rollback-stage-계획-5)).

Action 후보의 실행 상태는 Policy Guard 이후부터 다음 enum 중 하나로 표시한다.

| Status | 의미 | FE Run 버튼 |
| --- | --- | --- |
| `pending_approval` | 사람 승인 또는 change ticket이 필요함 | 비활성 |
| `ready` | 실행 조건을 충족함 | 활성 |
| `running` | 실행 중 | 비활성 |
| `completed` | 실행 성공 | 비활성 |
| `failed` | 실행 실패 | 비활성 |
| `blocked` | 정책 또는 Spring 검증으로 실행 차단 | 비활성 |

세부 실패 원인은 status enum을 늘리지 않고 `reason_code`와 `summary`로 표현한다.

### 8. Policy Guard Output

```json
{
  "policy_decisions": [
    {
      "action_id": "act_001",
      "action_type": "runtime_tool",
      "tool_name": "restart_connector",
      "risk": "high",
      "decision": "require_approval",
      "status": "pending_approval",
      "reason": "connector restart changes runtime state",
      "required_approver": "project_operator"
    }
  ]
}
```

Policy Guard는 승인 완료를 기록하지 않는다. 승인 결과는 approval gate가 State에 기록한다.

Policy decision과 action status의 기본 매핑:

| Policy decision | Action status |
| --- | --- |
| `allow` | `ready` |
| `require_approval` | `pending_approval` |
| `require_change_management` | `pending_approval` |
| `deny` | `blocked` |

### 9. Executor Output

```json
{
  "execution_results": [
    {
      "action_id": "act_001",
      "tool_name": "restart_connector",
      "status": "completed",
      "audit_event_id": "audit_001",
      "before_evidence_id": "ev_before_001",
      "after_evidence_id": "ev_after_001",
      "reason_code": null,
      "summary": "connector task restart requested"
    }
  ]
}
```

Executor는 현재 Spring Boot 응답을 받은 뒤 terminal execution result만 반환하고, runner는 `/actions/execution_results` patch를 append한다. 실행 시작 시 별도 `running` 상태 patch는 기록하지 않는다. Mutation timeout은 자동 재시도하지 않으며, 실패는 terminal result의 `status`, `reason_code`, `summary`로 표현된다.

**[현재]** `ExecutionResultOutput`(`schemas/outputs.py:149-167`)의 현재 필드는 `action_id`, `tool_name`, `status`, `audit_event_id`, `before_evidence_id`, `after_evidence_id`, `reason_code`, `summary`다. `before_evidence_id`/`after_evidence_id`는 evidence 참조일 뿐, **조치 전 상태 스냅샷이나 롤백 실행 결과를 담는 필드는 없다**. Executor의 `_snapshot_evidence(...)`(`workflow/stages/executor.py:22-24`)도 참조 id만 생성하고 실제 pre-change 상태를 보존하지 않는다.

**[계획 §5]** 자동 롤백 실행 경로의 결과를 run record에 남기기 위해 ExecutionResult에 rollback 필드를 추가한다(필드 이름 정본). 근거: [rca-standards-review §2.5, §5.2, §7-item5](../../rca-standards-review.md).

```json
{
  "execution_results": [
    {
      "action_id": "act_001",
      "tool_name": "restart_connector",
      "status": "failed",
      "audit_event_id": "audit_001",
      "before_evidence_id": "ev_before_001",
      "after_evidence_id": "ev_after_001",
      "pre_change_snapshot": "snapshot://run_001/act_001",
      "rollback_action_id": "act_001_rb",
      "rollback_status": "rolled_back",
      "rollback_audit_event_id": "audit_001_rb",
      "reason_code": "VERIFY_FAILED",
      "summary": "restart failed verification; rolled back to pre-change state"
    }
  ]
}
```

| 필드 | 의미 |
| --- | --- |
| `pre_change_snapshot` | 조치 전 상태 스냅샷 참조. Verifier 실패 시 이 지점으로 원복한다 |
| `rollback_action_id` | 실행된 보상 action id(후보 단계 `rollback_action_id`와 연결) |
| `rollback_status` | `not_triggered` / `rolled_back` / `rollback_failed` / `rollback_pending_approval` |
| `rollback_audit_event_id` | rollback 실행의 append-only 감사 이벤트 id |

`pre_change_snapshot`은 Executor가 mutation 직전 저장하고, `rollback_*` 필드는 Verifier 실패 → rollback stage 실행([§15 §9.2](./contract-workflow-control.md#92-verifier-실패-rollback-stage-계획-5)) 이후 채워진다.

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

현재 `run_report()`는 structured `final_response` 객체가 아니라 plain string answer를 반환한다. Runner state patch는 `/report/draft`에 `{"draft":{"answer": ...}}` object를 기록하고, `report_snapshot.body`는 `{"answer", "mode", "evidence"}` JSON으로 저장한다. 아래 구조는 목표 schema이며 현재 코드 출력이 아니다.

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
        "status": "pending_approval"
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
| catalog에 없는 `runtime_tool` | Policy Guard가 아니라 `ToolClientRegistry.call_tool(...)`에서 blocked result |
| `workflow_action`/`composite_action`/`notification`/`escalation`인데 action catalog에 없음 | Remediation output reject |
| raw evidence inline 포함 | Retrieval output reject |
| verifier approval 없는 report | Verifier `fail`/`needs_revision`은 Supervisor loopback으로 책임 Agent에 되돌린다. 예산 초과 시 Report 없이 `failed` 종료하며, 생성된 snapshot은 승인 여부를 `report_snapshot.verified`에 저장한다 |
| Planner step에 `params`, `depends_on`, `plan_hash` 누락 | Planner output reject |
| 허용되지 않은 action status | Output reject |
