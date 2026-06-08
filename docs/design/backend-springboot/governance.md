# Spring Boot Operations Backend — Governance Engine (운영 조치 집행)

> 요약은 [overview.md](./overview.md). 이 파일은 agent-facing mutation의 **집행 파이프라인**(정책·승인·변경관리·멱등성·감사·증거)을 구현 수준으로 다룬다. **무엇을 실행할 수 있는지(allowlist)의 정본은 [server.md §7.1](./server.md#71-operation-allowlist-집행-경계-단일-출처)**, 개념 흐름은 [server.md §4·§6~§10](./server.md#1-server-design)이며 이 문서는 그 구현이다.
>
> 패키지: `internalops`(요청 표면) + `governance`(policy·approval·changemanagement·idempotency·audit·evidence) ([server.md §5](./server.md#5-패키지-구조)).

## 7. Governance Engine

### 1. 목적·범위

FastAPI Agent가 `/internal/ops`로 보낸 **mutation 요청을 실제 runtime에 반영하기 전, 모든 게이트를 통과시키는 집행 계층**. read-only 조회는 scope만 통과하면 바로 실행하고, mutation은 이 엔진을 반드시 거친다. **FastAPI의 Policy Guard는 사전 판단(미러)일 뿐, 집행·SoT는 여기**다([server.md §3 신뢰 경계](./server.md#3-신뢰-경계) — TOCTOU·confused deputy 방어).

### 2. 집행 파이프라인

`internalops` controller가 받은 mutation 요청은 아래 순서로 통과하며, **하나라도 실패하면 즉시 차단 + audit**한다(부수효과 전에 끝낸다).

```text
[1] service identity   FastAPI service account인가                  → 401/403
[2] scope/ownership    project_id 멤버십 + resource 소유             → WORKSPACE_FORBIDDEN
[3] idempotency        X-Idempotency-Key 조회                        → replay / CONFLICT / 진행중 status
[4] policy             operation → allowlist decision(§3)            → POLICY_DENIED
[5] approval/change    decision별 승인·티켓 재검증(§4·§5)            → APPROVAL_* / CHANGE_*
[6] before-evidence    실행 전 snapshot 저장                          → evidence_ref
[7] execute            Resource Adapter 호출(Fabric8/Kafka/Connect)  → runtime
[8] after-evidence     실행 후 snapshot 저장
[9] audit              성공/실패/차단을 audit_event에 기록
[10] response          operation/evidence 봉투로 반환
```

차단은 실패가 아니라 정상 경로다. 모든 분기(성공·차단)는 [9] audit를 거친다.

### 3. Policy Guard (`governance.policy`)

operation을 [server.md §7.1 allowlist](./server.md#71-operation-allowlist-집행-경계-단일-출처)에서 lookup해 `allow`/`require_approval`/`require_change_management`/`deny`를 정한다.

- **allowlist는 정적 등록**(operation catalog). 미등록 `runtime_tool`·금지 목록은 **deny**. 불명확하면 더 안전한 쪽으로 올린다(낮추지 않음).
- decision은 operation 유형에서 파생([server.md §7](./server.md#1-server-design)): read=allow, runtime state change=approval, replay/rollback/config=change management, delete/exec/SQL/secret=deny.
- FastAPI catalog와 **decision이 어긋나면 Spring 기준이 우선**한다. `GET /internal/ops/admin/tool-catalog`로 런타임 allowlist를 노출([api §25](../../api/springboot.md)).

### 4. Approval 검증 (`governance.approval`)

**Approval record의 원본·검증·감사는 Spring(SoT)**, FastAPI는 facade(run↔approval 연계·UI 캐시만 — [fastapi server-design §9](../backend-fastapi/server-design.md#2-server-design)). 실행 직전 다음을 **모두** 만족해야 한다.

| # | 검증 | 실패 코드 |
| --- | --- | --- |
| 1 | approval id 존재 | RESOURCE_NOT_FOUND |
| 2 | status=`approved` | APPROVAL_REQUIRED |
| 3 | approval.action_id == 요청 action_id | APPROVAL_SCOPE_MISMATCH |
| 4 | approval.operation == 실제 operation | APPROVAL_SCOPE_MISMATCH |
| 5 | **params_hash 일치** | APPROVAL_SCOPE_MISMATCH |
| 6 | 승인자 권한(project/resource) | PERMISSION_DENIED |
| 7 | 미만료(expiry) | APPROVAL_EXPIRED |
| 8 | single-use(미사용) | CONFLICT |

- **params_hash**: 실행 parameter를 **정규화 JSON(키 정렬·whitespace 제거·null 규칙 고정) → SHA-256**. 승인 시점 hash와 실행 시점 hash가 다르면 "승인 후 변조"로 보고 차단(§4.5). 정규화 규칙은 FastAPI와 동일 고정([fastapi tool-catalog §14](../backend-fastapi/tool-catalog.md#4-tool-catalog)).
- 승인 절차: Policy Guard `require_approval` → `POST /approvals`로 request 생성 → 사용자가 `POST /approvals/{id}/decision`(HITL) → Executor가 위 8개 재검증 후 실행.

### 5. Change Management 검증 (`governance.changemanagement`)

`require_change_management`(rollback/backfill/topic·user·config 변경)는 approval보다 강하다. 모두 충족해야 실행한다.

| 검증 | 실패 코드 |
| --- | --- |
| change ticket 존재·status=approved | CHANGE_TICKET_REQUIRED |
| 현재 시각이 execution window 안 | CHANGE_WINDOW_CLOSED |
| rollback plan 존재 | CHANGE_TICKET_REQUIRED |
| impact analysis 존재 | CHANGE_TICKET_REQUIRED |
| requested operation ∈ ticket scope | CHANGE_SCOPE_MISMATCH |

외부 변경관리 시스템이 있으면 `changemanagement` adapter가 연동, 없으면 자체 `change_ticket` 테이블(§9).

### 6. Idempotency (`governance.idempotency`)

모든 mutation은 `X-Idempotency-Key` 필수. 키+요청 fingerprint를 저장해 재시도/중복을 흡수한다.

| 상황 | 처리 |
| --- | --- |
| 같은 key + 같은 params_hash | 저장된 **이전 response replay** (`IDEMPOTENCY_REPLAY`) |
| 같은 key + 다른 params_hash | `CONFLICT` |
| key 없음 | `VALIDATION_FAILED` |
| 같은 key 실행 중(in-flight) | 기존 execution status 반환 |

- **mutation timeout 시 자동 재시도 금지** — read-only after-check로 실제 반영 여부를 확인한다(중복 실행 방지).
- 키는 `(project_id, idempotency_key)` 유니크. 응답 스냅샷·status를 함께 저장(§9).

### 7. Audit & Evidence (`governance.audit`·`governance.evidence`)

- **audit_event(append-only)**: 모든 요청을 성공·실패·차단 불문 기록 — request_id·run_id·actor·operation·target·**policy_decision**·approval/ticket id·idempotency_key·before/after evidence id·result_status·error_code([data-model §3.8](./data-model.md#4-data-model)).
- **Evidence Writer**: 실행 전후 snapshot을 Evidence Store에 저장하고 **reference만** 반환한다(원문 미반환). evidence_ref는 metadb([data-model §3.9](./data-model.md#4-data-model)), 원문은 Evidence Store. mutation 성공 응답은 before/after evidence id를 포함해야 한다.

### 8. internalops 요청 표면 (`internalops`)

- **헤더**: `X-Agent-Run-Id`·`X-Agent-Step-Id`·`X-Agent-Name`·`X-Request-Id`·`X-Actor-Type`·`X-Actor-Id`, mutation은 `X-Idempotency-Key` 추가([api §3](../../api/springboot.md)).
- **응답 봉투**: 내부 운영 API는 `{ ok, request_id, operation, result, evidence[], audit_event_id }`(플랫폼 `/api/v1`의 `{ok,data}`와 다름). 실패는 `error{code, retryable, required_action}`([api §4·§5](../../api/springboot.md)).
- **인증**: FastAPI service identity만. 사용자 권한은 FastAPI 전달값을 믿지 않고 재확인(scope/ownership).

### 9. 데이터 모델 (governance 소유, metadb)

플랫폼 메타데이터([data-model.md](./data-model.md#4-data-model))와 별개로 거버넌스 집행 record를 둔다(Approval SoT=Spring).

**`approval`**

| 컬럼 | 타입 | 설명 |
| --- | --- | --- |
| `id` | uuid PK | |
| `workspace_id` | uuid FK | |
| `run_id` / `action_id` | text | agent run·action 연계 |
| `operation` | text | 승인된 operation |
| `params_hash` | text | 승인 당시 정규화 params SHA-256 |
| `status` | text | `pending`/`approved`/`rejected`/`expired` |
| `approver` | uuid null | 승인자(app_user) |
| `expires_at` | timestamptz | |
| `consumed_at` | timestamptz null | single-use 소비 시각 |

**`change_ticket`**

| 컬럼 | 타입 | 설명 |
| --- | --- | --- |
| `id` | uuid PK | |
| `workspace_id` | uuid FK | |
| `status` | text | `approved` 등 |
| `window_start` `window_end` | timestamptz | execution window |
| `rollback_plan` `impact_analysis` | text | 필수 |
| `scope` | jsonb | 허용 operation/resource |

**`idempotency_key`**

| 컬럼 | 타입 | 설명 |
| --- | --- | --- |
| `idempotency_key` | text | `(workspace_id, idempotency_key)` 유니크 |
| `workspace_id` | uuid FK | |
| `params_hash` | text | 요청 fingerprint |
| `status` | text | `in_flight`/`completed` |
| `response_snapshot` | jsonb | replay용 |
| `created_at` | timestamptz | |

> `audit_event`·`evidence_ref`는 [data-model §3.8·§3.9](./data-model.md#4-data-model). approval/change/idempotency는 governance 소유로 여기 정의한다.

### 10. 구현 메모

- **TOCTOU 방어**: 정책·승인·params_hash·idempotency는 **부수효과 직전 트랜잭션 안에서 재검증**한다(사전 판단 신뢰 금지). single-use approval 소비와 실행은 같은 트랜잭션 경계로 묶어 race를 막는다.
- **단락(short-circuit)**: 게이트 실패 시 adapter 호출 전에 중단하고 audit만 남긴다.
- **read-only after-check**: mutation timeout/불확실 시 재실행 대신 상태 조회로 반영 여부 확인.
- **테스트 기준**(server.md §13 확장): (a) approval 없는 mutation 차단, (b) params_hash 불일치 차단, (c) change window 밖 차단, (d) 같은 idempotency key replay가 중복 실행 안 만듦, (e) 같은 key+다른 params=CONFLICT, (f) before/after evidence 생성, (g) 금지 operation은 endpoint 부재 또는 deny, (h) 모든 분기 audit 기록.
