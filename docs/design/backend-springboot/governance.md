# Spring Boot Operations Backend — Governance Engine

> 요약은 [overview.md](./overview.md). 이 파일은 현재 구현된 `/internal/ops` governance, approval, change-ticket, mutation/idempotency 계약을 코드 기준으로 정리한다. API 상세는 [Spring Boot API §6](../../api/springboot.md#6-internal-ops-read--governance--mutation-api), 문자열 에러 코드는 [error-codes.md](../../api/error-codes.md#internal-ops-문자열-코드)를 따른다.

## 7. Governance Engine

### 1. 현재 구현 범위

현재 Spring Boot에 구현된 agent-facing 표면은 세 부류다.

| 범위 | 구현 |
| --- | --- |
| runtime tools | `/internal/ops/admin/tool-catalog`의 18개 read operation + 4개 mutation operation과 health/ready/version |
| governance facade | `/internal/ops/approvals/**`, `/internal/ops/change-tickets/**` |
| mutation subset | connector restart/pause/resume, Kafka Connect-managed consumer group restart |

`/internal/ops/**`는 agent-facing 내부 API이며 public frontend ingress에 노출하지 않는다. `SecurityConfig`는 `internal.ops.token`과 `X-Internal-Token` service identity header 일치를 요구하며, 토큰이 비어 있으면 fail-closed로 거부한다. 로컬/테스트 우회는 `internal.ops.auth-disabled=true`일 때만 명시적으로 허용된다. Mutation controller가 추가로 적용하는 gate는 agent header, workspace/resource ownership, idempotency, `PolicyGuard` 기반 approval 또는 change-ticket, Kafka Connect REST 결과 mapping이다.

### 2. Mutation 처리 순서

`InternalOpsMutationController` 기준 처리 순서:

```text
[1] request id 산출
[2] X-Agent-Run-Id / X-Agent-Step-Id / X-Idempotency-Key 필수 header 검사
[3] workspace namespace 기반 project 조회
[4] connector 또는 consumer group ownership 검사
[5] X-Approval-Id / X-Change-Ticket-Id UUID 형식 검사
[6] IdempotencyGuard.check(tenantId, operation, paramsHash)
[7] MutationGate.executeChecked
[8] PolicyGuard.evaluate 결과에 따라 allow, approval, change-ticket, deny 분기
[9] 필요한 경우 ApprovalValidator 또는 ChangeTicketValidator 검증
[10] before evidence 저장 후 Kafka Connect REST mutation 실행
[11] after/error evidence와 audit event 기록
[12] idempotency row에 response snapshot 저장
[13] OpsEnvelope 반환
```

`MutationGate` 안에서 발생한 실행 성공/실패는 evidence와 audit event를 남긴다. 다만 controller가 반환하는 `OpsEnvelope.evidence` 기본값은 빈 배열이고 `auditEventId` 값은 null이라 JSON 응답에서는 `audit_event_id` field가 생략된다. 헤더/ownership/idempotency 선검증에서 차단된 요청은 `MutationGate`에 들어가기 전 반환될 수 있다.

### 3. Header와 Envelope

| Header | 현재 동작 |
| --- | --- |
| `X-Agent-Request-Id` | request id 우선 후보 |
| `X-Request-Id` | `X-Agent-Request-Id`가 없을 때 request id 후보 |
| `X-Agent-Run-Id` | mutation 필수 |
| `X-Agent-Step-Id` | mutation 필수 |
| `X-Idempotency-Key` | mutation 필수 |
| `X-Approval-Id` | approval 정책이 필요한 mutation에서 사용. 누락 시 gate에서 403 `APPROVAL_REQUIRED` |
| `X-Change-Ticket-Id` | change-management 정책이 필요한 mutation에서 사용. 누락 시 gate에서 403 `CHANGE_TICKET_REQUIRED` |
| `X-Agent-Name`, `X-Agent-Id`, `X-Actor-Type`, `X-Actor-Id` | FastAPI가 보낼 수 있다. `X-Agent-Id`는 audit actor 후보이며, 필수 헤더 검증 대상은 아님 |

응답은 `OpsEnvelope`다. JSON field는 `request_id`, `audit_event_id`, `error.required_action`처럼 snake_case로 직렬화된다.

### 4. Approval facade

Approval source of truth는 Spring Boot `approval` 테이블이다.

| Endpoint | 구현 |
| --- | --- |
| `POST /internal/ops/approvals` | `tenantId`, `toolName`, `paramsHash`, `requiredApprover`, `expiresInMinutes`로 approval 생성. unknown field 거부 |
| `POST /internal/ops/approvals/preapproved` | ai-service HITL 통과 후 내부 caller가 Spring mutation용 `APPROVED` approval을 생성 |
| `POST /internal/ops/approvals/{approvalId}/decision` | `decision`, `tenantId`, `decidedBy`, `comment` 처리. `SecurityContext` principal이 필요 |
| `POST /internal/ops/approvals/{approvalId}/validate` | `tenantId`, `paramsHash`로 single-use 검증/소비 |
| `GET /internal/ops/approvals/{approvalId}?tenantId=` | 단건 조회 |
| `GET /internal/ops/approvals?tenantId=&status=&actorId=&limit=` | 목록 조회. status 기본 `PENDING`, limit 1..500 |

실행 직전 mutation controller는 `ApprovalValidator.validateAndConsume()`로 tenant, operation, params hash, expiry, single-use를 확인한다.

### 5. Change-ticket facade

Spring change-ticket 구현은 자체 `change_ticket` row를 만들고 승인된 실행 window 안에서만 검증한다.

| Endpoint | 구현 |
| --- | --- |
| `POST /internal/ops/change-tickets` | 인증된 requester가 `tenantId`, `title`, `scopeOperation`(alias `scope_operation`, `toolName`), execution window, rollback plan, impact, `requiredApprover`로 ticket 생성. requester와 approver가 같으면 거부한다. 응답 status는 `pending` |
| `POST /internal/ops/change-tickets/{changeTicketId}/approve` | 인증된 approver가 `OPEN -> APPROVED`로 전이. tenant·approvedBy·requiredApprover를 모두 대조하고 row를 pessimistic lock으로 조회한다 |
| `POST /internal/ops/change-tickets/{changeTicketId}/validate` | `tenantId`, operation scope, `APPROVED`, 승인 메타데이터, execution window, rollback plan, impact를 검증 |
| `GET /internal/ops/change-tickets/{changeTicketId}?tenantId=` | 단건 조회 |

Mutation controller는 `X-Change-Ticket-Id`를 `MutationContext`로 전달한다. `PolicyGuard`가 `REQUIRE_CHANGE_MANAGEMENT`를 반환한 operation은 `MutationGate`가 `ChangeTicketValidator.validate(changeTicketId, tenantId, operation)`을 통과해야 실행된다. invalid/missing ticket, future/expired window, rollback/impact/scope 누락, scope mismatch는 모두 fail-closed다.

### 6. Idempotency

모든 mutation은 `X-Idempotency-Key`를 요구한다. Guard는 tenant, operation, params hash를 기준으로 중복 요청을 처리한다.

| 상황 | 처리 |
| --- | --- |
| 새 key | `PROCESSING` row 생성 후 실행 |
| 같은 key + 같은 operation/params + 완료 | 저장된 response replay. cached JSON parse가 성공하면 원래 result status를 그대로 반환하고, `IDEMPOTENCY_REPLAY`는 fallback result 구성 시에만 사용 |
| 같은 key 실행 중 | 409 `CONFLICT` |
| 같은 key + 다른 operation/params | 409 `CONFLICT` |
| replay snapshot approval id가 현재 `X-Approval-Id`와 다름 | 403 `APPROVAL_SCOPE_MISMATCH` |
| replay snapshot change ticket id가 현재 `X-Change-Ticket-Id`와 다름 | 403 `CHANGE_TICKET_REQUIRED` |

Kafka Connect REST timeout은 504 `TIMEOUT`, 그 외 상류 실패는 502 `UPSTREAM_UNAVAILABLE`로 snapshot에 저장되어 replay될 수 있다.

### 7. Mutation allowlist

현재 구현된 mutation endpoint:

| Operation | Path | Gate |
| --- | --- | --- |
| `restart_connector` | `POST /internal/ops/projects/{projectId}/connectors/{connectorName}/restart` | idempotency + policy. high risk이므로 `aiProdLock=true`이면 approval |
| `pause_connector` | `POST /internal/ops/projects/{projectId}/connectors/{connectorName}/pause` | idempotency + policy allow |
| `resume_connector` | `POST /internal/ops/projects/{projectId}/connectors/{connectorName}/resume` | idempotency + policy allow |
| `restart_consumer_group` | `POST /internal/ops/projects/{projectId}/kafka/consumer-groups/{consumerGroup}/restart` | idempotency + policy. high risk이므로 `aiProdLock=true`이면 approval |

이외 deployment scale, rollback, backfill, topic config patch, KafkaUser ACL patch, pod exec, arbitrary SQL, secret raw read 같은 operation은 현재 Spring endpoint가 없다. Secret 원문 read는 정책상 금지이며 Kafka principal secret API도 `MASKED_REFERENCE_ONLY`만 반환한다.

### 8. Runtime tool catalog

`GET /internal/ops/admin/tool-catalog`는 18개 read operation과 4개 mutation operation을 함께 반환한다. 상세 목록은 [Spring Boot API §6.1](../../api/springboot.md#61-runtime-tool-catalog)을 따른다.

### 9. 현재 제약과 미연결 지점

아래 항목은 현재 코드 계약에 포함되지 않는다.

| 항목 | 현재 상태 |
| --- | --- |
| `/internal/ops/**` public ingress 노출 | 미노출. frontend nginx는 `/internal/ops/**`를 프록시하지 않고, FastAPI가 내부 서비스 경로로 호출 |
| service identity secret rotation/JWKS화 | 현재는 단일 shared secret(`internal.ops.token`/`AI_INTERNAL_OPS_TOKEN`) 일치 검사 |
| policy matrix lookup으로 allow/approval/change/deny 결정 | `PolicyGuard` 코드 상수로 결정한다. DB 기반 policy matrix는 없음 |
| before/after evidence writer | `MutationGate`가 evidence row를 저장하지만 mutation 응답 `evidence` 배열에는 연결하지 않음 |
| audit_event append-only 기록 | `MutationGate`가 audit row를 저장하지만 `OpsEnvelope.auditEventId` 값은 null이라 JSON 응답에서 `audit_event_id` field 생략 |
| Kubernetes/Prometheus/Schema Registry mutation | endpoint 없음 |

### 10. 테스트 기준

현재 구현 기준 regression 대상:

- tool catalog는 18개 read operation과 4개 mutation operation을 함께 포함하며 FastAPI 전용 alias와 미구현 operation은 포함하지 않는다.
- mutation은 `X-Agent-Run-Id`, `X-Agent-Step-Id`, `X-Idempotency-Key` 누락 시 400 `VALIDATION_FAILED`.
- high-risk mutation은 `aiProdLock=true`일 때 `X-Approval-Id` 누락 시 403 `APPROVAL_REQUIRED`.
- approval params hash/tenant/operation 불일치와 expired/used 상태가 차단된다.
- idempotency replay가 중복 Connect REST 호출을 만들지 않는다.
- 같은 key + 다른 params는 409 `CONFLICT`.
- connector/project ownership 불일치는 `RESOURCE_NOT_OWNED_BY_PROJECT` 또는 not found code로 차단된다.
