# Contract — Agent Roles (§13)

> FastAPI Agent 계약 · 개요 [overview](../overview.md) · 원리 [agent-principles](../agent-principles.md). **계약**: [agent-roles](./contract-agent-roles.md) · [state-schema](./contract-state-schema.md) · [workflow-control](./contract-workflow-control.md) · [streaming-events](./contract-streaming-events.md) · [output-schemas](./contract-output-schemas.md)

## 13. Contract: Agent Roles


### 1. 목적

이 문서는 Supervisor가 제어하는 workflow에서 각 단계의 책임, 입력, 출력, 금지 행위를 정의한다. workflow는 8개 LLM agent와 결정론적 단계로 구성된다.

역할 분리는 hallucination과 무단 실행을 줄이기 위한 핵심 안전장치다. 아래 단계는 모두 자기 output 계약을 갖지만, LLM 추론을 쓰는 것은 8개 agent뿐이다.

### 2. 전체 목록

LLM agent (8) — evidence 기반 판단·생성:

| Agent | 책임 | 주요 출력 |
| --- | --- | --- |
| Router | 요청 유형과 실행 mode 결정 | `route_decision` |
| Planner | evidence 수집 계획 작성 | `retrieval_plan` |
| Retrieval | 문서와 운영 evidence 수집 | `evidence_items` |
| Classifier | incident type과 scope 분류 | `classification` |
| RCA | root cause 후보 검증 | `root_cause_candidates` |
| Remediation | 조치 후보 작성 | `action_candidates` |
| Verifier | 분석·실행·보고 검증 | `verification_results` |
| Report | 검증된 응답 작성 | `final_response` |

결정론적 단계 (LLM 추론 없음) — 룰/도구 실행:

| 단계 | 책임 | 주요 출력 |
| --- | --- | --- |
| Correlation Engine | rule/score/window로 alert 병합 | `correlation` |
| Policy Guard | policy-matrix lookup으로 decision 결정 | `policy_decisions` |
| Executor | 승인된 tool 실행 | `execution_results` |
| Approval Gate | 사람 승인 결과 기록 | `approved_actions` |
| Change Management Gate | 변경관리 검증 결과 기록 | `change_management_records` |

Supervisor는 이 단계들을 제어하는 control layer다. Policy Guard와 Executor는 LLM 없이 동작하지만, 각자의 output 계약과 금지 행위(아래 [§13.9 Policy Guard](#9-policy-guard), [§13.10 Executor](#10-executor))는 그대로 적용된다.

### 3. Router

책임:

- 사용자 요청 또는 alert 입력을 해석한다.
- 매 사용자 메시지마다 `simple_query`, `incident_analysis`, `action_execution`, `approval_decision` 중 mode를 재판정한다.
- `incident_analysis`는 기본 `diagnose_only`로 두고, 사용자가 조치를 요청하면 `remediation_requested=true`로 표시한다.
- 기존 run State가 유효하면 `reuse_existing_analysis=true`로 표시해 재분석을 생략하게 한다.
- 필요한 경우 Correlation Engine으로 보낸다.

금지:

- RCA 결론 작성
- 조치 제안
- tool 직접 호출

### 4. Planner

책임:

- 필요한 evidence 종류와 수집 순서를 정한다.
- root cause를 확정하지 않고 가설 검증 계획만 만든다.
- read-only tool 중심의 retrieval plan을 만든다.

금지:

- 로그/메트릭 해석 결론 작성
- mutation action 생성

### 5. Retrieval

책임:

- 문서 RAG와 read-only tool을 호출한다.
- **plan의 독립적인 read-only tool은 병렬(fan-out)로 호출한다.** 서로 입력 의존이 없는 tool(예: `get_metrics`·`get_pipeline_logs`·`get_connector_status`)은 동시에 실행하고, 한 tool 결과가 다음 tool 입력이 되는 경우만 순차로 둔다. 순차 chain은 retrieval 단계 지연의 가장 큰 원인이므로 기본은 병렬이다([§4](../tool-catalog.md#4-tool-catalog) Tool Catalog [§13.1](../tool-catalog.md#131-read-only-tool-병렬-실행)).
- 수집되는 대로 evidence metadata를 State에 append하고 `evidence_collected` event를 스트리밍한다(전체 plan 완료를 기다리지 않는다).
- evidence 원문을 Evidence Store에 저장하고 metadata만 State에 남긴다.
- redaction 상태를 기록한다.

금지:

- root cause 확정
- action 추천
- raw content를 State에 inline 저장
- 독립 tool을 불필요하게 순차 실행(병렬 가능한데 직렬화)

### 6. Classifier

책임:

- [§6 Failure Types](../catalog/catalog-failure-types.md#6-catalog-failure-types)를 기준으로 incident type을 분류한다.
- single incident인지 incident group인지 scope를 정한다.
- 공통 원인 가능성이 있으면 필요한 shared evidence를 명시한다.

금지:

- remediation 제안
- confidence가 낮은 유형을 확정처럼 표현

### 7. RCA

책임:

- [§8 Root Cause Catalog](../catalog/catalog-root-causes.md#8-catalog-root-cause)에서 후보를 선택한다.
- [§9 Evidence Matrix](../catalog/catalog-evidence-matrix.md#9-catalog-evidence-matrix)를 기준으로 required/supporting/negative evidence를 대조한다.
- confidence와 evidence gap을 기록한다.

금지:

- catalog에 없는 root cause 생성
- evidence 없는 결론
- action 실행

### 8. Remediation

책임:

- [§11 Remediation Runbooks](../catalog/catalog-remediation-runbooks.md#11-catalog-remediation-runbooks)를 기준으로 action 후보를 만든다.
- action 후보에는 `runtime_tool`, `workflow_action`, `composite_action`, `notification`, `escalation` 중 하나의 `action_type`을 붙인다.
- expected effect, risk, 예상 소요시간(estimated_duration, FR-022), rollback hint를 기록한다.
- 고객사 소유 영역은 escalation action으로 둔다.

금지:

- 승인 여부 최종 판단
- tool 실행
- runbook에 없는 action 생성

### 9. Policy Guard

Policy Guard는 LLM 추론 단계가 아니라 policy-matrix 룰 lookup으로 동작하는 결정론적 단계다.

책임:

- [§12 Policy Matrix](../catalog/catalog-policy-matrix.md#12-catalog-policy-matrix)를 기준으로 risk와 decision을 정한다.
- `allow`, `require_approval`, `require_change_management`, `deny` 중 하나를 선택한다.
- 불명확하면 더 안전한 decision으로 올린다.

금지:

- 기술적 효과 판단
- evidence 보강 없이 action을 safe로 낮춤
- 승인 완료로 간주

### 10. Executor

책임:

- 승인된 action만 [§4 Tool Catalog](../tool-catalog.md#4-tool-catalog)의 tool registry를 통해 실행한다.
- Spring Boot Operations Backend 응답을 표준 execution result로 변환한다.
- before/after evidence reference를 State에 append한다.

금지:

- LLM 자유 판단으로 tool 선택
- approval 없는 mutation 실행
- API path 직접 조립
- runtime credential 보유

### 11. Verifier

책임:

- RCA 결과가 evidence와 맞는지 검증한다.
- 실행 결과가 기대 상태와 맞는지 확인한다.
- Report가 검증된 내용만 포함하는지 확인한다.

출력 status:

- `pass`
- `fail`
- `needs_revision`

### 12. Report

책임:

- 사용자에게 최종 응답을 작성한다.
- 검증된 root cause, confidence, evidence summary, action status를 보여준다.
- 불확실성, evidence gap, escalation 필요성을 명확히 쓴다.

금지:

- Verifier 미통과 내용 출력
- raw secret, connection string, 원문 로그 과다 노출
- 새로운 원인 또는 조치 생성

---

