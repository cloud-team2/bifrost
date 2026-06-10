# Catalog — Remediation Runbooks (§11)

> FastAPI Agent 카탈로그 · 개요 [overview](../overview.md) · 원리 [agent-principles](../agent-principles.md). **카탈로그**: [failure-types](./catalog-failure-types.md) · [incident→rootcause](./catalog-incident-root-cause-map.md) · [root-causes](./catalog-root-causes.md) · [evidence-matrix](./catalog-evidence-matrix.md) · [correlation-rules](./catalog-correlation-rules.md) · [runbooks](./catalog-remediation-runbooks.md) · [policy-matrix](./catalog-policy-matrix.md)

## 11. Catalog: Remediation Runbooks


### 1. 목적

이 문서는 root cause별 대응 후보를 정의한다. Remediation Agent는 이 문서의 action template 안에서만 조치 후보를 만든다.

실행 가능 여부는 [§12 Policy Matrix](catalog-policy-matrix.md#12-catalog-policy-matrix)와 Spring Boot Operations Backend가 최종 판단한다.

### 2. 원칙

1. Remediation Agent는 조치 후보만 만든다.
2. 승인 필요 여부는 Policy Guard가 판단한다.
3. 실행은 Executor와 Spring Boot Operations Backend만 한다.
4. 고객사 소유 영역은 직접 수정하지 않고 escalation한다.
5. 데이터 손실 가능성이 있는 조치는 기본 금지 또는 변경관리 대상이다.

Runbook의 `Action`은 항상 Spring Boot mutation tool을 뜻하지 않는다. 추가 evidence 수집은 `workflow_action`, 고객사/플랫폼 전달은 `escalation`, 알림은 `notification`, 여러 조치 후보로 분해해야 하는 의도는 `composite_action`으로 둔다. 실제 실행 가능한 단일 tool은 [§4 Tool Catalog](../tool-catalog.md#4-tool-catalog)의 `runtime_tool` catalog에 등록되어야 한다.

현재 코드에는 legacy action name이 남아 있을 수 있다. 예를 들어 `restart_connector_task` action template은 실행 tool name으로 `restart_connector`를 사용한다. 실제 Spring endpoint에 넘기는 값은 `tool_name`과 Tool Registry definition이 정본이다.

### 3. Root Cause Coverage Matrix

이 표는 [§8 Root Cause Catalog](catalog-root-causes.md#8-catalog-root-cause)의 모든 `root_cause_id`에 대해 v1에서 Remediation Agent가 어떤 수준의 대응 후보를 만들 수 있는지 정의한다. 상세 runbook이 없는 root cause라도 이 표의 `v1 처리`를 따라야 하며, 표에 없는 root cause에 대해 Remediation Agent가 임의 action을 만들면 안 된다.

| v1 처리 | 의미 |
| --- | --- |
| `detailed_runbook` | 이 문서에 상세 action template이 있으며, 해당 범위 안에서만 조치 후보를 만든다. |
| `diagnose_only` | 원인·근거·한계만 보고하고 runtime mutation 후보는 만들지 않는다. |
| `escalation_only` | 고객사/플랫폼/운영자에게 evidence summary를 전달하는 action만 허용한다. |
| `manual_change_required` | 자동 실행 후보를 만들지 않고 ticket/escalation으로 사람이 변경관리 절차를 진행하게 한다. |
| `unsupported_v1` | v1 Agent 대응 범위 밖이다. Report에 미지원 사유와 필요한 후속 조사를 명시한다. |

| Root cause id | v1 처리 | 허용 action type | 기준 |
| --- | --- | --- | --- |
| `SOURCE_DB_CONNECTION_TIMEOUT` | `detailed_runbook` | `workflow_action`, `escalation`, 조건부 `runtime_tool` | 고객사 source 수정은 금지하고, 근거 수집·전달·조건부 connector task 재시작만 허용 |
| `SOURCE_AUTH_EXPIRED` | `detailed_runbook` | `workflow_action`, `escalation`, 조건부 `runtime_tool` | secret 원문 조회·임의 변경 금지 |
| `SOURCE_READ_LATENCY` | `escalation_only` | `escalation` | source 내부 read 성능은 고객사/공유 영역 |
| `SOURCE_DATA_NOT_READY` | `escalation_only` | `escalation` | upstream 데이터 생성 지연은 고객사 영역 |
| `SOURCE_NETWORK_REACHABILITY` | `escalation_only` | `escalation` | 네트워크 경로 evidence를 정리해 customer/platform owner에게 전달 |
| `CONNECTOR_TASK_FAILED` | `detailed_runbook` | `workflow_action`, `runtime_tool` | Bifrost 소유 connector task 조치 가능 |
| `CONNECTOR_WORKER_REBALANCE_LOOP` | `manual_change_required` | `notification`, `escalation` | worker 안정성 조치는 별도 runbook 확정 전 자동 실행 금지 |
| `PIPELINE_TASK_RETRY_EXHAUSTED` | `diagnose_only` | 없음 | 실패 이력과 원인 후보를 보고하고 추가 root cause로 좁힌 뒤 조치 |
| `PIPELINE_CONFIG_INVALID` | `manual_change_required` | `notification`, `escalation` | config 변경은 변경관리 절차 필요 |
| `SCHEMA_MISMATCH` | `detailed_runbook` | `workflow_action`, 조건부 `runtime_tool` | 데이터 확산 차단·rollback은 정책에 따라 처리 |
| `CONSUMER_LAG_SPIKE` | `detailed_runbook` | `workflow_action`, `composite_action`, 조건부 `runtime_tool` | lag 원인 구분 후 허용된 조치만 제안 |
| `BROKER_RESOURCE_PRESSURE` | `detailed_runbook` | `workflow_action`, `runtime_tool`, `escalation` | rebalance proposal 또는 platform escalation |
| `PARTITION_IMBALANCE` | `manual_change_required` | `notification`, `escalation` | rebalance 상세 runbook 확정 전 자동 실행 금지 |
| `TOPIC_INGRESS_SPIKE` | `diagnose_only` | 없음 | 유입 증가 원인을 보고하고 downstream 보호 조치는 다른 root cause에서 판단 |
| `CONSUMER_REBALANCE_LOOP` | `manual_change_required` | `notification`, `escalation` | consumer group 안정성 조치는 수동 검토 필요 |
| `SINK_DB_CONNECTION_TIMEOUT` | `detailed_runbook` | `workflow_action`, `runtime_tool`, `escalation` | sink 보호를 위한 connector pause/resume과 owner 전달 |
| `SINK_AUTH_EXPIRED` | `escalation_only` | `escalation` | credential owner에게 전달, secret 임의 변경 금지 |
| `SINK_WRITE_LATENCY` | `detailed_runbook` | `workflow_action`, `composite_action`, `runtime_tool` | sink 보호·pressure 완화 후보 제안 |
| `SINK_CONSTRAINT_VIOLATION` | `manual_change_required` | `notification`, `escalation` | 데이터 정합성 영향으로 자동 조치 금지 |
| `POD_OOM_KILLED` | `detailed_runbook` | `workflow_action`, 조건부 `runtime_tool` | pod 상태·메모리 확인 후 정책상 가능한 조치만 제안 |
| `POD_CRASH_LOOP` | `manual_change_required` | `notification`, `escalation` | app/config 원인 확인 전 자동 restart loop 금지 |
| `NODE_PRESSURE` | `escalation_only` | `escalation` | node/cluster는 platform 영역 |
| `PVC_PRESSURE` | `escalation_only` | `escalation` | storage 증설·정리는 platform 변경관리 영역 |
| `DEPLOYMENT_REGRESSION` | `detailed_runbook` | `workflow_action`, `runtime_tool` | 최근 배포 회귀에 대한 pause/rollback 후보 |
| `RECENT_CONFIG_CHANGE_REGRESSION` | `manual_change_required` | `notification`, `escalation` | config rollback은 변경관리 절차 필요 |
| `RECENT_SCHEMA_CHANGE_REGRESSION` | `manual_change_required` | `notification`, `escalation` | schema rollback/수정은 변경관리 절차 필요 |
| `RECENT_IMAGE_DEPLOYMENT_REGRESSION` | `manual_change_required` | `notification`, `escalation` | image rollback은 상세 change 검토 필요 |
| `CREDENTIAL_ROTATION_REGRESSION` | `escalation_only` | `escalation` | credential owner에게 rotation evidence 전달 |
| `UPSTREAM_DATA_VOLUME_ANOMALY` | `escalation_only` | `escalation` | source volume 변화는 customer/shared 영역 |
| `PIPELINE_DUPLICATE_SPIKE` | `manual_change_required` | `notification`, `escalation` | replay/idempotency 이슈는 데이터 정합성 영향 |
| `PIPELINE_FRESHNESS_DELAY` | `diagnose_only` | 없음 | 병목 root cause로 추가 분류 후 조치 |
| `SCHEMA_NULL_RATE_SPIKE` | `escalation_only` | `escalation` | source/schema owner에게 evidence 전달 |
| `UNKNOWN_WITH_EVIDENCE_GAP` | `detailed_runbook` | `workflow_action`, `escalation` | 추가 근거 수집 또는 운영자 전달 |
| `MULTIPLE_POSSIBLE_CAUSES` | `diagnose_only` | 없음 | 추가 evidence로 후보를 좁히기 전 조치 금지 |
| `CUSTOMER_OWNED_ROOT_CAUSE_LIKELY` | `escalation_only` | `escalation` | 고객사 소유 가능성이 높으므로 evidence summary 전달 |

### 4. Source 계층

#### `SOURCE_DB_CONNECTION_TIMEOUT`

| Action | 설명 | 정책 |
| --- | --- | --- |
| `collect_source_timeout_evidence` | source timeout log/metric 추가 수집 | read_only |
| `escalate_to_customer_owner` | 고객사 담당자에게 evidence 전달 | allow |
| `pause_non_critical_pipeline` | downstream 압박 완화를 위해 비긴급 pipeline 일시 중지 | approval |
| `restart_connector_task` | timeout이 transient이고 task가 failed일 때 재시작 | approval |

금지:

- source DB connection limit 직접 변경
- source DB query 실행
- 고객사 DB restart

#### `SOURCE_AUTH_EXPIRED`

| Action | 설명 | 정책 |
| --- | --- | --- |
| `collect_auth_error_evidence` | auth error와 변경 이력 수집 | read_only |
| `escalate_credential_rotation` | credential owner에게 갱신 요청 | allow |
| `pause_pipeline` | 반복 실패로 downstream noise가 클 때 일시 중지 | approval |

금지:

- secret 원문 조회
- credential 임의 변경

### 5. Pipeline / Connector 계층

#### `CONNECTOR_TASK_FAILED`

| Action | 설명 | 정책 |
| --- | --- | --- |
| `restart_connector_task` | 실패 task 재시작 | approval |
| `pause_connector` | 반복 실패로 영향이 커질 때 일시 중지 | approval |
| `resume_connector` | 원인 해소 후 재개 | approval |
| `collect_connector_trace` | task trace와 worker log 수집 | read_only |

주의:

- 같은 원인으로 반복 실패하면 재시작 루프를 만들지 않는다.
- schema/config 문제면 restart보다 변경관리로 넘긴다.

#### `SCHEMA_MISMATCH`

| Action | 설명 | 정책 |
| --- | --- | --- |
| `collect_schema_changes` | subject version과 compatibility 확인 | read_only |
| `pause_pipeline` | 잘못된 데이터 확산 방지 | approval |
| `rollback_pipeline` | schema 변경 또는 배포 rollback | change_management |

금지:

- schema compatibility 강제 변경
- sink table 임의 변경

### 6. Kafka 계층

#### `CONSUMER_LAG_SPIKE`

| Action | 설명 | 정책 |
| --- | --- | --- |
| `get_consumer_lag` | lag 상세 확인 | read_only |
| `scale_consumer_deployment` | consumer replica 증가 | approval |
| `create_rebalance_proposal` | broker imbalance가 동반될 때 proposal 생성 | approval |
| `pause_low_priority_pipeline` | 중요도가 낮은 pipeline 일시 중지 | approval |

주의:

- topic ingress spike인지 consumer 처리 저하인지 먼저 구분한다.
- scale-out 전 consumer가 병렬 처리 가능한 구조인지 확인한다.

#### `BROKER_RESOURCE_PRESSURE`

| Action | 설명 | 정책 |
| --- | --- | --- |
| `collect_broker_metrics` | broker별 CPU/disk/network 확인 | read_only |
| `create_rebalance_proposal` | Cruise Control proposal 생성 | approval |
| `approve_rebalance` | proposal 승인 | approval |
| `escalate_platform_capacity` | capacity 부족 시 platform team escalation | allow |

금지:

- broker 강제 재시작
- topic delete

### 7. Sink 계층

#### `SINK_DB_CONNECTION_TIMEOUT`

| Action | 설명 | 정책 |
| --- | --- | --- |
| `collect_sink_timeout_evidence` | sink write timeout과 latency 수집 | read_only |
| `pause_connector` | sink 보호를 위해 write 중단 | approval |
| `resume_connector` | sink 회복 후 재개 | approval |
| `escalate_to_customer_owner` | sink owner에게 evidence 전달 | allow |

금지:

- sink DB connection limit 변경
- sink DB restart
- 임의 SQL 실행

#### `SINK_WRITE_LATENCY`

| Action | 설명 | 정책 |
| --- | --- | --- |
| `reduce_pipeline_pressure` | 비긴급 pipeline 일시 중지 또는 rate 완화 | approval |
| `pause_connector` | sink 보호 | approval |
| `collect_sink_write_metrics` | write latency와 retry/backoff 수집 | read_only |

### 8. Infra 계층

#### `POD_OOM_KILLED`

| Action | 설명 | 정책 |
| --- | --- | --- |
| `collect_pod_status` | pod status와 restart count 확인 | read_only |
| `collect_memory_metrics` | memory usage와 limit 확인 | read_only |
| `scale_consumer_deployment` | 처리 병렬화로 pod pressure 완화 | approval |
| `rollback_pipeline` | 배포 이후 OOM이면 rollback | change_management |

금지:

- pod exec
- container 내부 파일 수정

#### `DEPLOYMENT_REGRESSION`

| Action | 설명 | 정책 |
| --- | --- | --- |
| `collect_recent_changes` | rollout, image, config diff 확인 | read_only |
| `rollback_pipeline` | 문제 배포 rollback | change_management |
| `pause_pipeline` | 영향 확산 차단 | approval |

### 9. Unknown

#### `UNKNOWN_WITH_EVIDENCE_GAP`

| Action | 설명 | 정책 |
| --- | --- | --- |
| `collect_additional_evidence` | Planner가 추가 evidence 계획 | read_only |
| `escalate_to_operator` | 확정 불가 상태로 운영자에게 전달 | allow |

Unknown 상태에서는 mutation action을 만들지 않는다.

### 10. Action Template Schema

```json
{
  "action_id": "act_001",
  "root_cause_id": "CONNECTOR_TASK_FAILED",
  "action_name": "restart_connector_task",
  "action_type": "runtime_tool",
  "tool_name": "restart_connector",
  "risk": "high",
  "requires_human_approval": true,
  "reason": "connector task is FAILED and no schema/config regression evidence found",
  "expected_effect": "task restart may clear transient failure",
  "rollback_plan": "pause connector if task fails again",
  "estimated_duration": "약 30초 (예상, FR-022 표시용)"
}
```

`tool_name`은 `action_type=runtime_tool`일 때 필수다. `workflow_action`, `notification`, `escalation`, `composite_action`은 `tool_name`을 비워둘 수 있으며, Tool Client Registry 또는 Supervisor가 구체적인 read-only tool plan, notification request, runtime tool 후보로 변환한다.

Action type 예시:

| Action | action_type | 해석 |
| --- | --- | --- |
| `collect_connector_trace` | `workflow_action` | `get_traces`, `search_logs`, `get_connector_status` 수집 계획 |
| `pause_low_priority_pipeline` | `composite_action` | 대상 pipeline을 선택한 뒤 `pause_pipeline` 후보로 분해 |
| `escalate_to_customer_owner` | `escalation` | evidence summary를 고객사 owner에게 전달 |
| `send_operator_notification` | `notification` | 운영자 알림 생성 |

### 11. Versioning

Runbook 변경은 root cause catalog, policy matrix, tool catalog와 함께 검토한다. 특정 action을 새로 허용할 때는 approval 정책과 audit 필드도 같이 정의한다.

---
