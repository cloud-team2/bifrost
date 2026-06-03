# FastAPI Agent — Agent Principles (§1)

> 요약은 [overview.md](./overview.md). 서버 설계는 [server-design.md](./server-design.md). 카탈로그/계약은 `catalog-*`·`contract-*`.

## 1. Agent Principles


### 1. 설계 방향

Bifrost Agent의 핵심 역할은 Kafka 기반 데이터 파이프라인 장애를 evidence 중심으로 분석하고, 검증 가능한 대응안을 만드는 것이다.

> LLM은 RCA Engine이 아니라 RCA Assistant다.

Agent는 장애 원인을 자유롭게 생성하지 않는다. 사전에 정의된 장애 유형, root cause catalog, evidence matrix, runbook, policy 안에서 후보를 좁히고 설명한다.

세부 기준은 다음 문서로 분리한다.

| 범주 | 문서 |
| --- | --- |
| 장애 유형 | [§6 Failure Types](catalog-failure-types.md#6-catalog-failure-types) |
| 장애 유형과 root cause 매핑 | [§7 Incident→RootCause Map](catalog-incident-root-cause-map.md#7-catalog-incidentrootcause-map) |
| root cause 후보 | [§8 Root Cause Catalog](catalog-root-causes.md#8-catalog-root-cause) |
| evidence 기준 | [§9 Evidence Matrix](catalog-evidence-matrix.md#9-catalog-evidence-matrix) |
| alert 병합 | [§10 Correlation Rules](catalog-correlation-rules.md#10-catalog-correlation-rules) |
| 대응 runbook | [§11 Remediation Runbooks](catalog-remediation-runbooks.md#11-catalog-remediation-runbooks) |
| 정책/승인 기준 | [§12 Policy Matrix](catalog-policy-matrix.md#12-catalog-policy-matrix) |
| Agent 역할 | [§13 Agent Roles](contract-agent-roles.md#13-contract-agent-roles) |
| State schema | [§14 State Schema](contract-state-schema.md#14-contract-state-schema) |
| workflow 제어 | [§15 Workflow Control](contract-workflow-control.md#15-contract-workflow-control) |
| streaming event | [§16 Streaming Events](contract-streaming-events.md#16-contract-streaming-events) |
| output schema | [§17 Output Schemas](contract-output-schemas.md#17-contract-output-schemas) |

Frontend-facing Agent API는 [§3 API Reference](../../api/fastapi.md), Spring Boot 내부 운영 API·실행 backend는 [Spring Boot DETAILS](../backend-springboot/overview.md), tool 목록과 매핑은 [§4 Tool Catalog](tool-catalog.md#4-tool-catalog)를 기준으로 한다.

### 2. 적용 범위

Agent가 직접 다루는 범위는 Bifrost가 관측하거나 제어할 수 있는 운영 영역이다.

Bifrost는 `project_id`를 기준으로 pipeline, dependency, Kafka topic/user, Kubernetes namespace/deployment의 소유권을 나눈다. 모든 Agent run과 tool call은 project scope 안에서만 evidence를 수집하고, Spring Boot Operations Backend가 resource ownership을 다시 검증한다.

포함 범위:

- source/sink dependency의 연결 상태, timeout, latency
- pipeline task, connector task, retry/backoff
- Kafka topic, consumer group, broker, Kafka Connect
- Kubernetes pod, deployment, event, resource pressure
- trace summary와 connector task trace
- 배포, 설정, schema, credential 변경 이력
- freshness, volume, duplicate, null rate 같은 데이터 품질 신호

직접 복구하지 않는 범위:

- 고객사 DB 내부 튜닝
- 고객사 API 서버 수정
- 임의 SQL 실행
- secret 원문 조회
- pod exec 또는 shell command
- 데이터 삭제성 작업

고객사 소유 영역으로 보이는 문제는 evidence와 영향 범위를 정리해 escalation한다.

### 3. 할루시네이션 방지 원칙

#### 3.1 원인 생성이 아니라 후보 선택

RCA Agent는 [§8 Root Cause Catalog](catalog-root-causes.md#8-catalog-root-cause)에 정의된 후보만 선택한다. catalog에 없는 가능성은 `UNKNOWN_WITH_EVIDENCE_GAP`으로 보고한다.

이 원칙은 문서의 표현 문제가 아니라 시스템 안전장치다. Agent가 원인명을 새로 만들 수 있으면 evidence matrix, runbook, policy guard와 연결되지 않아 검증이 깨진다.

#### 3.2 Evidence-first

모든 분석 단계는 먼저 evidence를 수집한 뒤 판단한다.

State에는 원문을 inline으로 넣지 않는다. 로그 원문, metric query 결과, trace, event payload는 Evidence Store에 저장하고 State에는 `evidence_id`, `store_ref`, `summary`, `redaction_status`만 둔다.

세부 schema는 [§14 State Schema](contract-state-schema.md#14-contract-state-schema)를 따른다.

#### 3.3 기준은 운영 데이터로 보정

“조건 N개 이상 만족”이나 “score threshold” 방식은 가능하지만, 숫자를 임의로 정하지 않는다.

기준 설정 순서:

1. 과거 incident와 replay data로 threshold를 보정한다.
2. required evidence가 없으면 confidence 상한을 둔다.
3. negative evidence가 있으면 confidence를 낮춘다.
4. 기준 변경은 catalog version과 test fixture에 반영한다.

원인별 required/supporting/negative evidence는 [§9 Evidence Matrix](catalog-evidence-matrix.md#9-catalog-evidence-matrix)를 기준으로 한다.

#### 3.4 검증 실패는 정상 경로

검증 실패는 예외가 아니라 workflow의 일부다.

- evidence가 부족하면 Retrieval로 돌아간다.
- incident scope가 불명확하면 Classifier로 돌아간다.
- action 위험도가 높으면 Remediation을 수정한다.
- Verifier가 `needs_revision`을 반환하면 책임 Agent로 되돌아간다.

자세한 분기 규칙은 [§15 Workflow Control](contract-workflow-control.md#15-contract-workflow-control)에 둔다.

### 4. Alert와 Incident 상관관계

Alert는 개별 이상 신호이고, Incident는 운영자가 대응하는 사건 단위다. 하나의 실제 장애가 여러 alert를 만들 수 있으므로 Agent 앞단에는 deterministic Correlation Engine을 둔다.

Correlation Engine은 다음 축을 본다.

- time window
- topology
- shared dependency
- common change
- symptom direction

여러 Incident가 하나의 근본 원인을 공유할 수 있다. 이 경우 `incident_group` scope를 만들고 RCA는 shared dependency, topology, common change 중 최소 하나 이상의 직접 evidence를 요구한다.

상세 병합 기준은 [§10 Correlation Rules](catalog-correlation-rules.md#10-catalog-correlation-rules)를 따른다.

### 5. Workflow 구성

Agent는 단일 만능 Agent가 아니라 역할이 분리된 workflow다. Supervisor는 State, 조건 분기, retry, timeout, approval gate, verification loop를 제어하는 control layer이며 그 자체는 LLM agent가 아니다.

workflow는 evidence 기반 판단·생성이 필요한 **LLM agent**와, 룰·도구 실행만 하는 **결정론적 단계**로 나뉜다.

LLM agent (8):

1. Router
2. Planner
3. Retrieval
4. Classifier
5. RCA
6. Remediation
7. Verifier
8. Report

결정론적 단계 (LLM 추론 없음):

- Correlation Engine: [§4 Alert와 Incident 상관관계](#4-alert와-incident-상관관계) / [§10 Correlation Rules](catalog-correlation-rules.md#10-catalog-correlation-rules)의 rule/score/window로 alert를 묶는다.
- Policy Guard: [§12 Policy Matrix](catalog-policy-matrix.md#12-catalog-policy-matrix) lookup으로 `allow`/`require_approval`/`require_change_management`/`deny`를 결정한다.
- Executor: 승인된 tool을 정해진 순서로 호출하는 도구 실행 오케스트레이터다.
- Approval Gate / Change Management Gate: 사람 승인과 변경관리 검증 단계다.

결정론적 단계를 LLM에서 빼는 이유는 두 가지다. 같은 입력에 같은 결정을 내려 **재현성**이 높아지고, LLM 호출을 critical path에서 줄여 응답이 빨라진다.

역할별 책임과 금지 행위는 [§13 Agent Roles](contract-agent-roles.md#13-contract-agent-roles)에 둔다.

Incident 분석의 표준 실행 순서는 다음과 같다.

```text
Router
  -> Correlation Engine
  -> Planner
  -> Retrieval
  -> Classifier
  -> RCA
  -> Remediation
  -> Policy Guard
  -> Approval / Change Management
  -> Executor
  -> Verifier
  -> Report
```

Classifier는 Retrieval이 수집한 evidence summary를 사용하므로 Retrieval 뒤에 둔다.

이 순서는 "항상 전체를 실행한다"는 뜻이 아니다. Router는 매 사용자 메시지마다 mode를 재판정하고, 기존 run State가 유효하면 재사용해 필요한 단계만 실행한다. `incident_analysis`는 기본적으로 원인까지만 보고하고(`diagnose_only`), 조치 후보 생성과 실행은 사용자가 요청할 때만 진행한다. 단순 질의·승인 처리·조치 실행은 더 짧은 경로를 탄다. 의도별 최소 실행 단계는 [§15 Workflow Control](contract-workflow-control.md#15-contract-workflow-control)를 따른다.

메인 workflow와 실패 시 되돌림 규칙은 [§15 Workflow Control](contract-workflow-control.md#15-contract-workflow-control)를 기준으로 한다.

### 6. State 설계

State는 namespace로 나눈다. 이유는 Agent별 소유권을 분리해 서로의 판단을 임의로 덮어쓰지 못하게 하기 위해서다.

핵심 namespace:

- `run`
- `incident`
- `correlation`
- `evidence`
- `analysis`
- `actions`
- `verification`
- `report`

State 변경은 patch 단위로 append한다. raw evidence는 State에 넣지 않고 Evidence Store reference만 남긴다.

상세 schema와 patch 규칙은 [§14 State Schema](contract-state-schema.md#14-contract-state-schema)를 따른다.

### 7. RCA 판단

RCA는 세 가지를 반드시 분리한다.

- required evidence
- supporting evidence
- negative evidence

Confidence는 “원인 확정도”가 아니라 “현재 evidence 기준 운영상 판단 신뢰도”다.

초기 해석:

| Confidence | 의미 |
| --- | --- |
| `>= 0.80` | 강한 후보 |
| `0.60 - 0.79` | 유력하지만 추가 확인 필요 |
| `< 0.60` | 확정 불가 |

이 값은 운영 데이터로 보정한다. 필수 evidence가 빠진 후보는 높은 confidence를 받을 수 없다.

### 8. 대응과 권한

Remediation Agent는 조치 후보만 만든다. 실제 실행 가능 여부는 Policy Guard와 Spring Boot Operations Backend가 판단한다.

정책 decision:

- `allow`
- `require_approval`
- `require_change_management`
- `deny`

조치 후보는 [§11 Remediation Runbooks](catalog-remediation-runbooks.md#11-catalog-remediation-runbooks)를 따르고, 위험도와 승인 기준은 [§12 Policy Matrix](catalog-policy-matrix.md#12-catalog-policy-matrix)를 따른다.

Executor는 승인되었거나 변경관리 검증을 통과한 tool만 실행한다. 실행 가능한 tool 목록은 [§4 Tool Catalog](tool-catalog.md#4-tool-catalog)에 둔다.

### 9. 사용자 경험

Agent는 최종 결과만 기다리게 하지 않고 진행 상태를 streaming한다.

사용자에게 보여줄 수 있는 것은 다음이다.

- 현재 Agent 단계
- 어떤 evidence를 수집 중인지
- 어떤 tool call이 완료되었는지
- 승인이 필요한지
- 검증이 통과했는지

보여주지 말아야 하는 것은 raw secret, connection string, 내부 prompt, hidden reasoning, 원문 로그 전문이다.

상세 event schema는 [§16 Streaming Events](contract-streaming-events.md#16-contract-streaming-events)를 따른다.

### 10. 모델 선택 원칙

모델은 벤더 고정이 아니라 역할별 tier로 선택한다.

| 역할 | 권장 |
| --- | --- |
| Router / Planner / Classifier / Remediation / Report | lightweight structured model |
| RCA / Verifier | reasoning-capable model + deterministic rule |
| Retrieval | RAG + tool orchestration, 생성 LLM 최소 |
| Correlation Engine / Policy Guard / Executor | deterministic rule, 생성 LLM 미사용 |

Policy Guard와 Executor는 LLM 추론 단계가 아니라 룰/도구 실행 단계다. 이 둘과 Correlation Engine을 LLM에서 빼면 재현성이 올라가고 LLM 호출 수가 줄어 전체 응답이 빨라진다.

모델보다 중요한 것은 State schema, catalog, tool allowlist, evidence contract, verifier다.

### 11. 결론

Bifrost Agent의 설계 핵심은 “잘 말하는 Agent”가 아니라 “증거 없이 말할 수 없는 Agent”를 만드는 것이다.

따라서 Agent 문서는 두 층으로 나눈다.

1. 이 파일은 판단 원리와 workflow 방향만 담는다.
2. 사전에 고정해야 하는 장애 유형, RCA 기준, runbook, policy, schema는 [§6](catalog-failure-types.md#6-catalog-failure-types)~[§12 catalogs](catalog-policy-matrix.md#12-catalog-policy-matrix)와 [§13](contract-agent-roles.md#13-contract-agent-roles)~[§17 contracts](contract-output-schemas.md#17-contract-output-schemas)에 둔다.

---
