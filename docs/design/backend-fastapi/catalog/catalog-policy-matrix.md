# Catalog — Policy Matrix (§12)

> FastAPI Agent 카탈로그 · 개요 [overview](../overview.md) · 원리 [agent-principles](../agent-principles.md). **카탈로그**: [failure-types](./catalog-failure-types.md) · [incident→rootcause](./catalog-incident-root-cause-map.md) · [root-causes](./catalog-root-causes.md) · [evidence-matrix](./catalog-evidence-matrix.md) · [correlation-rules](./catalog-correlation-rules.md) · [runbooks](./catalog-remediation-runbooks.md) · [policy-matrix](./catalog-policy-matrix.md)

## 12. Catalog: Policy Matrix


### 1. 목적

이 문서는 Agent action의 위험도와 승인 기준을 정의한다. Policy Guard는 이 문서를 기준으로 `allow`, `require_approval`, `require_change_management`, `deny` 중 하나를 선택한다.

**최종 집행·정본은 Spring Boot의 [server.md §7.1 Operation Allowlist](../../backend-springboot/server.md#71-operation-allowlist-집행-경계-단일-출처)다.** 이 policy matrix는 그 allowlist를 미러링한 *사전 판단*이며, Spring이 실행 직전 같은 기준을 재검증한다(불일치 시 Spring 기준 우선).

### 2. Decision

| Decision | 의미 |
| --- | --- |
| `allow` | 자동 실행 가능 |
| `require_approval` | 사람 승인 후 실행 가능 |
| `require_change_management` | change ticket, 실행 window, rollback plan 필요 |
| `deny` | 실행 금지 |

### 3. Risk Level

| Risk | 설명 | 기본 decision |
| --- | --- | --- |
| `read_only` | 상태 조회, 로그/메트릭 조회 | `allow` |
| `low` | 외부 상태 변경 없음, 내부 workflow 상태만 변경 | `allow` |
| `medium` | 제한적 runtime 상태 변경 | `require_approval` |
| `high` | 데이터 재처리, rollback, 광범위한 영향 | `require_change_management` |
| `forbidden` | 데이터 삭제, secret 노출, shell 실행 | `deny` |

### 4. Tool별 기본 정책

`runtime_tool`은 tool allowlist 기준으로 판단하고, `workflow_action`/`composite_action`/`notification`/`escalation`은 action catalog 기준으로 판단한다.

| Tool 또는 Action | Risk | Decision |
| --- | --- | --- |
| `get_pipeline_logs` | read_only | allow |
| `get_metrics` | read_only | allow |
| `get_traces` | read_only | allow |
| `get_connector_status` | read_only | allow |
| `get_connector_task_trace` | read_only | allow |
| `get_consumer_lag` | read_only | allow |
| `get_broker_metrics` | read_only | allow |
| `get_pod_status` | read_only | allow |
| `get_db_connection_status` | read_only | allow |
| `collect_*_evidence` | read_only | allow |
| `collect_connector_trace` | read_only | allow |
| `collect_schema_changes` | read_only | allow |
| `collect_broker_metrics` | read_only | allow |
| `collect_sink_write_metrics` | read_only | allow |
| `collect_pod_status` | read_only | allow |
| `collect_memory_metrics` | read_only | allow |
| `collect_recent_changes` | read_only | allow |
| `collect_additional_evidence` | read_only | allow |
| `send_operator_notification` | low | allow |
| `create_ticket` | low | allow |
| `escalate_to_customer_owner` | low | allow |
| `escalate_credential_rotation` | low | allow |
| `escalate_platform_capacity` | low | allow |
| `escalate_to_operator` | low | allow |
| `pause_non_critical_pipeline` | medium | require_approval |
| `pause_low_priority_pipeline` | medium | require_approval |
| `reduce_pipeline_pressure` | medium | require_approval |
| `restart_connector_task` | medium | require_approval |
| `restart_connector` | medium | require_approval |
| `pause_connector` | medium | require_approval |
| `resume_connector` | medium | require_approval |
| `scale_consumer_deployment` | medium | require_approval |
| `rollout_restart_deployment` | medium | require_approval |
| `pause_pipeline` | medium | require_approval |
| `resume_pipeline` | medium | require_approval |
| `create_rebalance_proposal` | medium | require_approval |
| `approve_rebalance` | medium | require_approval |
| `refresh_rebalance` | medium | require_approval |
| `backfill_pipeline` | high | require_change_management |
| `rollback_pipeline` | high | require_change_management |
| `rollback_deployment` | high | require_change_management |

### 5. 변경관리 대상

다음 조건 중 하나라도 해당하면 `require_change_management`로 올린다.

- 데이터 재처리
- rollback
- partition/retention/schema 변경
- connector config overwrite
- customer-visible downtime 가능성
- 여러 pipeline에 동시에 영향
- rollback plan이 필요한 작업

### 6. Severity 보정

`incident.severity`는 플랫폼과 동일하게 **`WARNING`/`CRITICAL` 2단계**다([기능명세서 부록 B.7](../../../spec.md#b7-인시던트-자동-생성-및-그룹화-규칙)). severity는 action을 더 위험한 decision으로 **올릴 때만** 사용하고, 자동으로 낮추는 데는 쓰지 않는다.

| Severity | 정책 보정 |
| --- | --- |
| `CRITICAL` | approval 요청 우선순위를 높이고, customer-visible action은 change management 검토 |
| `WARNING` | runtime mutation은 최소 approval 유지, 기본 decision 유지 (자동 허용으로 낮추지 않음) |

> 에이전트와 플랫폼은 같은 2단계 severity를 쓴다. 별도의 4단계(critical/high/medium/low) 축은 두지 않는다 — 과거 문서의 4단계 표기는 폐기한다. RCA 분석으로 보정된 severity(있으면)는 [Spring Report Support API](../../../api/springboot.md#24-report-support-api)의 `PATCH .../incidents/{id}/rca`로 incident에 기록한다.

### 7. Deny 대상

다음 작업은 승인 여부와 무관하게 deny한다.

| 작업 | 이유 |
| --- | --- |
| pod exec | shell 권한 노출 |
| arbitrary SQL | 고객사 DB 직접 조작 |
| secret 원문 조회 | credential 노출 |
| topic delete | 데이터 손실 |
| namespace/PVC delete | 인프라 손상 |
| DB truncate | 데이터 손실 |
| LLM 생성 manifest 직접 apply | 검증 불가 |

### 8. Approval 검증 조건

`require_approval` action은 다음을 모두 만족해야 한다.

| 조건 | 설명 |
| --- | --- |
| approver 권한 | 승인자가 project/resource 권한 보유 |
| action match | 승인된 tool과 실제 tool 일치 |
| params hash match | 승인 당시 parameter와 실행 parameter 일치 |
| expiry | 승인 유효기간 이내 |
| single-use | 이미 사용된 승인 아님 |
| reason | 승인 사유 기록 |

### 9. Change Management 검증 조건

`require_change_management` action은 다음을 모두 만족해야 한다.

| 조건 | 설명 |
| --- | --- |
| change ticket | 승인된 change ticket 존재 |
| execution window | 현재 시간이 실행 window 안 |
| rollback plan | 실패 시 되돌림 계획 존재 |
| impact analysis | 영향 범위 기록 |
| verifier plan | 실행 후 검증 방법 존재 |

### 10. Policy Guard Output

```json
{
  "action_id": "act_001",
  "action_type": "runtime_tool",
  "tool_name": "restart_connector_task",
  "risk": "medium",
  "decision": "require_approval",
  "reason": "connector task restart changes runtime state",
  "required_approver": "project_operator"
}
```

### 11. 예외 처리

정책이 불명확하면 `allow`로 낮추지 않는다.

| 상황 | 처리 |
| --- | --- |
| action 영향 범위 불명확 | require_approval |
| 데이터 손실 가능성 불명확 | require_change_management |
| catalog에 없는 `runtime_tool` | deny |
| catalog에 없는 workflow/composite/notification/escalation action | Remediation output reject |
| approval scope 불일치 | deny |
| change window 종료 | deny 또는 대기 |

### 12. Versioning

Policy 변경은 audit와 replay test에 영향을 준다. 새로운 mutation tool을 추가할 때는 이 문서와 [§4 Tool Catalog](../tool-catalog.md#4-tool-catalog)를 함께 갱신한다.

---
