# FastAPI Agent — Server Design (§2)

> 요약은 [overview.md](./overview.md). 판단 원리는 [agent-principles.md](./agent-principles.md). 식별자: agentdb `project_id`는 현재 프론트 `workspace_id` UUID를 저장한다. Spring `/internal/ops/projects/{projectId}`는 대부분 workspace `projectKey`/namespace slug를 기대하므로 현재 FastAPI tool registry에는 UUID↔projectKey 매핑 gap이 있다.

## 2. Server Design


### 1. 목적

FastAPI Agent Server는 Bifrost의 Agent orchestration 계층이다. 사용자 요청과 alert를 받아 Agent workflow를 실행하고, 필요한 운영 조회와 실행은 Spring Boot Operations Backend에 위임한다.

FastAPI는 판단과 workflow를 담당한다.

```text
Frontend
  -> FastAPI Agent Server
  -> Spring Boot Operations Backend
```

API 상세는 [§3 API Reference](../../api/fastapi.md), Agent 원리는 [§1 Agent Principles](agent-principles.md#1-agent-principles), tool 매핑은 [§4 Tool Catalog](tool-catalog.md#4-tool-catalog)를 기준으로 한다. Spring Boot 내부 운영 API는 [Spring Boot DETAILS](../backend-springboot/overview.md)를 따른다.

### 2. 책임

FastAPI가 담당한다.

- Agent run 생성과 상태 관리
- LLM agent + 결정론적 단계 workflow 실행
- LLM provider 호출
- prompt와 structured output validation
- State graph와 namespace patch 관리
- Evidence metadata 관리
- Tool Client Registry 관리
- Spring Boot Operations API 호출
- SSE event streaming
- approval/change management 대기 상태 관리
- Verifier와 Report 실행

FastAPI가 담당하지 않는다.

- Kubernetes API 직접 호출
- Kafka AdminClient 직접 사용
- Kafka Connect REST 직접 호출
- Prometheus 직접 query
- DB 직접 접속
- 승인 없는 runtime mutation
- shell, pod exec, arbitrary SQL 실행

### 3. 내부 모듈

```text
app/
  api/                       # 라우트 = api.md 표면(§5~§17)과 1:1
    routes_health.py         #   health/ready/version/capabilities (§5)
    routes_agent.py          #   runs create/get (§6). chat·plan·incidents/analyze route 없음
    routes_runs.py           #   run 조회·state/timeline·steps·actions (§6·§8)
    routes_events.py         #   SSE stream (§7). history route 없음
    routes_evidence.py       #   evidence 조회·hydrate (§9)
    routes_approvals.py      #   approval facade (§10)
    routes_change.py         #   change management (§11)
    routes_actions.py        #   action run/approval-decision facade + run actions summary (§12). execute/verify route 없음
    routes_reports.py        #   report get/list (§13). preview/regenerate route 없음
    routes_catalogs.py       #   catalog/tool metadata (§15)
    routes_feedback.py       #   feedback·audit UI 요약 (§16)
    routes_admin.py          #   models·dependencies·replay·reload (§17)
  core/
    config.py
    auth.py
    logging.py
    errors.py
  llm/                       # LLM provider + 모델 tier 라우팅(§10)
    provider.py              #   벤더 추상화 client (생성/structured output 호출)
    model_router.py          #   역할별 tier 선택(Router..Report=lightweight / RCA·Verifier=reasoning)
  prompts/                   # 에이전트별 프롬프트 템플릿(버전·테스트 대상)
    router.py · planner.py · retrieval.py · classifier.py · rca.py · remediation.py · verifier.py · report.py
  agents/
    router.py
    planner.py
    retrieval.py
    classifier.py
    rca.py
    remediation.py
    verifier.py
    report.py
  supervisor/
    graph.py
    state_store.py
    transitions.py
    retry_policy.py
  workflow/
    stages/
      correlation.py
      policy_guard.py
      executor.py
      approval_gate.py
      change_gate.py
    guards.py
  tools/
    registry.py
    spring_client.py
    context.py
    result.py
  catalogs/
    failure_types.py
    root_causes.py
    incident_rootcause_map.py
    evidence_matrix.py
    correlation_rules.py
    runbooks.py
    policy_matrix.py
  schemas/
    state.py
    events.py
    outputs.py
    tools.py
    api.py
  evidence/
    metadata.py
    redaction.py
  knowledge/                 # RAG (Knowledge Vector Store)
    vector_store.py          #   pgvector client (또는 외부 벡터 DB)
    embedder.py
    indexer.py               #   knowledge_chunk/pgvector 인덱싱(배치)
  streaming/
    event_bus.py
    sse.py
  persistence/               # Agent Run Store (PostgreSQL)
    run_repository.py
    state_repository.py
    event_repository.py
    approval_link_repository.py
    report_repository.py
```

`agents/`에는 LLM 판단·생성이 필요한 8개 Agent만 둔다. **프롬프트 템플릿은 `prompts/`(에이전트별, 버전·테스트 대상), LLM provider 호출과 역할별 model tier 라우팅(§10)은 `llm/`** 에 두어 Agent 구현에서 분리한다(프롬프트가 코드에 묻히지 않게). Correlation, Policy Guard, Executor, Approval/Change Management Gate처럼 결정론적으로 동작해야 하는 단계는 `workflow/stages/`에 둬서 LLM agent와 실행 제어 경계를 분리한다.

`api/` 라우트는 [api.md](../../api/fastapi.md) 표면(§5~§17)과 1:1로 맞춘다(run-scoped state/timeline·steps·actions는 `routes_runs`가 묶는다). `catalogs/`는 failure type, root cause, evidence matrix, runbook, policy처럼 운영 기준이 되는 정적 계약을 담고, `schemas/`는 State·streaming event·structured output·tool I/O·API DTO 같은 **공유 검증 schema**를 담아 Agent 구현 파일 안에 상수와 모델이 흩어지지 않게 한다(에이전트 고유 output schema도 여기 둔다).

현재 `main.py` mount 기준으로 health, agent, runs, events, actions, approvals, change, reports, feedback, admin, evidence, catalogs route는 외부 API에 연결되어 있다.

### 4. State 관리

State는 Agent workflow의 단일 공유 컨텍스트다. FastAPI는 State namespace와 patch version을 관리한다.

핵심 원칙:

- raw evidence는 State에 넣지 않는다.
- Agent는 자기 namespace만 수정한다.
- State 변경은 patch로 append한다.
- 현재 runner는 Verifier `fail`/`needs_revision`을 Supervisor loopback으로 반영한다. 예산 안에서는 책임 Agent로 되돌아가고, 예산 초과 시 Report stage 없이 `failed`로 종료한다. `report_snapshot.verified`는 생성된 snapshot에 승인된 final response가 있었는지를 표시한다.

상세 schema는 [§14 State Schema](contract/contract-state-schema.md#14-contract-state-schema)를 따른다.

### 5. Tool Client Registry

FastAPI는 LLM이 만든 action/tool 의도를 바로 실행하지 않는다. Tool Client Registry가 다음을 수행한다.

1. tool name allowlist 확인
2. parameter schema validation
3. risk와 approval requirement 확인
4. Spring Boot operation mapping
5. timeout/retry policy 적용
6. 결과를 `ToolResult`로 정규화

실제 운영 API는 Spring Boot가 제공한다.

### 6. Spring Boot 호출 방식

FastAPI가 Spring Boot를 호출할 때는 service identity와 run context를 함께 보낸다.

```http
X-Agent-Run-Id: run_20260601_001
X-Agent-Step-Id: step_006
X-Agent-Name: Executor
X-Request-Id: req_20260601_001
X-Actor-Type: agent
X-Actor-Id: bifrost-agent
```

Mutation 호출에는 `X-Idempotency-Key`가 필요하다.

### 7. API 표면

FastAPI는 Frontend를 위한 API를 제공한다.

| 영역 | 예시 |
| --- | --- |
| Agent run | chat, incident analysis, plan, execute |
| Run 조회 | run summary, state summary, timeline |
| Event streaming | SSE event stream |
| Approval | approval decision, pending approval 조회 |
| Report | final report, evidence summary |
| Admin | health, model status, tool catalog 조회 |

상세 endpoint는 [§3 API Reference](../../api/fastapi.md)에 둔다.

### 8. Streaming

초기 구현은 SSE를 기본으로 한다.

Streaming 대상:

- run started/completed
- agent started/completed
- tool call started/completed/failed
- evidence collected
- approval required
- change management required
- execution completed
- verification completed

양방향 제어가 필요해지면 WebSocket을 추가한다.

### 9. Persistence (Data Model)

#### 9.1 저장소 구성

FastAPI는 성격이 다른 **세 종류의 저장소**를 쓴다(운영 raw data는 어디에도 직접 적재하지 않는다).

| 저장소 | 종류 | 소유 | 담는 것 |
| --- | --- | --- | --- |
| **Agent Run Store** | 관계형(PostgreSQL) | FastAPI | run 메타·State patch·SSE event·approval 연계·report 스냅샷 |
| **Knowledge Vector Store** | 벡터(pgvector 권장) | FastAPI | RAG 코퍼스(runbook·용어집·운영 문서·과거 인시던트 요약) 임베딩 |
| Evidence Store | blob/관계형 | **Spring/`metadb`** | 운영 조회 raw 결과(원문). FastAPI는 `store_ref`만 참조 |

- **인스턴스**: Agent Run Store는 FastAPI 전용 PostgreSQL(논리 DB `agentdb`). Knowledge Vector Store는 **pgvector 확장으로 같은 PostgreSQL에 co-locate**하는 것을 v1 기본으로 한다(폐쇄망·클러스터 용량 제약[infra §11](../infra.md#11-클러스터-용량-분석-및-대응안-2026-06-02---해소됨119) 상 전용 벡터 DB 컴포넌트를 새로 띄우지 않음). 코퍼스/스케일이 커지면 전용 벡터 DB(Qdrant·Milvus 등)로 외부화한다(인터페이스 동일).
- v1엔 `agentdb`를 `metadb` 네임스페이스의 PostgreSQL 인스턴스에 별도 database로 co-locate할 수 있으나 Spring 테이블과 상호 직접접근하지 않는다(서비스 경계=HTTP/JSON, [ADR 0004](../../adr/0004-monorepo-monolith.md)). 인프라 배치는 [infra §6.6](../infra.md#66-bifrost-application).
- **SoT 경계**: 운영 raw·evidence 원문·approval·incident·audit의 원본은 Spring `metadb`다([Spring DETAILS §4](../backend-springboot/data-model.md#4-data-model)). FastAPI 저장소는 run 상태·지식 코퍼스·캐시·요약만 둔다.
- (선택) 다중 replica에서 SSE 라이브 fan-out·run 잠금이 필요하면 Redis를 캐시/pub-sub로 둘 수 있다(resume 이력은 `run_event`로 충분).

#### 9.2 Agent Run Store (관계형)

**Agent run의 실행 상태**를 저장한다(플랫폼 메타데이터가 아니라 에이전트 orchestration 상태).

| 데이터 | 목적 | 테이블 |
| --- | --- | --- |
| run metadata | run 조회와 재개 | `agent_run` |
| state patch | workflow replay와 audit(append-only) | `state_patch` |
| event log | SSE 재연결(resume) | `run_event` |
| approval 연계 | 승인 대기 상태(Spring facade) | 현재 마이그레이션 테이블 없음. repository factory는 in-memory 구현을 반환 |
| report snapshot | 최종 응답 재조회 | `report_snapshot` |

**ERD**

```mermaid
erDiagram
    agent_run ||--o{ state_patch     : "append-only State"
    agent_run ||--o{ run_event       : "SSE replay"
    agent_run ||--o{ report_snapshot  : "최종 응답"

    agent_run {
        text   run_id PK
        uuid   project_id "현재 run 생성 입력 workspace UUID; internal-ops namespace와 다름"
        text   mode "simple_query/incident_analysis/action_execution/approval_decision"
        bool   remediation_requested "repository field. 현재 create_run route는 request 값을 persistence에 넘기지 않아 기본 false로 저장"
        text   incident_id "Spring incident 논리 참조(nullable). 현재 create_run route는 request 값을 persistence에 넘기지 않아 null로 저장"
        text   status "running/waiting_for_approval/completed/failed/cancelled"
        text   catalog_version "replay 재현 기준"
    }
    state_patch {
        bigint id PK
        text   run_id FK
        int    seq "run 내 단조 증가"
        text   namespace "run/incident/correlation/evidence/analysis/actions/verification/report"
        text   author "Router..Report / Supervisor"
        text   op "append/version/tombstone"
        jsonb  patch "메타데이터만(raw evidence inline 금지)"
    }
    run_event {
        text   event_id PK
        text   run_id FK
        int    seq "resume 커서"
        text   type "agent_started/tool_call_completed/approval_required/run_completed..."
        text   message "사용자 표시용 요약"
        jsonb  payload "민감정보·raw 금지(nullable)"
    }
    report_snapshot {
        uuid   id PK
        text   run_id FK
        text   root_cause_id "§8 catalog id(nullable)"
        bool   verified "Verifier approved_for_final_response"
        jsonb  body "{\"answer\",\"mode\",\"evidence\"}"
    }
```

> 텍스트 요약: `agent_run`이 `state_patch`(State 변경 이력)·`run_event`(SSE 재연결)·`report_snapshot`(최종 응답)을 1:N으로 소유한다. approval link는 현재 persistent table이 아니라 in-memory repository 상태다. `project_id`/`incident_id`/`approval_id`/evidence `store_ref`는 모두 Spring `metadb`로 가는 **논리 참조**이며 DB FK를 걸지 않는다(서비스 경계).

**테이블**

**`agent_run`** — run 메타데이터 (Agent Run API [api.md §6](../../api/fastapi.md))

| 컬럼 | 타입 | 설명 |
| --- | --- | --- |
| `run_id` | text PK | 예: `run_20260601_001` |
| `project_id` | uuid | 현재 run 생성 경로가 받는 workspace UUID. FastAPI registry가 이 값을 internal-ops path에 그대로 넣으면 Spring의 namespace lookup과 맞지 않는다(`list_alerts`만 UUID fallback) |
| `requested_by` | text | 요청 사용자 |
| `mode` | text | run 생성 route가 넘긴 mode/default가 저장된다. workflow runner는 user message를 router로 다시 판정하며, 현재 `agent_run.mode`를 갱신하지 않는다 |
| `remediation_requested` | bool | repository field. 현재 `POST /api/v1/agent/runs` route는 request 값을 persistence에 넘기지 않아 기본 false로 저장 |
| `incident_id` | text null | Spring incident 논리 참조. 현재 create-run route는 request 값을 persistence에 넘기지 않아 null로 저장 |
| `status` | text | `running`/`waiting_for_approval`/`completed`/`failed`/`cancelled` |
| `current_agent` | text null | 진행 중 단계 |
| `catalog_version` | text | tool/catalog 버전(replay 재현 기준, [§4.18](tool-catalog.md#4-tool-catalog)) |
| `created_at` `updated_at` `closed_at` | timestamptz | |

**`state_patch`** — State 변경 이력(append-only, event-sourced. [§14](contract/contract-state-schema.md#14-contract-state-schema))

| 컬럼 | 타입 | 설명 |
| --- | --- | --- |
| `id` | bigint PK | |
| `run_id` | text FK | → `agent_run` |
| `seq` | int | run 내 순서. `unique(run_id, seq)` |
| `namespace` | text | 현재 코드에서 관측되는 값: `run`, `run.plan`, `incident`, `correlation`, `evidence`, `analysis`, `actions`, `verification`, `report` |
| `author` | text | 작성 주체(Agent 또는 Supervisor). 자기 namespace만 기록 |
| `op` | text | `append`/`version`(수정)/`tombstone`(삭제 대체) |
| `path` | text | namespace 내 경로 |
| `patch` | jsonb | 변경 내용. **raw evidence/secret inline 금지**, evidence는 `store_ref`만 |
| `created_at` | timestamptz | |

**`run_event`** — SSE event 로그(재연결 history. [§16](contract/contract-streaming-events.md#16-contract-streaming-events), [api.md §7](../../api/fastapi.md))

| 컬럼 | 타입 | 설명 |
| --- | --- | --- |
| `event_id` | text PK | 예: `evt_001` |
| `run_id` | text FK | → `agent_run` |
| `seq` | int | resume 커서. `unique(run_id, seq)` |
| `type` | text | `agent_started`/`tool_call_completed`/`approval_required`/`verification_completed`/`run_completed`/… |
| `agent` | text null | 단계명 |
| `message` | text | 사용자 표시용 요약 |
| `payload` | jsonb null | 부가 컨텍스트(secret·connection string·원문 로그·내부 prompt 금지) |
| `created_at` | timestamptz | |

**approval link** — approval facade 연계

현재 Alembic migration은 `approval_link` 테이블을 만들지 않는다. `approval_link_repository` factory도 persistent backend 대신 in-memory repository를 반환하므로 run↔approval 연계는 프로세스 메모리 상태다. approval record(상태·params hash·승인자·만료·single-use)의 원본·검증·감사는 Spring facade가 담당한다.

**`report_snapshot`** — 최종 report 재조회 (Report API [api.md §13](../../api/fastapi.md))

| 컬럼 | 타입 | 설명 |
| --- | --- | --- |
| `id` | uuid PK | |
| `run_id` | text FK | → `agent_run` |
| `incident_id` | text null | Spring incident 논리 참조 |
| `root_cause_id` | text null | [§8 Root Cause Catalog](catalog/catalog-root-causes.md#8-catalog-root-cause) id |
| `confidence` | numeric null | |
| `verified` | bool | Verifier `approved_for_final_response`=true만 노출 |
| `body` | jsonb | 현재 workflow runner가 저장하는 `{"answer", "mode", "evidence"}` JSON. `run_report()` 자체는 plain string answer를 반환한다. |
| `created_at` | timestamptz | |

Repository의 최신/목록 조회는 기본적으로 `verified=true` snapshot만 반환한다. Workflow runner는 verifier 결과 중 `approved_for_final_response`가 하나 이상 있을 때만 `verified=true`로 저장한다.
현재 workflow runner는 report snapshot 생성 시 `incident_id`를 넘기지 않으므로 workflow가 만든 snapshot의 `incident_id`는 null이다. `GET /api/v1/incidents/{incident_id}/reports` route와 repository filter는 존재하지만, 현재 runner 산출물만으로는 incident별 목록이 채워지지 않는다.

#### 9.3 Knowledge Vector Store (RAG)

Retrieval 에이전트의 **문서 RAG**([§1 Agent Principles](agent-principles.md#1-agent-principles)) 코퍼스를 임베딩으로 보관한다. `simple_query`(지식 질의, 예: "DLQ가 뭐야?")와 인시던트 분석 시 runbook·운영 문서 근거를 **유사도 검색**으로 가져오고, 결과는 evidence item(`store_ref`=청크 참조)으로 State에 올린다.

> **여기 담는 건 큐레이션된 지식 코퍼스**(runbook·문서)이지 런타임 운영 raw(로그·secret)가 아니다. 그래서 본문 `content` 저장이 허용된다(§9.4의 raw 미저장 규칙과 구분).

**collection `knowledge_chunk`** (pgvector 테이블)

| 컬럼 | 타입 | 설명 |
| --- | --- | --- |
| `chunk_id` | uuid PK | |
| `doc_id` | text | 출처 문서/런북 id |
| `doc_type` | text | `runbook`/`glossary`/`ops_doc`/`catalog`/`incident_report` |
| `title` | text | |
| `content` | text | 청크 본문(큐레이션 지식; 운영 raw 아님) |
| `embedding` | vector(1536) | 현재 Alembic migration 기준 |
| `scope` | text | `global`(플랫폼 공통) 또는 `project:{project_id}`(과거 인시던트 등) |
| `doc_version` | text | 문서/카탈로그 버전(재인덱싱 기준) |
| `metadata` | jsonb | 태그·링크 |
| `updated_at` | timestamptz | |

- 인덱스: `embedding` ivfflat cosine index, `scope`·`doc_type`, `doc_id`·`doc_version` 필터.
- 임베딩 인덱싱은 오프라인 배치(`knowledge/indexer`). 현재 indexer는 runbook catalog에서 `_RUNBOOKS` attribute를 찾지만 실제 catalog는 `ROOT_CAUSE_RUNBOOKS`를 노출하므로, 그 경로로는 runbook 문서가 인덱싱되지 않는다. vector store와 chunk upsert 자체는 구현되어 있다.
- `scope=project:{id}` 청크는 해당 project로만 검색되게 테넌시 격리한다.
- `store_ref`는 `knowledge://{scope}/{doc_id}/{chunk_id}` 형식으로 생성한다.

#### 9.4 운영 규칙

1. **raw 미저장**: 로그·metric·trace·event payload 원문, secret, connection string은 저장하지 않는다. evidence는 Evidence Store(Spring/`metadb`)에 두고 `store_ref`만 참조한다. (단, Knowledge Vector Store의 **큐레이션 지식 코퍼스**(runbook·문서)는 운영 raw가 아니므로 본문 저장 허용 — §9.3.)
2. **append-only**: `state_patch`·`run_event`는 추가만 하고 삭제는 tombstone patch로 표현한다. State는 patch 재생으로 복원하며, 빠른 조회용 materialized 캐시는 구현 디테일이다.
3. **SoT 경계**: approval·audit·incident의 원본은 Spring `metadb`. FastAPI는 run 연계·캐시·요약만 둔다(중복 생성 금지).
4. **FK 경계**: `project_id`·`incident_id`·`approval_id`·evidence `store_ref`는 Spring 소유라 **DB FK를 걸지 않는다**(논리 참조, 유효성은 API로 검증 — [ADR 0004](../../adr/0004-monorepo-monolith.md)).
5. **retention**: 오래된 run의 `state_patch`/`run_event`는 보존 정책에 따라 아카이브·tombstone한다(무한 적재 금지).
6. **replay 재현성**: `agent_run.catalog_version`을 고정해 동일 catalog 기준으로 run을 재생한다([admin replay api.md §17](../../api/fastapi.md)).
7. **지식 코퍼스 인덱싱·격리**: Knowledge Vector Store는 `knowledge_chunk.embedding vector(1536)` 기반으로 검색한다. 현재 `knowledge.indexer._runbook_documents()`는 `runbook_catalog._RUNBOOKS`를 조회하지만 실제 catalog는 `ROOT_CAUSE_RUNBOOKS`로 노출되어, runbook corpus 인덱싱 경로는 그대로 동작하지 않는다. `scope=project:*` 청크는 해당 project로만 검색되게 격리한다.

### 10. 보안

1. 현재 FastAPI route에는 frontend 사용자 JWT 검증 dependency가 연결되어 있지 않다.
2. Spring `/internal/ops/**`는 service-to-service identity gate로 보호한다(#646). FastAPI(spring_client)가 `X-Internal-Token`(설정 `AI_INTERNAL_OPS_TOKEN`)을 동봉하고, ops-backend는 `internal.ops.token`과 일치할 때만 허용한다. 토큰 미설정 시 게이트 비활성(로컬/기존 환경 호환) — gitops가 양쪽에 동일 시크릿을 주입해 활성화한다. 추가로 공개 진입점인 frontend는 `/internal/ops`를 프록시하지 않는다(에이전트 전용).
3. LLM output으로 API path를 직접 만들지 않는다.
4. tool allowlist 밖 요청은 거부한다.
5. Secret, token, connection string은 prompt와 report에 넣지 않는다.
6. mutation은 approval/change ticket 없이 실행하지 않는다.

### 11. 테스트 기준

- structured output validation 실패 시 repair 또는 fail 처리
- raw evidence inline 저장 차단
- tool allowlist 밖 호출 차단
- approval 없는 mutation 실행 차단
- Spring Boot error envelope 처리
- SSE reconnect 시 event resume 가능
- 현재 Verifier 미통과 report를 차단하지 않고 `report_snapshot.verified=false`로 저장

### 12. 결론

FastAPI Agent Server는 Bifrost의 판단 계층이다. 운영 리소스를 직접 만지는 서버가 아니라, evidence 기반으로 판단하고 Spring Boot Operations Backend에 검증 가능한 tool call을 위임하는 orchestration server로 설계한다.

---

## 3. API Reference

Frontend(BifrostAgentPanel 등)가 호출하는 FastAPI API 명세는 분량이 커 별도 파일로 분리했다 → **[api.md](../../api/fastapi.md)**.

포함 내용: 공통 응답 봉투·표준 에러코드, Health/Metadata·Agent Run·Event Streaming(SSE)·State/Timeline·Evidence·Approval·Change Management·Action Execution·Report·Incident/Alert(소유권)·Catalog/Tool Metadata·Feedback/Audit·Admin API, 금지 API.

---
