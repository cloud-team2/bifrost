# FastAPI Agent Server — API 명세

> [DETAILS.md](../design/backend-fastapi.md)에서 분리한, Frontend가 AI 장애대응 화면에서 호출하는 FastAPI API 레퍼런스다. 요약은 [README.md](../design/backend-fastapi.md). 워크스페이스·DB·파이프라인 CRUD·모니터링 등 플랫폼 조회/관리 API는 [Spring Boot api.md](./springboot.md)가 제공한다.
>
> **섹션 참조 규칙**: 본문의 `§N(...)`은 별도 명시(이 문서의 §N)가 없으면 [DETAILS.md](../design/backend-fastapi.md)의 섹션(Tool Catalog·Workflow Control·Streaming Events 등)을 가리킨다.

---

### 1. 목적

이 문서는 Frontend가 **AI 장애대응 화면(BifrostAgentPanel 등)** 에서 호출하는 FastAPI Agent Server API를 정의한다. 워크스페이스·DB·파이프라인 CRUD, 인시던트/알럿 목록, 모니터링 같은 **플랫폼 조회/관리 API는 FastAPI가 아니라 Spring Boot 플랫폼 API**가 제공한다([Spring Boot api.md Part A](./springboot.md), [Frontend DETAILS](../design/frontend.md)).

FastAPI API는 Agent run을 시작하고, 진행 상태를 streaming하며, 승인/거절 입력과 최종 report 조회를 제공한다. Kubernetes, Kafka, Prometheus 같은 runtime resource는 직접 제어하지 않는다.

Spring Boot 내부 운영 API는 [Spring Boot DETAILS](../design/backend-springboot.md)를 기준으로 한다.

### 2. 공통 규칙

| 항목 | 규칙 |
| --- | --- |
| Base path | `/api/v1` |
| JSON field | snake_case |
| timestamp | ISO-8601 UTC |
| 인증 | 사용자 JWT 또는 session token |
| streaming | SSE 우선, WebSocket은 확장 |
| raw evidence | 반환하지 않음 |

### 3. 공통 Response

성공:

```json
{
  "ok": true,
  "request_id": "req_20260601_001",
  "data": {}
}
```

실패:

```json
{
  "ok": false,
  "request_id": "req_20260601_001",
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "project_id is required",
    "retryable": false
  }
}
```

### 4. 표준 Error Code

| Code | 설명 |
| --- | --- |
| `VALIDATION_FAILED` | 요청 schema 또는 parameter 오류 |
| `UNAUTHORIZED` | 인증 필요 |
| `FORBIDDEN` | project 또는 action 권한 없음 |
| `RUN_NOT_FOUND` | run을 찾을 수 없음 |
| `INCIDENT_NOT_FOUND` | incident를 찾을 수 없음 |
| `ACTION_NOT_FOUND` | action을 찾을 수 없음 |
| `APPROVAL_NOT_FOUND` | approval을 찾을 수 없음 |
| `RUN_ALREADY_CLOSED` | 완료/취소된 run에 변경 요청 |
| `POLICY_DENIED` | 정책상 실행 차단 |
| `SPRING_BACKEND_ERROR` | Spring Boot Operations Backend 호출 실패 |
| `LLM_PROVIDER_ERROR` | LLM provider 호출 실패 |
| `STREAM_UNAVAILABLE` | event stream 생성 실패 |

### 5. Health / Metadata API

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/v1/health` | FastAPI 서버 생존 상태를 확인한다. |
| `GET` | `/api/v1/ready` | LLM, Spring Boot, State Store, Evidence Store 연결 준비 상태를 확인한다. |
| `GET` | `/api/v1/version` | API version, build version, catalog version을 반환한다. |
| `GET` | `/api/v1/capabilities` | 사용 가능한 Agent mode, model tier, streaming 지원 여부를 반환한다. |

#### `GET /api/v1/ready`

응답:

```json
{
  "ok": true,
  "data": {
    "status": "ready",
    "dependencies": {
      "spring_operations": "ok",
      "llm_provider": "ok",
      "state_store": "ok",
      "evidence_store": "ok"
    }
  }
}
```

### 6. Agent Run API

| Method | Path | 설명 |
| --- | --- | --- |
| `POST` | `/api/v1/agent/runs` | 사용자 요청으로 새 Agent run을 생성한다. |
| `POST` | `/api/v1/agent/chat` | 대화형 메시지를 받아 run을 생성하거나 기존 run에 이어 붙인다. |
| `POST` | `/api/v1/agent/plan` | 실행 없이 분석 계획만 생성한다. |
| `POST` | `/api/v1/agent/incidents/{incident_id}/analyze` | Incident 기준 RCA run을 시작한다. |
| `GET` | `/api/v1/agent/runs` | 사용자의 Agent run 목록을 조회한다. |
| `GET` | `/api/v1/agent/runs/{run_id}` | run의 현재 상태와 요약을 조회한다. |
| `POST` | `/api/v1/agent/runs/{run_id}/messages` | 기존 run에 후속 사용자 메시지를 추가한다. |
| `POST` | `/api/v1/agent/runs/{run_id}/cancel` | 실행 중인 run을 취소한다. |
| `POST` | `/api/v1/agent/runs/{run_id}/retry` | 실패한 run을 지정 단계부터 재시도한다. |

#### `POST /api/v1/agent/runs`

새 Agent run을 생성한다.

요청:

```json
{
  "project_id": "proj_001",
  "mode": "incident_analysis",
  "remediation_requested": false,
  "message": "daily_user_sync 장애 원인을 분석해줘",
  "incident_id": "inc_001",
  "alert_ids": ["alert_001"],
  "stream": true
}
```

`mode`는 생략 가능하며, 생략하면 Router가 메시지로부터 추론한다. Router는 매 메시지마다 mode를 재판정하므로 같은 run에서도 turn마다 다른 부분집합만 실행된다. `incident_analysis`는 기본 `diagnose_only`(원인까지만)이고, 조치 후보까지 원하면 `remediation_requested=true`로 보낸다. 실제 조치 실행은 별도의 `action_execution`/approval 흐름을 거친다. 의도별 최소 실행 단계는 §15(Workflow Control) §4.1을 따른다.

응답:

```json
{
  "ok": true,
  "data": {
    "run_id": "run_20260601_001",
    "status": "running",
    "event_stream_url": "/api/v1/agent/runs/run_20260601_001/events"
  }
}
```

#### `POST /api/v1/agent/chat`

대화형 메시지를 처리한다. `run_id`가 없으면 새 run을 만들고, 있으면 기존 run에 메시지를 추가한다.

요청:

```json
{
  "project_id": "proj_001",
  "run_id": null,
  "message": "consumer lag가 왜 늘었는지 봐줘",
  "stream": true
}
```

#### `POST /api/v1/agent/plan`

실제 운영 tool 호출 없이 분석 계획을 생성한다. read-only preview는 정책에 따라 허용할 수 있다.

#### `POST /api/v1/agent/incidents/{incident_id}/analyze`

기존 Incident와 관련 alert를 기준으로 RCA run을 시작한다.

요청:

```json
{
  "project_id": "proj_001",
  "alert_ids": ["alert_001", "alert_002"],
  "scope": "auto"
}
```

#### `GET /api/v1/agent/runs/{run_id}`

run summary를 반환한다. raw evidence는 포함하지 않는다.

응답:

```json
{
  "ok": true,
  "data": {
    "run_id": "run_20260601_001",
    "status": "waiting_for_approval",
    "mode": "incident_analysis",
    "current_agent": "Policy Guard",
    "incident_id": "inc_001",
    "summary": "SOURCE_DB_CONNECTION_TIMEOUT 후보가 유력하며 connector restart는 승인 필요",
    "pending_approval_count": 1
  }
}
```

### 7. Event Streaming API

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/v1/agent/runs/{run_id}/events` | run 진행 상태를 SSE로 구독한다. |
| `GET` | `/api/v1/agent/runs/{run_id}/events/history` | 재연결용 event history를 조회한다. |

#### `GET /api/v1/agent/runs/{run_id}/events`

SSE stream을 반환한다.

예시:

```text
event: agent_started
data: {"run_id":"run_001","agent":"Retrieval","message":"근거 수집을 시작했습니다"}

event: tool_call_completed
data: {"tool":"get_pipeline_logs","evidence_id":"ev_log_001","summary":"extract task timeout log collected"}

event: approval_required
data: {"action_id":"act_001","approval_id":"appr_001","reason":"state-changing action"}
```

분석이 끝나기 전이라도 단계 완료 시점에 중간 결과를 보낸다(§4.2 지연 최소화). preview는 Verifier 통과 전이므로 "검증 전"으로 표시하고, 최종 `report`(Verifier 통과분)와 구분한다.

```text
event: report_preview_available
data: {"run_id":"run_001","root_cause_id":"SOURCE_DB_CONNECTION_TIMEOUT","confidence":0.82,"verified":false}
```

전체 event 종류는 [DETAILS §16](../design/backend-fastapi.md#16-contract-streaming-events)를 따른다.

### 8. State / Timeline API

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/v1/agent/runs/{run_id}/state/summary` | UI 표시용 State 요약을 조회한다. |
| `GET` | `/api/v1/agent/runs/{run_id}/timeline` | Agent 단계, tool call, 승인, 실행 기록 timeline을 조회한다. |
| `GET` | `/api/v1/agent/runs/{run_id}/steps` | Agent step별 상태를 조회한다. |
| `GET` | `/api/v1/agent/runs/{run_id}/actions` | run에서 생성된 action 후보와 상태를 조회한다. |

#### `GET /api/v1/agent/runs/{run_id}/timeline`

응답:

```json
{
  "ok": true,
  "data": {
    "items": [
      {
        "type": "agent_completed",
        "agent": "Retrieval",
        "message": "5 evidence items collected",
        "created_at": "2026-06-01T00:15:00Z"
      }
    ]
  }
}
```

### 9. Evidence API

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/v1/agent/runs/{run_id}/evidence` | run에서 사용된 evidence metadata 목록을 조회한다. |
| `GET` | `/api/v1/agent/runs/{run_id}/evidence/{evidence_id}` | evidence summary와 redaction 상태를 조회한다. |
| `POST` | `/api/v1/agent/runs/{run_id}/evidence/{evidence_id}/hydrate` | 권한이 있는 운영자에게 제한적 evidence 상세를 제공한다. |

Evidence API는 기본적으로 raw content를 반환하지 않는다. `hydrate`도 redaction과 권한 검사를 통과해야 한다.

### 10. Approval API

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/v1/approvals` | 사용자가 처리할 approval 목록을 조회한다. |
| `GET` | `/api/v1/approvals/{approval_id}` | approval 상세와 action 요약을 조회한다. |
| `POST` | `/api/v1/approvals/{approval_id}/decision` | approval을 승인 또는 거절한다. |
| `GET` | `/api/v1/agent/runs/{run_id}/approvals` | 특정 run의 approval 요청 목록을 조회한다. |
| `POST` | `/api/v1/agent/runs/{run_id}/approvals/{approval_id}/decision` | run context에서 approval 결정을 기록한다. |

> **Approval source of truth는 Spring Boot다.** 이 API는 프론트용 facade로, [Spring Boot Approval API](../design/backend-springboot.md#19-approval-api)를 호출하고 run 연계 메타데이터(run_id↔approval_id, UI 표시)만 보관한다. approval record(params hash·승인자·만료·single-use)의 원본·검증·감사는 Spring Boot가 집행한다. 동일 approval을 양쪽에 중복 생성하지 않는다.

요청:

```json
{
  "decision": "approved",
  "comment": "영향 범위 확인 후 승인"
}
```

응답:

```json
{
  "ok": true,
  "data": {
    "approval_id": "appr_001",
    "status": "approved",
    "approved_action_ids": ["act_001"]
  }
}
```

### 11. Change Management API

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/v1/change-tickets/{change_ticket_id}` | change ticket 상태, 실행 window, rollback plan 요약을 조회한다. |
| `POST` | `/api/v1/agent/runs/{run_id}/actions/{action_id}/change-ticket` | 고위험 action에 change ticket을 연결한다. |
| `POST` | `/api/v1/agent/runs/{run_id}/actions/{action_id}/execution-window` | 실행 window 정보를 연결한다. |
| `POST` | `/api/v1/agent/runs/{run_id}/actions/{action_id}/rollback-plan` | rollback plan을 연결한다. |

FastAPI는 change ticket을 최종 검증하지 않는다. Spring Boot가 실행 직전에 다시 검증한다.

### 12. Action Execution API

| Method | Path | 설명 |
| --- | --- | --- |
| `POST` | `/api/v1/agent/runs/{run_id}/actions/{action_id}/execute` | 승인되었거나 변경관리 검증 대상인 action 실행을 요청한다. |
| `GET` | `/api/v1/agent/runs/{run_id}/actions/{action_id}/execution` | action 실행 결과와 audit id를 조회한다. |
| `POST` | `/api/v1/agent/runs/{run_id}/actions/{action_id}/verify` | 실행 후 verifier를 다시 실행한다. |

#### `POST /api/v1/agent/runs/{run_id}/actions/{action_id}/execute`

FastAPI가 State와 approval 정보를 확인한 뒤 Spring Boot Operations API를 호출한다.

요청:

```json
{
  "approval_id": "appr_001",
  "change_ticket_id": null
}
```

### 13. Report API

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/v1/agent/runs/{run_id}/report` | 최종 report를 조회한다. |
| `GET` | `/api/v1/agent/runs/{run_id}/report/preview` | verifier 통과 전 preview 가능한 요약을 조회한다. |
| `POST` | `/api/v1/agent/runs/{run_id}/report/regenerate` | 검증된 State 기준으로 report를 다시 생성한다. |
| `GET` | `/api/v1/incidents/{incident_id}/reports` | incident에 연결된 report 목록을 조회한다. |

Report는 Verifier가 `approved_for_final_response=true`로 승인한 내용만 포함한다.

### 14. Incident / Alert (소유권)

incident·alert의 **저장과 목록/상세 조회는 Spring Boot 플랫폼 API**가 담당한다(메타데이터 DB의 `incident`/`event` 테이블 — [Spring Boot DETAILS §4 Data Model](../design/backend-springboot.md#4-data-model), [Spring Boot api.md Part A](./springboot.md)). Frontend의 AlertsView 목록도 Spring Boot를 호출한다.

FastAPI는 그 incident를 **분석/대응**하는 역할만 하며, 진입점은 §6의 `POST /api/v1/agent/incidents/{incident_id}/analyze` 하나다(여기서 중복 정의하지 않는다).

alert 상관관계는 Correlation Engine(deterministic)이 처리하며, run 내부 단계로 동작한다(§15(Workflow Control)). 별도 외부 `/alerts/correlate` 미리보기는 v1에서 제공하지 않는다.

### 15. Catalog / Tool Metadata API

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/v1/catalogs/failure-types` | 장애 유형 catalog를 조회한다. |
| `GET` | `/api/v1/catalogs/incident-root-cause-map` | incident type과 root cause 후보 매핑을 조회한다. |
| `GET` | `/api/v1/catalogs/root-causes` | root cause catalog를 조회한다. |
| `GET` | `/api/v1/catalogs/policies` | policy decision 기준을 조회한다. |
| `GET` | `/api/v1/tools` | Agent 논리 tool catalog를 조회한다. |
| `GET` | `/api/v1/tools/{tool_name}` | 특정 tool의 risk와 parameter schema를 조회한다. |

이 API는 UI 설명, 디버깅, 운영자 검토용이다.

### 16. Feedback / Audit UI API

| Method | Path | 설명 |
| --- | --- | --- |
| `POST` | `/api/v1/agent/runs/{run_id}/feedback` | 사용자가 RCA/report 품질 피드백을 남긴다. |
| `GET` | `/api/v1/agent/runs/{run_id}/audit-events` | run과 연결된 audit event 요약을 조회한다. |
| `GET` | `/api/v1/audit-events/{audit_event_id}` | 특정 audit event 요약을 조회한다. |

Audit raw record의 source of truth는 Spring Boot다. FastAPI는 UI 요약을 제공한다.

### 17. Admin API

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/v1/admin/models` | 설정된 LLM model tier와 상태를 조회한다. |
| `GET` | `/api/v1/admin/dependencies` | Spring Boot, evidence store, state store 등 dependency 상태를 조회한다. |
| `POST` | `/api/v1/admin/runs/{run_id}/replay` | 저장된 State patch로 run replay를 시작한다. |
| `POST` | `/api/v1/admin/catalogs/reload` | catalog cache를 reload한다. |

Admin API는 운영자 권한이 필요하다.

### 18. 금지 API

FastAPI에는 다음 API를 만들지 않는다.

- Kubernetes resource 직접 patch/apply/delete
- Kafka topic 직접 생성/삭제
- Kafka Connect REST 직접 호출
- Prometheus 직접 query를 외부에 노출
- Secret 원문 조회
- pod exec 또는 shell 실행
- arbitrary SQL 실행

필요한 운영 기능은 Spring Boot Operations API와 tool registry를 통해서만 제공한다.
