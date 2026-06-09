# FastAPI Agent Server — API 명세

> [DETAILS.md](../design/backend-fastapi/overview.md)에서 분리한, Frontend가 AI 장애대응 화면에서 호출하는 FastAPI API 레퍼런스다. 요약은 [README.md](../design/backend-fastapi/overview.md). 워크스페이스·DB·파이프라인 CRUD·모니터링 등 플랫폼 조회/관리 API는 [Spring Boot api.md](./springboot.md)가 제공한다.
>
> **섹션 참조 규칙**: 본문의 `§N(...)`은 별도 명시(이 문서의 §N)가 없으면 [DETAILS.md](../design/backend-fastapi/overview.md)의 섹션(Tool Catalog·Workflow Control·Streaming Events 등)을 가리킨다.

---

### 1. 목적

이 문서는 Frontend가 **AI 장애대응 화면(BifrostAgentPanel 등)** 에서 호출하는 FastAPI Agent Server API를 정의한다. 워크스페이스·DB·파이프라인 CRUD, 인시던트/알럿 목록, 모니터링 같은 **플랫폼 조회/관리 API는 FastAPI가 아니라 Spring Boot 플랫폼 API**가 제공한다([Spring Boot API Reference](./springboot.md), [Frontend DETAILS](../design/frontend.md)).

FastAPI API는 Agent run을 시작하고, 진행 상태를 streaming하며, 승인/거절 입력과 최종 report 조회를 제공한다. Kubernetes, Kafka, Prometheus 같은 runtime resource는 직접 제어하지 않는다.

Spring Boot 내부 운영 API는 [Spring Boot DETAILS](../design/backend-springboot/overview.md)를 기준으로 한다.

### 2. 공통 규칙

| 항목 | 규칙 |
| --- | --- |
| Base path | `/api/v1` |
| JSON field | snake_case |
| timestamp | ISO-8601 UTC |
| 인증 | 사용자 JWT 또는 session token |
| streaming | SSE 우선, WebSocket은 확장 |
| raw evidence | 반환하지 않음 |

### 2.1 구현 상태 기준

이 문서의 구현 상태는 2026-06-09 코드 확인 기준이다. 기존 codex docgap 감사는 미구현 38건·drift 10건(총 48건)을 보고했으며, 아래 상태 표기는 그 결과를 출발점으로 삼되 현재 `main.py` mount와 `routes_*.py` 구현을 다시 대조한 값이다.

- mount 정본: `services/ai-service/app/main.py:45-53`는 `routes_health`, `routes_agent`, `routes_events`, `routes_actions`, `routes_approvals`, `routes_change`, `routes_reports`만 include한다.
- 구현 route 정본: `services/ai-service/app/api/routes_health.py`, `routes_agent.py`, `routes_events.py`, `routes_actions.py`, `routes_approvals.py`, `routes_change.py`, `routes_reports.py`의 `@router.*`/`@decision_router.*` decorator.
- 0줄/미mount 파일: `routes_admin.py`, `routes_catalogs.py`, `routes_evidence.py`, `routes_feedback.py`, `routes_runs.py`는 현재 내용이 없고 `main.py`에도 mount되지 않는다. 해당 표면은 아래 표에서 **미구현(예정)** 으로 표시한다.
- 빌드 추적 이슈: runs/state-timeline [#312](https://github.com/cloud-team2/bifrost/issues/312), evidence·catalogs [#313](https://github.com/cloud-team2/bifrost/issues/313), admin·feedback [#314](https://github.com/cloud-team2/bifrost/issues/314), approvals·change-tickets [#307](https://github.com/cloud-team2/bifrost/issues/307), mutation [#308](https://github.com/cloud-team2/bifrost/issues/308), connection-guide·table-mapping [#303](https://github.com/cloud-team2/bifrost/issues/303).

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

정본 근거: `services/ai-service/app/main.py:46`, `services/ai-service/app/api/routes_health.py:19-81`.

| Method | Path | 구현 상태 | 설명 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/health` | 구현됨 | FastAPI 서버 생존 상태를 확인한다. |
| `GET` | `/api/v1/ready` | 구현됨 | LLM, Spring Boot, Agent Run Store, Knowledge Vector Store, Evidence Store 연결 준비 상태를 확인한다. |
| `GET` | `/api/v1/version` | 구현됨 | API version, build version, catalog version을 반환한다. |
| `GET` | `/api/v1/capabilities` | 구현됨 | 사용 가능한 Agent mode, model tier, streaming 지원 여부를 반환한다. |

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
      "agent_run_store": "ok",
      "vector_store": "ok",
      "evidence_store": "ok"
    }
  }
}
```

### 6. Agent Run API

정본 근거: `services/ai-service/app/main.py:47`, `services/ai-service/app/api/routes_agent.py:35-72`. `routes_runs.py`는 0줄/미mount다.

| Method | Path | 구현 상태 | 설명 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/agent/runs` | 구현됨 | 사용자 요청으로 새 Agent run을 생성한다. |
| `POST` | `/api/v1/agent/chat` | 미구현(예정) | 대화형 메시지를 받아 run을 생성하거나 기존 run에 이어 붙이는 설계 표면. 현재 route 없음. |
| `POST` | `/api/v1/agent/plan` | 미구현(예정) | 실행 없이 분석 계획만 생성하는 설계 표면. 현재 route 없음. |
| `POST` | `/api/v1/agent/incidents/{incident_id}/analyze` | 미구현(예정) | Incident 기준 RCA run 시작 설계 표면. 현재 route 없음. |
| `GET` | `/api/v1/agent/runs` | 미구현(예정: [#312](https://github.com/cloud-team2/bifrost/issues/312)) | 사용자의 Agent run 목록 조회. `routes_runs.py` 0줄/미mount. |
| `GET` | `/api/v1/agent/runs/{run_id}` | 구현됨 | run의 현재 상태와 요약을 조회한다. |
| `POST` | `/api/v1/agent/runs/{run_id}/messages` | 미구현(예정: [#312](https://github.com/cloud-team2/bifrost/issues/312)) | 기존 run에 후속 사용자 메시지를 추가하는 설계 표면. 현재 route 없음. |
| `POST` | `/api/v1/agent/runs/{run_id}/cancel` | 미구현(예정: [#312](https://github.com/cloud-team2/bifrost/issues/312)) | 실행 중인 run 취소 설계 표면. 현재 route 없음. |
| `POST` | `/api/v1/agent/runs/{run_id}/retry` | 미구현(예정: [#312](https://github.com/cloud-team2/bifrost/issues/312)) | 실패 run 재시도 설계 표면. 현재 route 없음. |

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

#### `POST /api/v1/agent/chat` — 미구현(예정)

대화형 메시지를 처리하는 설계 표면이다. 현재 `routes_agent.py`에는 `/chat` mapping이 없고, 후속 메시지용 `/runs/{run_id}/messages`도 [#312](https://github.com/cloud-team2/bifrost/issues/312) 범위로 남아 있다.

요청(예정):

```json
{
  "project_id": "proj_001",
  "run_id": null,
  "message": "consumer lag가 왜 늘었는지 봐줘",
  "stream": true
}
```

#### `POST /api/v1/agent/plan` — 미구현(예정)

실제 운영 tool 호출 없이 분석 계획을 생성하는 설계 표면이다. 현재 route 없음.

#### `POST /api/v1/agent/incidents/{incident_id}/analyze` — 미구현(예정)

기존 Incident와 관련 alert를 기준으로 RCA run을 시작하는 설계 표면이다. 현재 route 없음.

요청(예정):

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

정본 근거: `services/ai-service/app/main.py:48`, `services/ai-service/app/api/routes_events.py:14-26`.

| Method | Path | 구현 상태 | 설명 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/agent/runs/{run_id}/events` | 구현됨 | run 진행 상태를 SSE로 구독한다. |
| `GET` | `/api/v1/agent/runs/{run_id}/events/history` | 미구현(예정: [#312](https://github.com/cloud-team2/bifrost/issues/312)) | 재연결용 event history 조회 설계 표면. 현재 route 없음. |

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

전체 event 종류는 [DETAILS §16](../design/backend-fastapi/contract/contract-streaming-events.md#16-contract-streaming-events)를 따른다.

### 8. State / Timeline API

정본 근거: `routes_actions.py:104-115`만 `/runs/{run_id}/actions`를 구현한다. `routes_runs.py`는 0줄/미mount라 state/timeline/steps 표면은 [#312](https://github.com/cloud-team2/bifrost/issues/312) 범위로 남아 있다.

| Method | Path | 구현 상태 | 설명 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/agent/runs/{run_id}/state/summary` | 미구현(예정: [#312](https://github.com/cloud-team2/bifrost/issues/312)) | UI 표시용 State 요약 조회 설계 표면. 현재 route 없음. |
| `GET` | `/api/v1/agent/runs/{run_id}/timeline` | 미구현(예정: [#312](https://github.com/cloud-team2/bifrost/issues/312)) | Agent 단계, tool call, 승인, 실행 기록 timeline 조회 설계 표면. 현재 route 없음. |
| `GET` | `/api/v1/agent/runs/{run_id}/steps` | 미구현(예정: [#312](https://github.com/cloud-team2/bifrost/issues/312)) | Agent step별 상태 조회 설계 표면. 현재 route 없음. |
| `GET` | `/api/v1/agent/runs/{run_id}/actions` | 구현됨(요약만) | run에서 생성된 action 후보와 상태 조회 설계 표면이나, 현재 구현은 run status/mode 요약만 반환한다. |

#### `GET /api/v1/agent/runs/{run_id}/timeline` — 미구현(예정: [#312](https://github.com/cloud-team2/bifrost/issues/312))

응답(예정):

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

정본 근거: `routes_evidence.py`는 0줄이고 `main.py`에 mount되지 않는다. Evidence metadata endpoint는 [#313](https://github.com/cloud-team2/bifrost/issues/313) 범위로 남아 있다.

| Method | Path | 구현 상태 | 설명 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/agent/runs/{run_id}/evidence` | 미구현(예정: [#313](https://github.com/cloud-team2/bifrost/issues/313)) | run에서 사용된 evidence metadata 목록 조회 설계 표면. |
| `GET` | `/api/v1/agent/runs/{run_id}/evidence/{evidence_id}` | 미구현(예정: [#313](https://github.com/cloud-team2/bifrost/issues/313)) | evidence summary와 redaction 상태 조회 설계 표면. |
| `POST` | `/api/v1/agent/runs/{run_id}/evidence/{evidence_id}/hydrate` | 미구현(예정: [#313](https://github.com/cloud-team2/bifrost/issues/313)) | 권한 있는 운영자에게 제한적 evidence 상세 제공 설계 표면. |

Evidence API는 기본적으로 raw content를 반환하지 않는다. `hydrate`도 redaction과 권한 검사를 통과해야 한다.

### 10. Approval API

정본 근거: `services/ai-service/app/main.py:50-51`, `services/ai-service/app/api/routes_approvals.py:23-66`.

| Method | Path | 구현 상태 | 설명 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/approvals` | 미구현(예정: [#307](https://github.com/cloud-team2/bifrost/issues/307)) | 사용자가 처리할 approval 목록 조회 설계 표면. 현재 route 없음. |
| `GET` | `/api/v1/approvals/{approval_id}` | 미구현(예정: [#307](https://github.com/cloud-team2/bifrost/issues/307)) | approval 상세와 action 요약 조회 설계 표면. 현재 route 없음. |
| `POST` | `/api/v1/approvals/{approval_id}/decision` | 구현됨 | approval을 승인 또는 거절한다. `decision_router`가 `/api/v1` prefix로 mount된다. |
| `GET` | `/api/v1/agent/runs/{run_id}/approvals` | 구현됨 | 특정 run의 pending approval 요청 목록을 조회한다. |
| `POST` | `/api/v1/agent/runs/{run_id}/approvals/{approval_id}/decision` | 미구현(예정: [#307](https://github.com/cloud-team2/bifrost/issues/307)) | run context에서 approval 결정을 기록하는 설계 표면. 현재 route 없음. |
| `POST` | `/api/v1/agent/approvals/{approval_id}/approve` | 구현됨(현재 코드 표면) | 승인 shortcut. 설계 표면과 별도로 현재 구현되어 있다. |
| `POST` | `/api/v1/agent/approvals/{approval_id}/reject` | 구현됨(현재 코드 표면) | 거절 shortcut. 설계 표면과 별도로 현재 구현되어 있다. |

> **Approval source of truth는 Spring Boot로 수렴해야 한다.** 현재 FastAPI 구현은 `approval_link_repository`를 통해 run 연계 approval 상태를 관리하며, Spring Boot facade/중복 생성 금지 계약의 완성은 [#307](https://github.com/cloud-team2/bifrost/issues/307) 범위다.

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

정본 근거: `services/ai-service/app/main.py:52`, `services/ai-service/app/api/routes_change.py:21-76`.

| Method | Path | 구현 상태 | 설명 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/change-tickets/{change_ticket_id}` | 미구현(예정: [#307](https://github.com/cloud-team2/bifrost/issues/307)) | change ticket 상태, 실행 window, rollback plan 요약 조회 설계 표면. 현재 route 없음. |
| `POST` | `/api/v1/agent/runs/{run_id}/change-tickets` | 구현됨(현재 코드 표면) | body의 `action_id`, `ticket_id`, `window`, `rollback_plan`을 저장/검증한다. |
| `GET` | `/api/v1/agent/runs/{run_id}/change-tickets` | 구현됨(현재 코드 표면) | run에 연결된 change ticket 목록을 조회한다. |
| `POST` | `/api/v1/agent/runs/{run_id}/actions/{action_id}/change-ticket` | 미구현(예정: [#307](https://github.com/cloud-team2/bifrost/issues/307)) | action별 change ticket 연결 설계 표면. 현재는 위 aggregate route만 구현. |
| `POST` | `/api/v1/agent/runs/{run_id}/actions/{action_id}/execution-window` | 미구현(예정: [#307](https://github.com/cloud-team2/bifrost/issues/307)) | action 실행 window 연결 설계 표면. 현재 route 없음. |
| `POST` | `/api/v1/agent/runs/{run_id}/actions/{action_id}/rollback-plan` | 미구현(예정: [#307](https://github.com/cloud-team2/bifrost/issues/307)) | action rollback plan 연결 설계 표면. 현재 route 없음. |

FastAPI는 change ticket을 최종 검증하지 않는다. Spring Boot가 실행 직전에 다시 검증한다.

### 12. Action Execution / Mutation API

정본 근거: `services/ai-service/app/main.py:49`, `services/ai-service/app/api/routes_actions.py:40-115`. action별 execute/execution/verify mutation endpoint는 [#308](https://github.com/cloud-team2/bifrost/issues/308) 범위로 남아 있다.

| Method | Path | 구현 상태 | 설명 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/agent/actions/run` | 구현됨(현재 코드 표면) | `action_execution` 모드로 새 run을 시작한다. |
| `POST` | `/api/v1/agent/actions/approval-decision` | 구현됨(현재 코드 표면) | `approval_decision` 모드로 새 run을 시작한다. |
| `GET` | `/api/v1/agent/runs/{run_id}/actions` | 구현됨(요약만) | 현재 구현은 run status/mode 요약을 반환한다. |
| `POST` | `/api/v1/agent/runs/{run_id}/actions/{action_id}/execute` | 미구현(예정: [#308](https://github.com/cloud-team2/bifrost/issues/308)) | 승인/변경관리 검증 후 action 실행 요청 설계 표면. 현재 route 없음. |
| `GET` | `/api/v1/agent/runs/{run_id}/actions/{action_id}/execution` | 미구현(예정: [#308](https://github.com/cloud-team2/bifrost/issues/308)) | action 실행 결과와 audit id 조회 설계 표면. 현재 route 없음. |
| `POST` | `/api/v1/agent/runs/{run_id}/actions/{action_id}/verify` | 미구현(예정: [#308](https://github.com/cloud-team2/bifrost/issues/308)) | 실행 후 verifier 재실행 설계 표면. 현재 route 없음. |

#### `POST /api/v1/agent/runs/{run_id}/actions/{action_id}/execute` — 미구현(예정: [#308](https://github.com/cloud-team2/bifrost/issues/308))

FastAPI가 State와 approval 정보를 확인한 뒤 Spring Boot Operations API를 호출하는 설계 표면이다.

요청(예정):

```json
{
  "approval_id": "appr_001",
  "change_ticket_id": null
}
```

### 13. Report API

정본 근거: `services/ai-service/app/main.py:53`, `services/ai-service/app/api/routes_reports.py:18-34`.

| Method | Path | 구현 상태 | 설명 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/agent/runs/{run_id}/report` | 구현됨 | 최종 report snapshot을 조회한다. |
| `GET` | `/api/v1/agent/runs/{run_id}/report/preview` | 미구현(예정) | verifier 통과 전 preview 가능한 요약 조회 설계 표면. 현재 route 없음. preview는 SSE `report_preview_available` 이벤트로만 노출된다. |
| `POST` | `/api/v1/agent/runs/{run_id}/report/regenerate` | 미구현(예정) | 검증된 State 기준 report 재생성 설계 표면. 현재 route 없음. |
| `GET` | `/api/v1/incidents/{incident_id}/reports` | 구현됨 | incident에 연결된 report 목록을 조회한다. |

Report는 Verifier가 `approved_for_final_response=true`로 승인한 내용만 포함한다.

### 14. Incident / Alert (소유권)

incident·alert의 **저장과 목록/상세 조회는 Spring Boot 플랫폼 API**가 담당한다(메타데이터 DB의 `incident`/`event` 테이블 — [Spring Boot DETAILS §4 Data Model](../design/backend-springboot/data-model.md#4-data-model), [Spring Boot API Reference](./springboot.md)). Frontend의 AlertsView 목록도 Spring Boot를 호출한다.

FastAPI는 그 incident를 **분석/대응**하는 역할만 한다. 설계상 진입점은 §6의 `POST /api/v1/agent/incidents/{incident_id}/analyze`이나, 현재 route는 미구현(예정)이다. 구현 전까지는 `POST /api/v1/agent/runs`에 `incident_id`를 포함해 run을 시작하는 현재 구현 표면만 존재한다.

alert 상관관계는 Correlation Engine(deterministic)이 처리하며, run 내부 단계로 동작한다(§15(Workflow Control)). 별도 외부 `/alerts/correlate` 미리보기는 v1에서 제공하지 않는다.

### 15. Catalog / Tool Metadata API

정본 근거: `routes_catalogs.py`는 0줄이고 `main.py`에 mount되지 않는다. catalog/tool metadata endpoint는 [#313](https://github.com/cloud-team2/bifrost/issues/313) 범위로 남아 있다.

| Method | Path | 구현 상태 | 설명 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/catalogs/failure-types` | 미구현(예정: [#313](https://github.com/cloud-team2/bifrost/issues/313)) | 장애 유형 catalog 조회 설계 표면. |
| `GET` | `/api/v1/catalogs/incident-root-cause-map` | 미구현(예정: [#313](https://github.com/cloud-team2/bifrost/issues/313)) | incident type과 root cause 후보 매핑 조회 설계 표면. |
| `GET` | `/api/v1/catalogs/root-causes` | 미구현(예정: [#313](https://github.com/cloud-team2/bifrost/issues/313)) | root cause catalog 조회 설계 표면. |
| `GET` | `/api/v1/catalogs/policies` | 미구현(예정: [#313](https://github.com/cloud-team2/bifrost/issues/313)) | policy decision 기준 조회 설계 표면. |
| `GET` | `/api/v1/tools` | 미구현(예정: [#313](https://github.com/cloud-team2/bifrost/issues/313)) | Agent 논리 tool catalog 조회 설계 표면. |
| `GET` | `/api/v1/tools/{tool_name}` | 미구현(예정: [#313](https://github.com/cloud-team2/bifrost/issues/313)) | 특정 tool의 risk와 parameter schema 조회 설계 표면. |

이 API는 UI 설명, 디버깅, 운영자 검토용이다.

### 16. Feedback / Audit UI API

정본 근거: `routes_feedback.py`는 0줄이고 `main.py`에 mount되지 않는다. feedback/admin UI endpoint는 [#314](https://github.com/cloud-team2/bifrost/issues/314) 범위로 남아 있다.

| Method | Path | 구현 상태 | 설명 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/agent/runs/{run_id}/feedback` | 미구현(예정: [#314](https://github.com/cloud-team2/bifrost/issues/314)) | 사용자가 RCA/report 품질 피드백을 남기는 설계 표면. |
| `GET` | `/api/v1/agent/runs/{run_id}/audit-events` | 미구현(예정: [#314](https://github.com/cloud-team2/bifrost/issues/314)) | run과 연결된 audit event 요약 조회 설계 표면. |
| `GET` | `/api/v1/audit-events/{audit_event_id}` | 미구현(예정: [#314](https://github.com/cloud-team2/bifrost/issues/314)) | 특정 audit event 요약 조회 설계 표면. |

Audit raw record의 source of truth는 Spring Boot다. FastAPI는 UI 요약을 제공하는 설계지만 현재 외부 route는 없다.

### 17. Admin API

정본 근거: `routes_admin.py`는 0줄이고 `main.py`에 mount되지 않는다. admin endpoint는 [#314](https://github.com/cloud-team2/bifrost/issues/314) 범위로 남아 있다.

| Method | Path | 구현 상태 | 설명 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/admin/models` | 미구현(예정: [#314](https://github.com/cloud-team2/bifrost/issues/314)) | 설정된 LLM model tier와 상태 조회 설계 표면. |
| `GET` | `/api/v1/admin/dependencies` | 미구현(예정: [#314](https://github.com/cloud-team2/bifrost/issues/314)) | Spring Boot, evidence store, state store 등 dependency 상태 조회 설계 표면. |
| `POST` | `/api/v1/admin/runs/{run_id}/replay` | 미구현(예정: [#314](https://github.com/cloud-team2/bifrost/issues/314)) | 저장된 State patch로 run replay 시작 설계 표면. |
| `POST` | `/api/v1/admin/catalogs/reload` | 미구현(예정: [#314](https://github.com/cloud-team2/bifrost/issues/314)) | catalog cache reload 설계 표면. |

Admin API는 운영자 권한이 필요하다.

### 18. Frontend-only Spring 연계 예정 항목

Connection Guide/Table Mapping은 FastAPI route가 아니라 Pipeline 상세 탭의 Spring Boot/프론트 연계 예정 항목이다. 현재 `PipelineController`에는 `/connection-guide`, `/table-mapping` mapping이 없으며, 빌드 추적은 [#303](https://github.com/cloud-team2/bifrost/issues/303)이다. Frontend 호출 표면은 [Frontend 설계 §6](../design/frontend.md#6-pipeline-상세-탭-fr-006--fr-012)에 미구현(예정)으로 표시한다.

### 19. 금지 API

FastAPI에는 다음 API를 만들지 않는다.

- Kubernetes resource 직접 patch/apply/delete
- Kafka topic 직접 생성/삭제
- Kafka Connect REST 직접 호출
- Prometheus 직접 query를 외부에 노출
- Secret 원문 조회
- pod exec 또는 shell 실행
- arbitrary SQL 실행

필요한 운영 기능은 Spring Boot Operations API와 tool registry를 통해서만 제공한다.
