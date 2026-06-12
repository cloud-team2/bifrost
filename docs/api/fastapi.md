# FastAPI Agent Server — API 명세

> Frontend가 AI 장애대응 화면에서 호출하는 FastAPI API 레퍼런스다. 워크스페이스·DB·파이프라인 CRUD·모니터링 같은 플랫폼 조회/관리 API는 [Spring Boot API Reference](./springboot.md)가 제공한다.

---

## 1. 목적

FastAPI Agent Server는 Agent run 생성, run 상태 조회, SSE streaming, approval/change UI cache, evidence metadata, catalog/tool metadata, 최종 report 조회를 제공한다. Kubernetes, Kafka, Kafka Connect, Prometheus 같은 runtime resource는 직접 제어하지 않고 Spring Boot `/internal/ops/**`에 위임한다.

Spring Boot 내부 운영 API와 governance/mutation 계약은 [Spring Boot API Reference](./springboot.md#6-internal-ops-read--governance--mutation-api)와 [Spring Boot Governance](../design/backend-springboot/governance.md)를 따른다.

## 2. 공통 규칙

| 항목 | 규칙 |
| --- | --- |
| Base path | `/api/v1` |
| JSON field | snake_case |
| timestamp | ISO-8601 UTC |
| 인증 | 현재 FastAPI route에는 JWT/Bearer 검증 dependency가 연결되어 있지 않다. Agent run SSE path는 `access_token` query parameter를 받지만 현재 검증에 쓰지 않는다. |
| streaming | SSE 우선. WebSocket route는 현재 없음 |
| raw evidence | State에는 evidence metadata와 `store_ref`만 둔다. raw content는 `store_ref`로 evidence store를 조회하는 `GET .../evidence/{id}`(raw 동봉)·`POST .../evidence/{id}/hydrate`로만 노출한다 |

### 2.1 구현 상태 기준

구현 상태는 `services/ai-service/app/main.py`의 router mount와 각 `routes_*.py` decorator를 기준으로 한다.

| Mounted module | Prefix | 상태 |
| --- | --- | --- |
| `routes_health` | `/api/v1` | 구현됨 |
| `routes_agent` | `/api/v1/agent` | 구현됨 |
| `routes_runs` | `/api/v1/agent` | 구현됨 |
| `routes_events` | `/api/v1/agent` | 구현됨 |
| `routes_actions` | `/api/v1/agent` | 구현됨 |
| `routes_approvals.router` | `/api/v1/agent` | 구현됨 |
| `routes_approvals.decision_router` | `/api/v1` | 구현됨 |
| `routes_change` | `/api/v1/agent` | 구현됨 |
| `routes_reports` | `/api/v1` | 구현됨 |
| `routes_feedback` | `/api/v1/agent` | 구현됨 |
| `routes_admin` | `/api/v1/admin` | 구현됨 |
| `routes_evidence` | `/api/v1/agent` | 구현됨 |
| `routes_catalogs` | `/api/v1` | 구현됨 |

## 3. 공통 Response

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

대부분의 논리 실패는 HTTP 200 envelope으로 내려온다. `routes_evidence.py`의 `EVIDENCE_NOT_FOUND`, `routes_catalogs.py`의 `TOOL_NOT_FOUND`는 custom `JSONResponse`로 HTTP 404를 반환한다. `routes_approvals.py`의 잘못된 decision 또는 missing approval은 공통 envelope이 아니라 FastAPI `HTTPException` 기본 응답(`{"detail": ...}`)이다.

## 4. 표준 Error Code

| Code | 설명 |
| --- | --- |
| `VALIDATION_FAILED` | 요청 schema 또는 parameter 오류 |
| `UNAUTHORIZED` | 인증 필요 |
| `FORBIDDEN` | project 또는 action 권한 없음 |
| `RUN_NOT_FOUND` | run을 찾을 수 없음 |
| `INCIDENT_NOT_FOUND` | incident를 찾을 수 없음 |
| `ACTION_NOT_FOUND` | action을 찾을 수 없음 |
| `APPROVAL_NOT_FOUND` | approval을 찾을 수 없음. `GET /api/v1/approvals/{approval_id}`는 이 code envelope을 반환하고, approve/reject/decision shortcut route는 plain `HTTPException` detail을 반환한다 |
| `RUN_ALREADY_CLOSED` | 완료/취소된 run에 변경 요청 |
| `POLICY_DENIED` | 정책상 실행 차단 |
| `SPRING_BACKEND_ERROR` | Spring Boot Operations Backend 호출 실패 |
| `LLM_PROVIDER_ERROR` | LLM provider 호출 실패 |
| `STREAM_UNAVAILABLE` | event stream 생성 실패 |
| `NOT_IMPLEMENTED` | route는 있으나 v1 구현이 없는 기능 |
| `EVIDENCE_NOT_FOUND` | evidence id를 찾을 수 없음. `ErrorCode` enum이 아니라 `routes_evidence.py` custom 404 code |
| `EVIDENCE_RAW_NOT_FOUND` | evidence metadata는 있으나 `store_ref`로 raw evidence를 찾을 수 없음. `routes_evidence.py` hydrate route의 custom 404 code |
| `TOOL_NOT_FOUND` | tool name을 찾을 수 없음. `ErrorCode` enum이 아니라 `routes_catalogs.py` custom 404 code |

## 5. Health / Metadata API

| Method | Path | 구현 상태 | 설명 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/health` | 구현됨 | FastAPI 서버 생존 상태 |
| `GET` | `/api/v1/ready` | 구현됨 | LLM, Spring Boot, Agent Run Store, Knowledge Vector Store, Evidence Store 준비 상태 |
| `GET` | `/api/v1/version` | 구현됨 | API/build/catalog version |
| `GET` | `/api/v1/capabilities` | 구현됨 | Agent mode, model tier, streaming 지원 여부 |
| `GET` | `/health` | 구현됨 | Kubernetes probe용 경량 endpoint. 공통 envelope을 쓰지 않음 |

## 6. Agent Run API

| Method | Path | 구현 상태 | 설명 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/agent/runs` | 구현됨 | 새 Agent run 생성 |
| `GET` | `/api/v1/agent/runs` | 구현됨 | run 목록 조회. query: `project_id`, `status`, `limit`(0..100, default 20) |
| `GET` | `/api/v1/agent/runs/{run_id}` | 구현됨 | run record 조회 |
| `POST` | `/api/v1/agent/runs/{run_id}/messages` | 구현됨 | 기존 run에 후속 메시지 추가 후 background workflow 재시작 |
| `POST` | `/api/v1/agent/runs/{run_id}/cancel` | 구현됨 | run status를 `cancelled`로 변경 |
| `POST` | `/api/v1/agent/runs/{run_id}/retry` | route만 있음 | HTTP 200 failure envelope `NOT_IMPLEMENTED` |
| `POST` | `/api/v1/agent/chat` | 미구현 | route 없음 |
| `POST` | `/api/v1/agent/plan` | 미구현 | route 없음 |
| `POST` | `/api/v1/agent/incidents/{incident_id}/analyze` | 미구현 | route 없음 |

### `POST /api/v1/agent/runs`

요청 DTO는 `project_id`, `mode`, `message`, `incident_id`, `remediation_requested`, `stream`, `action_candidate`를 받는다. 현재 `CreateRunRequest`에는 `alert_ids` 필드가 없다.

```json
{
  "project_id": "proj_001",
  "mode": "incident_analysis",
  "message": "daily_user_sync 장애 원인을 분석해줘",
  "incident_id": "inc_001",
  "remediation_requested": false,
  "stream": true,
  "action_candidate": null
}
```

`mode`를 생략하면 코드가 `"simple_query"`로 저장한다. 메시지 기반 mode 추론은 현재 route layer의 동작이 아니다.
`incident_id`와 `remediation_requested`는 repository `create()` 호출에 전달되어 `agent_run` row에 저장되고, `run_workflow()`에는 `requested_incident_id`, `requested_remediation_requested`, `requested_action_candidate`로 전달된다.

응답:

```json
{
  "ok": true,
  "request_id": "req_...",
  "data": {
    "run_id": "run_...",
    "event_stream_url": "/api/v1/agent/runs/run_.../events",
    "status": "running"
  }
}
```

## 7. Event Streaming API

| Method | Path | 구현 상태 | 설명 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/agent/runs/{run_id}/events` | 구현됨 | run 진행 상태 SSE 구독 |
| `GET` | `/api/v1/agent/runs/{run_id}/events/history` | 구현됨 | `last_event_id` 이후 missed event catchup용 JSON endpoint |

브라우저 `EventSource`는 header를 붙일 수 없으므로 Frontend는 `/api/v1/agent/runs/{run_id}/events?access_token=<jwt>` 형태로 구독한다. 현재 FastAPI route는 이 query token을 검증하지 않고 `Last-Event-ID` header만 읽어 `StreamingResponse`를 반환한다.

## 8. State / Timeline API

`routes_runs.py`가 run-scoped state/timeline facade를 구현한다.

| Method | Path | 구현 상태 | 설명 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/agent/runs/{run_id}/state/summary` | 구현됨 | State patch namespace 요약 |
| `GET` | `/api/v1/agent/runs/{run_id}/timeline?after_seq=` | 구현됨 | `state_patch`와 `run_event`를 병합한 timeline |
| `GET` | `/api/v1/agent/runs/{run_id}/steps` | 구현됨 | patch author 기준 step 요약 |
| `GET` | `/api/v1/agent/runs/{run_id}/actions` | 구현됨 | `actions` namespace patch를 파싱해 action summary 반환 |

`state/summary` 응답 data:

| Field | 설명 |
| --- | --- |
| `run_id` | path run id |
| `mode` | `agent_run.mode` |
| `status` | `agent_run.status` |
| `current_stage` | `agent_run.current_agent` |
| `namespaces` | namespace별 `patch_count`, `last_author`, `last_op`, `last_updated_at` |
| `guards` | 기본 `step_count`, `gap_loops`. facade는 `guards` namespace patch만 overlay로 읽는다. 현재 runner의 budget guard patch는 namespace `run`, path `/run/guards`에 저장되어 이 overlay에는 반영되지 않는다. |

`timeline` item field:

| Field | 설명 |
| --- | --- |
| `seq` | state patch seq. event 기반 item은 `null` |
| `type` | `state_patch` 또는 event type |
| `agent` | patch author 또는 event agent |
| `message` | 표시용 message |
| `created_at` | 생성 시각 |

`actions` item field는 `action_id`, `action_type`, `tool_name`, `risk`, `policy_decision`, `approval_id`, `approval_status`, `execution_status`, `audit_event_id`다. facade parser는 list payload, `actions`, `value`를 우선 읽고 그 외 dict는 payload 자체를 단일 item으로 처리한다. `action_id`가 없으면 patch path의 `/actions/{segment}`에서 segment를 가져오므로, 현재 runner patch(`/actions/policy_decisions`, `/actions/execution_results` 등)는 실제 action id 대신 `policy_decisions` 같은 synthetic id summary를 만들 수 있다.

## 9. Evidence API

`routes_evidence.py`는 `state_patch`의 `evidence` namespace를 조회한다.

| Method | Path | 구현 상태 | 설명 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/agent/runs/{run_id}/evidence` | 구현됨 | evidence metadata 목록 |
| `GET` | `/api/v1/agent/runs/{run_id}/evidence/{evidence_id}` | 구현됨 | matching patch payload 반환. `store_ref`로 raw evidence를 조회할 수 있으면 `raw` 필드를 동봉한다. 없으면 HTTP 404 `EVIDENCE_NOT_FOUND` |
| `POST` | `/api/v1/agent/runs/{run_id}/evidence/{evidence_id}/hydrate` | 구현됨 | metadata patch의 `store_ref`로 evidence store에서 raw evidence를 조회해 반환. evidence id가 없으면 HTTP 404 `EVIDENCE_NOT_FOUND`, raw record를 찾지 못하면 HTTP 404 `EVIDENCE_RAW_NOT_FOUND` |

목록 item field는 `evidence_id`, `type`, `store_ref`, `summary`, `redaction_status`, `collected_at`이다. Raw content는 evidence metadata patch의 `store_ref`로 evidence repository(`get_evidence_repo()`)를 조회해 hydrate한다. hydrate 응답 field는 `store_ref`, `payload`, `redaction_status`, `status`, `tool_name`, `step_id`, `created_at`이다.

## 10. Approval API

| Method | Path | 구현 상태 | 설명 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/agent/runs/{run_id}/approvals` | 구현됨 | run의 approval link 목록 |
| `POST` | `/api/v1/agent/approvals/{approval_id}/approve` | 구현됨 | 승인 shortcut |
| `POST` | `/api/v1/agent/approvals/{approval_id}/reject` | 구현됨 | 거절 shortcut |
| `POST` | `/api/v1/approvals/{approval_id}/decision` | 구현됨 | approval decision router. request body: `decision`, `comment` |
| `GET` | `/api/v1/approvals` | 구현됨 | 글로벌 approval 목록. query filter `status`(`pending\|approved\|rejected`), `project_id` 지원. `created_at` desc 정렬 |
| `GET` | `/api/v1/approvals/{approval_id}` | 구현됨 | 단일 approval 상세(`ApprovalSummary`). 없으면 `APPROVAL_NOT_FOUND` envelope |
| `POST` | `/api/v1/agent/runs/{run_id}/approvals/{approval_id}/decision` | 미구현 | route 없음 |

현재 FastAPI approval route는 local approval-link repository만 갱신한다. Spring Boot approval facade와 mutation single-use 검증 표면은 존재하지만, FastAPI executor는 Spring mutation 호출에 `X-Approval-Id`를 전달하지 않으므로 현재 실행 경로와 연결되어 있지 않다.

## 11. Change Management API

| Method | Path | 구현 상태 | 설명 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/agent/runs/{run_id}/change-tickets` | 구현됨 | body의 `action_id`, `ticket_id`, `window`, `rollback_plan`, `impact_analysis`, `verifier_plan` 저장/검증 |
| `GET` | `/api/v1/agent/runs/{run_id}/change-tickets` | 구현됨 | run에 연결된 change ticket 목록 |
| `GET` | `/api/v1/change-tickets/{change_ticket_id}` | 미구현 | route 없음 |
| `POST` | `/api/v1/agent/runs/{run_id}/actions/{action_id}/change-ticket` | 미구현 | route 없음 |
| `POST` | `/api/v1/agent/runs/{run_id}/actions/{action_id}/execution-window` | 미구현 | route 없음 |
| `POST` | `/api/v1/agent/runs/{run_id}/actions/{action_id}/rollback-plan` | 미구현 | route 없음 |

Spring Boot change-ticket facade의 현재 구현 필드는 [Spring Boot API §6.3](./springboot.md#63-change-ticket-facade)를 따른다.

## 12. Action Execution / Mutation API

| Method | Path | 구현 상태 | 설명 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/agent/actions/run` | route 있음(현재 persistence/flow 결함) | `action_execution` mode로 `run_repo.create(...)`를 호출하지만 handler가 async create를 `await`하지 않는다. workflow runner도 전달 mode를 쓰지 않고 user message를 router로 다시 판정한다 |
| `POST` | `/api/v1/agent/actions/approval-decision` | route 있음(현재 persistence/flow 결함) | `approval_decision` mode로 `run_repo.create(...)`를 호출하지만 handler가 async create를 `await`하지 않는다. router에는 `approval_decision` branch가 없어 이 route만으로 approval workflow가 강제되지 않는다 |
| `GET` | `/api/v1/agent/runs/{run_id}/actions` | 구현됨 | §8과 동일. `routes_runs`가 먼저 mount되어 actions namespace summary를 반환 |
| `POST` | `/api/v1/agent/runs/{run_id}/actions/{action_id}/execute` | 미구현 | route 없음 |
| `GET` | `/api/v1/agent/runs/{run_id}/actions/{action_id}/execution` | 미구현 | route 없음 |
| `POST` | `/api/v1/agent/runs/{run_id}/actions/{action_id}/verify` | 미구현 | route 없음 |

현재 Spring Boot에 구현된 mutation subset은 connector restart/pause/resume과 Kafka Connect-managed consumer group restart다. 세부 header, approval, idempotency, error code는 [Spring Boot API §6.4](./springboot.md#64-mutation-endpoints)를 따른다.

## 13. Report API

| Method | Path | 구현 상태 | 설명 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/agent/runs/{run_id}/report` | 구현됨 | run의 최신 verified report snapshot |
| `GET` | `/api/v1/incidents/{incident_id}/reports` | 구현됨 | incident의 verified report snapshot 목록 |
| `GET` | `/api/v1/agent/runs/{run_id}/report/preview` | 미구현 | route 없음. preview는 SSE event로만 노출될 수 있음 |
| `POST` | `/api/v1/agent/runs/{run_id}/report/regenerate` | 미구현 | route 없음 |

`report_snapshot`은 FastAPI agentdb 테이블이다. 마이그레이션 기준 필드는 `id`, `run_id`, `incident_id`, `root_cause_id`, `confidence`, `verified`, `body`, `created_at`이며 run/incident별 created index가 있다. Repository는 기본적으로 `verified=true` snapshot만 최신/목록 조회에 사용한다. Workflow runner는 Verifier가 `approved_for_final_response`를 하나 이상 승인한 경우에만 `verified=true`로 저장하고, snapshot 생성 시 현재 workflow의 `incident_id`도 전달한다.

## 14. Incident / Alert 소유권

Incident, event, monitoring 목록/상세 조회는 Spring Boot 플랫폼 API가 담당한다. FastAPI `CreateRunRequest`의 `incident_id`와 `remediation_requested`는 run persistence와 `run_workflow()`에 전달된다. 별도 `/alerts/correlate`나 `/api/v1/agent/incidents/{incident_id}/analyze` route는 현재 없다.

## 15. Catalog / Tool Metadata API

`routes_catalogs.py`가 catalog와 FastAPI 논리 tool registry metadata를 제공한다.

| Method | Path | 구현 상태 | 응답 data |
| --- | --- | --- | --- |
| `GET` | `/api/v1/catalogs/failure-types` | 구현됨 | `items[{incident_type, layer, description, signals}]`, `version` |
| `GET` | `/api/v1/catalogs/incident-root-cause-map` | 구현됨 | `mapping`, `version` |
| `GET` | `/api/v1/catalogs/root-causes` | 구현됨 | `items[{root_cause_id, layer, owned_by, direct_action_allowed, default_confidence_cap}]`, `version` |
| `GET` | `/api/v1/catalogs/policies` | 구현됨 | `items[{action_type, risk, decision, reason}]`, `version` |
| `GET` | `/api/v1/catalogs/runbooks` | 구현됨 | `items[{root_cause_id, disposition, allowed_action_types, basis, actions, forbidden_actions}]`, `version` |
| `GET` | `/api/v1/tools` | 구현됨 | `tools[{name, operation, risk, method, path_template}]` |
| `GET` | `/api/v1/tools/{tool_name}` | 구현됨 | summary + `params_schema`, `result_schema`. 없으면 HTTP 404 `TOOL_NOT_FOUND` |
| `POST` | `/api/v1/tools/{tool_name}/execute` | 구현됨 | read-only tool을 직접 실행(slash command용). body `project_id`, `params`. 응답 data는 `tool_result`, `result` |

`POST /api/v1/tools/{tool_name}/execute`는 read-only slash command 실행 전용이다. tool이 없으면 HTTP 404 `TOOL_NOT_FOUND`, 대상 tool이 `RiskLevel.READ_ONLY`가 아니거나 `requires_approval`이면 HTTP 400 `POLICY_DENIED`로 거부한다. 실행은 `slash_command` agent 컨텍스트(`run_id=slash_...`)로 registry를 호출하고, tool 실패 시 Spring tool error code를 HTTP 400 envelope으로 그대로 전달한다. mutation tool은 이 route로 실행할 수 없다.

FastAPI tool registry는 Agent의 논리 tool 목록이다. Spring Boot `GET /internal/ops/admin/tool-catalog`는 실제 Spring runtime endpoint catalog이며 현재 read operation과 approval-gated mutation operation을 함께 반환한다. 두 catalog의 목적과 범위는 다르다.

## 16. Feedback / Audit UI API

`routes_feedback.py`는 `main.py`에서 `/api/v1/agent` prefix로 mount된다.

| Method | Path | 구현 상태 | 설명 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/agent/runs/{run_id}/feedback` | 구현됨 | run feedback 저장. run 없으면 404 `RUN_NOT_FOUND` |
| `GET` | `/api/v1/agent/runs/{run_id}/audit-events` | 구현됨 | run event stream에서 audit event type만 projection |
| `GET` | `/api/v1/agent/audit-events/{audit_event_id}` | route 있음 | v1은 Spring SoT라 `NOT_IMPLEMENTED` failure 응답 |

Audit raw record의 source of truth는 Spring Boot다.

## 17. Admin API

`routes_admin.py`는 `main.py`에서 `/api/v1/admin` prefix로 mount된다.

| Method | Path | 구현 상태 | 설명 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/admin/models` | 구현됨 | default model, agent tier mapping, provider status |
| `GET` | `/api/v1/admin/dependencies` | 구현됨 | Spring operations, agent run store 상태 |
| `POST` | `/api/v1/admin/runs/{run_id}/replay` | route 있음 | v1은 `NOT_IMPLEMENTED` failure 응답 |
| `POST` | `/api/v1/admin/catalogs/reload` | 구현됨 | in-process catalog reload metadata 응답 |

## 18. Spring 연계 항목

Connection Guide와 Table Mapping은 FastAPI route가 아니라 Spring Boot `PipelineController` + Frontend 상세 탭 연계다.

| 항목 | Spring path | 응답 요약 |
| --- | --- | --- |
| Connection Guide | `GET /api/v1/workspaces/{wsId}/pipelines/{id}/connection-guide` | `pipelineId`, `pipelineName`, `bootstrapServers`, `recommendedGroupId`, `authenticationMethod`, `credentialReference`, `authenticationTemplates`, `topics` |
| Table Mapping | `GET /api/v1/workspaces/{wsId}/pipelines/{id}/table-mapping` | `pipelineId`, `sourceConnector`, `sinkConnector`, `mappings[{sourceTable,kafkaTopic,sinkTable}]` |

Kafka secret material은 API로 반환하지 않는다. Kafka principal secret 조회는 Spring Boot가 `passwordMasked="********"`와 `exposurePolicy="MASKED_REFERENCE_ONLY"`만 반환한다.

## 19. RAG / Knowledge Vector Store

FastAPI RAG는 `knowledge_chunk` pgvector 테이블을 사용한다. 마이그레이션 기준 필드는 `chunk_id`, `doc_id`, `doc_type`, `title`, `content`, `embedding vector(1536)`, `scope`, `doc_version`, `metadata`, `updated_at`이다.

검색 scope는 공통 `global`, project scope `project:{project_id}`, 명시 scope를 조합한다. `store_ref`는 `knowledge://{scope}/{doc_id}/{chunk_id}` 형식으로 생성된다. Ready check는 `SELECT 1 FROM knowledge_chunk LIMIT 1`로 table 접근성을 확인한다.

## 20. 금지 API

FastAPI에는 다음 API를 만들지 않는다.

- Kubernetes resource 직접 patch/apply/delete
- Kafka topic 직접 생성/삭제
- Kafka Connect REST 직접 호출
- Prometheus 직접 query를 외부에 노출
- Secret 원문 조회
- pod exec 또는 shell 실행
- arbitrary SQL 실행

필요한 운영 기능은 Spring Boot Operations API와 tool registry를 통해서만 제공한다.
