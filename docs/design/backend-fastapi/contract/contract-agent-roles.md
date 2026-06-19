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
| Router | 요청 유형과 실행 mode 결정. [계획 §1] execution_depth·next_agent 결정 | `route_decision` |
| Planner | evidence 수집 계획 작성 | `retrieval_plan` |
| Retrieval | 문서와 운영 evidence 수집 | `evidence_items` |
| Classifier | incident type과 scope 분류 | `classification` |
| RCA | root cause 후보 검증 | `root_cause_candidates` |
| Remediation | 조치 후보 작성 | `action_candidates` |
| Verifier | [현재] mode별 검증: incident_analysis는 RCA/evidence matrix, action_execution/approval_decision은 executor output, 그리고 report body를 검증. mode·입력 조합으로 분기 | `verification_results` |
| Report | plain string answer 작성. snapshot body는 `{"answer","mode","evidence"}` | `/report/draft`, `report_snapshot.body` |

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
- 매 사용자 메시지마다 mode(`simple_query`/`incident_analysis`/`action_execution`/`approval_decision`) 중 하나를 선택한다. 현재 router는 **lightweight LLM으로 mode를 structured 분류**하고(#483), LLM 미가용·파싱/검증 실패 시 keyword 매칭으로 fallback한다(회귀 보존). 승인/거절은 `approval_decision`으로 라우팅된다.
- 실행 의도(재시작 등)는 `action_execution`을 선택한다. 조치 후보 제시 요청은 `incident_analysis` + `remediation_requested=true`로 두어 RCA 뒤 Remediation/Policy Guard 단계를 붙인다.
- `/` 슬래시 입력(`/pipelines`·`/connectors`·`/consumer-groups`·`/events`)은 LLM을 거치지 않는 **결정적 단축 경로**로 처리되어 read-only tool을 직접 선택한다(#504).
- `reuse_existing_analysis`는 `action_execution`/`approval_decision`에서 `true`이며, runner는 같은 run의 이전 action 후보와 policy 결정을 State patch에서 복원한다.
- 필요한 경우 Correlation Engine으로 보낸다.
- [현재] `RouterOutput.route_decision`은 `mode`·`remediation_requested`·`reuse_existing_analysis`·`reason`·`required_flow`만 가진다. `required_flow`는 `stages_for_mode(mode, remediation_requested)` 결과를 그대로 노출하므로, 실행 깊이(어디까지 실행할지)는 mode가 고정한 transition table로만 정해지고 Router가 turn마다 따로 통제하지 못한다.

[계획 §1] 실행 깊이·핸드오프 결정 (roadmap §1 — 자연어 질의 실행 깊이 제어):

- `RouterOutput`에 `execution_depth`(enum: `direct_answer`/`single_lookup`/`bounded_lookup`/`incident_diagnosis`/`remediation_planning`/`action_execution`), `max_tool_calls`(int), `allow_react_loop`(bool), `history_policy`(`none`/`summary`/`full`)를 추가한다. Router는 mode뿐 아니라 "어떤 specialist agent까지 실행할지"(`next_agent`)와 turn당 tool budget을 결정한다.
- depth→허용 stage 매핑의 **정본은 [§15 Workflow Control](contract-workflow-control.md#15-contract-workflow-control)**이다. 이 문서는 표를 복제하지 않고 참조만 한다.
- per-agent history input filter: Router는 최근 사용자 발화 + 짧은 thread summary + 직전 run mode/action 상태만, Planner는 `normalized_intent`+`entities`+`execution_depth`만, Retrieval/ReAct는 필요한 tool 입력만, Report는 요약된 history만 받는다. 전체 대화 원문을 모든 agent에 그대로 전달하지 않는다.
- 근거: [rca-standards-review.md](../../rca-standards-review.md) §6.4.1·§6.4.5·§6.5·§7(roadmap §1).

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
- [현재] plan의 read-only tool을 `_run_plan_steps(...)`로 실행한다. 이 함수는 `RetrievalPlanStep.depends_on`을 해석하는 **wave 실행기**다: 선행 의존이 모두 끝난 step들은 같은 wave에서 `asyncio.gather(...)`로 병렬 호출하고, 의존이 남은 step은 선행 완료 후 다음 wave로 미룬다. 결과는 plan 순서대로 정렬해 반환하며, 미해결·순환 의존은 deadlock 대신 남은 step을 한꺼번에 실행해 흡수한다. (즉, 독립 tool 병렬 + 의존 tool 순차 chain을 모두 처리한다.)
- [현재] `mode`가 `simple_query`/`incident_analysis`이고 provider가 tool-calling을 지원하면, flat plan 외에 `run_tool_loop(...)`(ReAct 루프, agentic.py)을 추가로 실행한다. LLM이 직전 tool 결과를 보고 다음 read-only tool을 반복 호출(식별자 chaining)하며, 루프가 호출하지 않은 plan step만 이어서 실행한다. LLM 미연결 등으로 루프가 진전하지 못하면 기존 flat plan 경로로 폴백한다.
- [현재] 순수 지식 질의(planner가 fallback tool만 계획)는 RAG 근거가 있으면 운영 tool 호출 없이 단락(short-circuit)한다. 단, 명시적 운영 tool이 계획됐으면 knowledge 근거가 있어도 실제 운영 데이터를 조회한다(#478).
- 수집되는 대로 `evidence_collected` event를 스트리밍한다. State patch append는 `run_retrieval(...)` 반환 뒤 runner 단계에서 수행된다.
- 현재 Retrieval은 synthetic `store_ref`가 있는 `EvidenceItem` metadata를 만든다. raw evidence store 저장·hydrate는 아직 구현되어 있지 않으며 #480 범위다.
- redaction 상태를 기록한다.

[계획 §1] ReAct 루프·tool budget 제어 (roadmap §1):

- 현재 ReAct 루프는 모든 `simple_query`에서 켜지고 `max_steps=6`을 mode 무관하게 공유한다. Router가 넘긴 `allow_react_loop`·`max_tool_calls`로 루프 실행 여부와 tool 호출 수를 제한해, 단순 조회에서 의도치 않은 다중 tool 호출이 발생하지 않게 한다.
- 근거: [rca-standards-review.md](../../rca-standards-review.md) §6.2(drift 표)·§6.4.4.

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

- [현재] `run_verifier(mode, *, rca_out, retrieval_out, classifier_out, executor_out, report_body, evidence_repo)`는 mode와 전달된 입력 조합에 따라 분기한다.
  - `incident_analysis`: RCA 후보를 evidence matrix 기준으로 검증하고, catalog 밖 `root_cause_id`는 `fail`, 후보 없음은 `needs_revision`으로 회수한다.
  - `action_execution`/`approval_decision`: `_verify_action_execution`이 executor output을 검증한다. after evidence가 없거나, 실패/차단 결과의 사유가 비었거나 status·summary가 불일치하면 `needs_revision`을 낸다.
  - `report_body`가 전달되면 `_verify_report_body`가 Report 본문을 검증한다. 본문이 catalog 밖 root_cause_id를 포함하면 `fail`, RCA 검증 없이 root cause 결론을 포함하면 `needs_revision`이다.
  - `simple_query`: 조회 결과를 그대로 `pass` 처리하되, Report 본문이 전달되면 본문 검증을 수행한다.
- [현재] runner는 stage 단계에서 `executor_out`을 전달하고(`run_verifier(... executor_out=executor_out)`), Report 생성 뒤 한 번 더 `report_body=answer`까지 함께 전달해 본문을 검증한다. 즉 executor output·Report 본문 검증은 이미 구현되어 있다.
- 검증 결과 status에 대한 Supervisor loopback·예산(`fail_loops`/`gap_loops`) 처리는 [§15 Workflow Control](contract-workflow-control.md#15-contract-workflow-control)을 정본으로 한다.

출력 status:

- `pass`
- `fail`
- `needs_revision`

### 12. Report

책임:

- 사용자에게 최종 응답을 작성한다.
- 현재 `run_report(user_message, retrieval_out, mode, llm, rca_out, classifier_out)`은 retrieval evidence summary와 RCA/Classifier 진단 블록을 받아 plain answer를 만든다. runner는 Verifier 결과를 Supervisor에 기록해 `fail`/`needs_revision`이면 책임 Agent로 loopback하므로, 예산 안에서는 미통과 결과를 곧바로 Report로 보내지 않는다.
- [현재] Report 생성 뒤 runner는 `run_verifier(... report_body=answer, executor_out=executor_out)`를 호출해 Report 본문과(해당 mode면) 실행 결과를 검증한다. 즉 본문·실행 결과 검증은 [§13.11 Verifier](#11-verifier)에 구현되어 있다.
- 불확실성, evidence gap, escalation 필요성을 명확히 쓴다.

금지:

- raw secret, connection string, 원문 로그 과다 노출
- 새로운 원인 또는 조치 생성

---
