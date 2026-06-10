# Spring Boot Operations Backend — Server Design

> 요약은 [overview.md](./overview.md). 이 파일은 서버 자체 설계(목적·책임·신뢰경계·계층·패키지·관측 수집·보안)를 다룬다.
>
> **식별자**: 프론트/플랫폼 API의 `{wsId}`와 FastAPI agentdb `project_id`는 workspace UUID다. Spring `/internal/ops/projects/{projectId}` path variable은 현재 대부분 controller에서 workspace `namespace`/`projectKey` slug로 조회한다(`list_alerts`만 UUID fallback을 가진다). tool 논리명 ↔ 엔드포인트 매핑은 FastAPI 영역의 Tool Catalog 표를 기준으로 한다(이 문서는 path 기준).

## 1. Server Design

### 1. 목적

Spring Boot Operations Backend는 Bifrost의 **플랫폼 본체이자 실제 운영 제어 계층**이다. 두 가지 책임을 가진다.

1. **플랫폼 기능(frontend-facing)**: 워크스페이스·Database·Pipeline CRUD, Kafka 리소스 프로비저닝([§2 Provisioning](./provisioning.md#2-provisioning)), DB 등록·CDC 점검([§3 Database Registry](./database-registry.md#3-database-registry)), 모니터링·이벤트 조회, 메타데이터 저장([§4 Data Model](./data-model.md#4-data-model)).
2. **운영 조치 실행(agent-facing)**: FastAPI Agent가 만든 판단과 action 후보를 받아 정책·권한·승인·감사·idempotency를 검증한 뒤 Kubernetes, Kafka, Kafka Connect, Prometheus, Strimzi 리소스에 접근한다.

두 경로 모두 최종적으로 같은 정책·감사·프로비저닝 계층을 공유한다.

전체 backend 구조는 [../../README.md](../../README.md)를 기준으로 하고, 이 문서는 Spring Boot 서버 자체만 다룬다. API 상세는 [§5 API Reference](../../api/springboot.md), FastAPI 설계는 [FastAPI 설계](../backend-fastapi/overview.md)를 따른다.

### 2. 책임

현재 코드가 담당한다.

- platform JWT 인증과 workspace access/OWNER·ADMIN 권한 검증
- `/internal/ops` read tool catalog와 read endpoint 제공
- internal mutation의 project/resource ownership 검증
- internal mutation의 approval id와 params hash 검증
- idempotency 처리
- Kafka Connect REST connector restart/pause/resume 호출
- Kafka Connect-managed consumer group restart 요청 처리
- Database/Pipeline/Kafka principal/Monitoring platform API 제공
- Kafka principal Secret reference 조회(`MASKED_REFERENCE_ONLY`, 원문 미반환)

설계상 보강 대상이지만 현재 코드 계약으로 쓰면 안 되는 항목:

- `/internal/ops/**` FastAPI service identity 인증. 현재 `SecurityConfig`는 `/internal/ops/**`를 permitAll로 둔다.
- change ticket execution window, rollback plan, impact analysis, operation scope 검증.
- mutation before/after evidence writer와 audit event append-only 기록.
- Kubernetes/Prometheus/Schema Registry mutation 또는 KafkaRebalance approve/refresh endpoint.

Spring Boot가 담당하지 않는다.

- LLM 호출
- RCA reasoning
- prompt 관리
- Agent State graph orchestration
- Agent run progress streaming(FastAPI SSE)
- Report 문장 생성

### 3. 신뢰 경계

Spring Boot는 FastAPI Agent의 판단을 신뢰하지 않는다. FastAPI가 이미 Policy Guard를 통과했다고 해도 Spring Boot는 실행 직전에 다시 검증한다.

> **이 재검증은 중복 낭비가 아니라 신뢰 경계를 넘는 방어적 설계(defense-in-depth)다.** 두 검사는 목적·신뢰수준이 다르다 — FastAPI의 Policy Guard는 **UX/빠른 피드백**(승인 요청 전 미리 판단해 사용자에게 보여줌)이고 안전엔 필수가 아니다(없어도 Spring이 막는다). Spring의 검사는 **보안·무결성의 최종 집행(SoT)**이다. 부수효과 직전에 반드시 다시 봐야 하는 이유: (a) **TOCTOU** — 판단 시점과 실행 사이에 상태가 변할 수 있다(승인 만료, ownership 변경, params 변조), (b) **confused deputy** — FastAPI가 버그·탈취돼도 Spring이 차단해야 한다. approval·params hash·idempotency는 본질적으로 서버측에서만 보장된다(클라이언트 validation + 서버 validation과 같은 구조).

검증 항목:

| 항목 | 설명 |
| --- | --- |
| service identity | 설계 목표. 현재 `/internal/ops/**` path는 permitAll이며 별도 service account gate가 없다 |
| user/project scope | 요청 사용자가 project 권한을 갖는가 |
| resource ownership | resource가 해당 project 소유 또는 허용 범위인가 |
| operation allowlist | 현재는 controller endpoint 자체가 allowlist. runtime catalog는 read 8개만 노출 |
| approval | approval id, operation, tenant, params hash, expiry, single-use 검증 |
| change management | facade는 있으나 mutation gate에는 아직 연결되지 않음 |
| idempotency | 중복 mutation replay/conflict 처리 |

### 4. 내부 계층

```text
Controller
  -> Request Validation
  -> Auth / Project Scope
  -> Policy Guard
  -> Approval / Change Management
  -> Idempotency
  -> Operation Service
  -> Resource Adapter
  -> Evidence Writer
  -> Audit / Timeline
```

| 계층 | 책임 |
| --- | --- |
| Controller | platform API와 internal ops API 수신, 각 표면의 DTO validation |
| Auth / Scope | service identity, project, resource 권한 검증 |
| Policy | operation 위험도와 허용 범위 판단 |
| Approval | approval id, approver, scope, params hash 검증 |
| Change Management | change ticket 존재, tenant ownership, OPEN status 검증 |
| Idempotency | 중복 실행 방지와 replay response |
| Operation Service | 운영 의도 단위 use case 처리 |
| Resource Adapter | Fabric8, Kafka, Prometheus 등 외부 client |
| Evidence Writer | raw result 저장과 evidence reference 생성 |
| Audit | 모든 요청, 실행, 차단 사유 기록 |

위 계층 표는 목표 구조다. 현재 internal mutation 구현은 header 검증 → project/resource ownership → idempotency → approval → Kafka Connect REST → response snapshot 순서의 좁은 subset이며, evidence writer/audit layer와 change-management gate는 아직 연결되어 있지 않다.

### 5. 패키지 구조

**구성 원칙 — package-by-feature**: platform 도메인은 각자 `controller/service/repository/dto/entity`를 품어 응집도를 높이고(한 기능을 한 패키지에서 본다), 전역 관심사는 `global`(config·common)로 묶는다. 모니터링 read·이벤트·인시던트는 별도 도메인 패키지(`monitoring`/`event`/`incident`)로 두고, 운영 조치 거버넌스(정책·승인·변경관리·멱등성·감사·증거)는 `governance`로 묶는다. agent-facing(`/internal/ops`)은 인증·응답봉투·idempotency가 platform과 근본적으로 다르고 여러 도메인을 가로지르므로 `internalops` 한 곳에 모은다.

```text
com.bifrost.ops
  ├─ global               # 전역 관심사 묶음
  │   ├─ config           #   SecurityConfig, JacksonConfig, OpenApiConfig, WebConfig, KubernetesClientConfig ...
  │   └─ common           #   request_id, response envelope, 공통 error mapping, validation helper
  ├─ auth                 # 로그인/JWT (controller·service)
  ├─ workspace            # 워크스페이스 (FR-002)
  │   └─ controller · service · repository · dto · entity
  ├─ database             # DB 등록·연결테스트·CDC 점검 (FR-013~015)
  │   ├─ controller · service · repository · dto · entity
  │   ├─ cdc              #   CdcReadinessStatus enum
  │   └─ inspector        #   DatabaseInspector + Postgres/MariaDB 구현(연결테스트·schema·readiness)
  ├─ pipeline             # 파이프라인 CRUD·생명주기 (FR-003~005). 상세 탭 read는 PipelineSync/Topic/Message service가 직접 처리
  │   └─ controller · service · repository · dto · entity
  ├─ provisioning         # 파이프라인 리소스 생성 추상화 + 구현
  │   ├─ port             #   KafkaPipelineProvisioner 인터페이스
  │   ├─ dto              #   command/result/status/resource ref
  │   ├─ impl.strimzi     #   Fabric8/Strimzi 구현(단일)
  │   └─ watcher          #   KafkaConnector watch → PipelineStatusService
  ├─ monitoring           # 플랫폼 read + 폴링 수집기 (FR-006~009·017·023)
  │   ├─ query            #   현재 KafkaMetricsQuery 중심. shared read port layer가 아님
  │   └─ collector        #   주기 폴링(Kafka Admin 30s·Connect REST 10s·Prometheus 60s·DB health 60s) → event 기록/DB health 재계산 일부
  ├─ event                # 이벤트 로그·카탈로그 (FR-019·024, 부록 B.5/B.6)
  ├─ incident             # IncidentService 메서드 보유; poller 자동 생성 연결은 보강 대상 (FR-021·026, 부록 B.7)
  ├─ secret               # SecretStore port + 현재 DbSecretStore(metadb secrets.credential_json)
  ├─ streaming            # platform SSE: pipeline_status_changed·connector_state_changed·incident_opened ...
  ├─ internalops          # ── agent-facing 전용(/internal/ops). 여러 도메인을 가로지름 ──
  │   ├─ controller · dto · error      # platform과 다른 인증·봉투·idempotency
  │   ├─ project          #   내부 ops project scope/ownership·resource registry
  │   └─ operations       #   observability/kafka/k8s/strimzi/dependency/schema/workflow — controller/service별 직접 주입
  ├─ governance           # 운영 조치 거버넌스(§4 실행 체인, platform·internalops 공용)
  │   ├─ policy · approval · changemanagement · idempotency
  │   └─ audit · evidence
  └─ adapters             # 외부 client(+port 인터페이스): kubernetes · kafka · connect · prometheus · logstore · notification · schemaregistry. Tempo adapter는 현재 없음
```

설계 의도:
- **platform 도메인 = feature별 레이어드**: `workspace`/`database`/`pipeline`이 각각 `controller·service·repository·dto·entity`를 가져 한 기능 변경이 한 패키지 안에서 끝난다.
- **`global` 묶음**: 전역 설정(`config`)과 공통 응답/에러 유틸(`common`)을 한 상위 패키지로 모아 도메인에서 떼어낸다.
- **모니터링·이벤트·인시던트 분리**: 폴링 수집기는 `monitoring`, 이벤트 로그는 `event`, 인시던트 관련 메서드는 `incident`로 둔다(부록 B.6/B.7). 현재 poller는 `IncidentService`를 호출하지 않고, `PipelineController`의 상세 탭은 `PipelineSyncService`/`PipelineTopicService`/`PipelineMessageService`를 직접 호출한다.
- **read 로직 단일화 상태**: `monitoring.query` 공유 port는 목표 구조지만 현재 `InternalOpsObservabilityController`는 `AdminClient`/`LokiClient`/`JdbcTemplate`/`IncidentRepository`/`RestClient`를 직접 주입한다.
- **`internalops` 분리**: agent-facing API는 인증 방식·response envelope·idempotency·evidence/audit 필드가 platform과 달라 섞지 않는다. project scope/ownership도 여기 둔다.
- **`governance` 묶음**: 정책·승인·변경관리·멱등성·감사·증거는 §4 실행 체인을 이루는 횡단 관심사라 한 상위 패키지로 모은다(platform·internalops 공용).
- **추상화 일관성(mock-first)**: `provisioning`·`secret`처럼 외부 의존(`adapters`의 kafka/k8s/prometheus/connect)도 port 인터페이스를 두어 mock 교체·테스트가 가능하게 한다. `provisioning.impl.strimzi`·`watcher`는 Fabric8/Strimzi 실제 구현, Platform SSE는 `streaming`, Agent run progress streaming은 FastAPI 담당.

> ⚠️ 이 구조는 **설계 목표**다. 패키지 skeleton은 권세빈 담당이고 현재 코드는 일부만 이 형태이므로, 코드 재배치는 별도 합의·chore로 진행한다(이 문서는 목표 구조만 정의).

### 6. Read와 Mutation 분리

Read-only operation은 project scope와 resource ownership을 통과하면 자동 실행할 수 있다.

예시:

- connector status 조회
- consumer lag 조회
- pod status 조회
- deployment health 조회
- metric query
- log search
- recent change 조회

Mutation operation은 approval 또는 change management 없이는 실행하지 않는다.

예시:

- connector task restart
- connector pause/resume
- deployment scale
- pipeline pause/resume
- KafkaRebalance approve
- backfill/rollback

### 7. Operation Policy

| Operation 유형 | 기본 정책 |
| --- | --- |
| read-only | allow |
| low-risk internal state | allow |
| runtime state change | require_approval |
| data replay / rollback / config change | require_change_management |
| delete / exec / arbitrary SQL / secret raw read | deny |

agent의 tool catalog·policy matrix는 이 정책을 **미러링한 사전 판단**이고, **정본·최종 집행은 Spring Boot(§7.1 allowlist)**다. 불일치 시 Spring 기준이 우선한다.

### 7.1 Operation Allowlist (현재 집행 경계)

Spring API의 현재 controller family는 [Controller Coverage](../../api/springboot.md#controller-coverage)에 정리한다. `GET /internal/ops/admin/tool-catalog`는 구현된 read operation allowlist를 반환하며, mutation은 이 catalog에 포함하지 않는다.

**현재 read catalog 8개**

| Operation | Endpoint |
| --- | --- |
| `get_consumer_lag` | `GET /internal/ops/projects/{projectId}/kafka/consumer-groups/{consumerGroup}/lag` |
| `search_logs` | `POST /internal/ops/projects/{projectId}/observability/logs/search` |
| `query_traces` | `GET /internal/ops/projects/{projectId}/connectors/{connectorName}/traces` |
| `list_alerts` | `GET /internal/ops/projects/{projectId}/observability/alerts` |
| `get_incident_summary` | `GET /internal/ops/incidents/{incidentId}/summary` |
| `list_project_pipelines` | `GET /internal/ops/projects/{projectId}/pipelines` |
| `get_pipeline_topology` | `GET /internal/ops/projects/{projectId}/pipelines/{pipelineId}/topology` |
| `get_connector_status` | `GET /internal/ops/projects/{projectId}/kafka/connectors/{connectorName}/status` |

**현재 mutation subset**

| Operation | Endpoint | Gate |
| --- | --- | --- |
| `restart_connector` | `POST /internal/ops/projects/{projectId}/connectors/{connectorName}/restart` | approval + idempotency |
| `pause_connector` | `POST /internal/ops/projects/{projectId}/connectors/{connectorName}/pause` | approval + idempotency |
| `resume_connector` | `POST /internal/ops/projects/{projectId}/connectors/{connectorName}/resume` | approval + idempotency |
| `restart_consumer_group` | `POST /internal/ops/projects/{projectId}/kafka/consumer-groups/{consumerGroup}/restart` | approval + idempotency |

`get_metrics`, `get_deployments`, `get_kafka_lag` alias, deployment scale, rebalance, pipeline backfill/rollback, connector config patch, topic mutation, pod exec, arbitrary SQL, secret raw read는 현재 Spring runtime catalog/endpoint가 아니다. Secret raw read는 정책상 금지이며 Kafka principal secret API도 reference와 masked value만 반환한다.

### 8. Approval과 Change Management

Approval 검증:

1. approval id 존재 여부
2. approval status가 approved인지 확인
3. mutation 경로는 approval operation과 실제 operation 일치
4. params hash 일치
5. expiry 확인
6. single-use consumed 처리

Facade `POST /internal/ops/approvals/{approvalId}/validate`는 `tenantId`와 `paramsHash`만 받아 tenant ownership과 params hash/expiry/single-use/status를 검증한다. action id field는 현재 entity/DTO에 없다.
8. single-use 여부 확인

Change Management 검증:

현재 facade는 change ticket 존재, tenant 소속, status `OPEN`만 검증한다. execution window, rollback plan, impact analysis, requested operation scope 검증은 현재 entity/controller 필드에 없다.

### 9. Idempotency

모든 mutation API는 `X-Idempotency-Key`를 요구한다.

처리 규칙:

| 상황 | 처리 |
| --- | --- |
| 같은 key + 같은 operation/params + 완료 | 이전 response replay. cached JSON parse가 성공하면 원래 status를 그대로 반환하고, `IDEMPOTENCY_REPLAY`는 fallback result 구성 시에만 사용 |
| 같은 key + 다른 operation/params | `CONFLICT` |
| key 없음 | `VALIDATION_FAILED` |
| 실행 중 중복 요청 | `CONFLICT` |

Mutation timeout이 발생해도 Spring은 자동 재시도하지 않는다. Kafka Connect timeout은 현재 `504 TIMEOUT` envelope로 반환·저장된다. FastAPI는 Spring non-ok envelope를 `ToolStatus.FAILED`로 매핑하므로 executor의 `ToolStatus.TIMEOUT` after-check 경로에는 들어가지 않는다.

### 10. Evidence와 Audit

현재 internal mutation 응답의 `evidence`는 빈 배열이고 `auditEventId` 값은 null이라 JSON 응답에서는 `audit_event_id` field가 생략된다. 성공, 실패, 차단을 모두 append-only audit으로 기록하는 것은 구현 보강 대상이다.

기록 항목:

- request id
- run id
- actor
- project id
- operation
- resource
- policy decision
- approval id 또는 change ticket id
- idempotency key
- before evidence id
- after evidence id
- result status
- error code

### 11. Resource Adapter

| Adapter | 사용 대상 |
| --- | --- |
| Fabric8 Kubernetes Client | Deployment, Pod, Event, PVC, Strimzi CR |
| Kafka AdminClient | Topic, ConsumerGroup, Broker metadata |
| Kafka Connect REST Client | Connector status, restart, pause/resume |
| Prometheus HTTP Client | metric query |
| Log Store Client | log search |
| Trace 조회 | 현재 별도 Tempo adapter 없음. internal-ops `query_traces`는 Connect REST task `trace` field를 조회 |
| Notification / Ticket Adapter | escalation, notification, ticket 생성 |
| Schema Registry Client | schema subject/version/change |

목표 구조는 adapter를 operation service 뒤에 두는 것이지만, 현재 `InternalOpsObservabilityController`는 `AdminClient`/`LokiClient`/`JdbcTemplate`/`IncidentRepository`/`RestClient`를 직접 주입한다.

### 11.1 관측·모니터링 데이터 수집 (상태 vs 지표)

모니터링은 단일 메커니즘이 아니다. **상태(state)와 지표(metric)를 다른 경로로** 모은다 — Watcher만으로는 부족하고(상태 전이 전용), 지표·로그·트레이스는 폴링/질의로 가져온다. 데이터 소스·주기의 단일 출처는 [기능명세서 부록 B](../../spec.md#부록-b--리소스-상태값-정의-및-자동-기준-단일-출처)다. 수집기·상태 산정·이벤트/인시던트 엔진·Sync/Messages/Metrics·SSE의 **구현 상세는 [monitoring.md](./monitoring.md)** 에 둔다.

| 경로 | 방식 | 수집 대상 | 소스 / 주기 |
| --- | --- | --- | --- |
| Watcher (Fabric8) | watch(event) | connector/pipeline **상태 전이** | KafkaConnector CR `.status` → `PipelineStatusService` → SSE ([§2 Provisioning §6](./provisioning.md#2-provisioning)) |
| 폴링 수집기 | 주기 polling | sink consumer lag, connector task event, worker JVM, DB 상태 | Kafka AdminClient(30s)·Connect REST(10s)·Prometheus/PromQL(60s, `prometheus.enabled`)·DB health(60s) (부록 B.1/B.2/B.4/B.6) |
| 쿼리 어댑터 | on-demand | metric·log·trace | Prometheus·Loki·Connect REST. 현재 trace는 Tempo가 아니라 Connect REST task `trace` field |

수집 결과의 쓰임: (a) `event` 기록(Watcher/PipelineStatusService 등 일부 경로는 SSE도 직접 발행), (b) 플랫폼 API(`/api/v1/.../metrics`·`/consumer-groups`·`/connectors`·`/cluster` 등)로 **프론트 시각화**(FR-006~009·017·023, [frontend §6/§7](../frontend.md)), (c) Agent의 `/internal/ops` read tool 근거. 현재 poller는 incident 자동 생성 메서드를 호출하지 않는다.

**임베딩하지 않는 것** (별도 스택을 두고 Spring이 질의/소비):

- **Prometheus·Loki**: `monitoring` 네임스페이스의 **별도 스택**([infra §6.7](../infra.md#67-observability)). Spring은 HTTP client로 **질의만** 한다. 현재 build에는 `spring-boot-starter-actuator`만 있고 `micrometer-registry-prometheus` 의존성은 없어 `/actuator/prometheus` 노출은 구현되어 있지 않다. trace evidence는 Tempo adapter가 아니라 Connect REST status trace에서 가져온다.
- **Grafana**: 운영자용 대시보드이며 제품 화면이 아니다. 사용자 화면은 프론트가 위 플랫폼 API로 구성한다.
- **Kafka UI**: 제품에 포함하지 않는다(로컬 `docker-compose` 개발 편의 도구 한정 — [guide](../../guides/getting-started-infra.md)). "Connect REST를 사용자에게 직접 노출하지 않는다"는 원칙과 일치한다.

### 12. 보안 원칙

1. internal API는 외부 공개하지 않는다.
2. `/internal/ops/**` service account 인증은 보강 대상이다. 현재 path security는 permitAll이므로 네트워크 경계와 controller gate에 의존한다.
3. 사용자 권한은 FastAPI 전달값을 믿지 않고 backend에서 재확인한다.
4. Secret 원문을 반환하지 않는다.
5. Kubernetes RBAC은 namespace/resource 단위 최소 권한으로 둔다.
6. Kafka credential은 operation별 service account로 제한한다.
7. 현재 mutation은 idempotency를 필수로 한다. audit append-only 기록은 보강 대상이다.

### 13. 테스트 기준

- read operation은 approval 없이 성공해야 한다.
- mutation은 approval 없이 실패해야 한다.
- params hash 불일치 approval은 실패해야 한다.
- change window 밖 execution 차단은 change-management 확장 후 테스트 대상이다. 현재 mutation gate에는 change ticket/window가 없다.
- idempotency replay가 중복 실행을 만들지 않아야 한다.
- before/after evidence reference 생성은 구현 보강 대상이다. 현재 regression 기준에서는 envelope field가 비어 있음을 확인한다.
- forbidden operation은 endpoint가 없거나 policy deny되어야 한다.
- FastAPI service identity가 없으면 실패해야 한다는 항목은 보안 목표다. 현재 코드 기준 regression으로는 적용되지 않는다.

### 14. 결론

Spring Boot Operations Backend는 Bifrost 운영 제어의 최종 집행자다. 현재 internal mutation controller는 header/ownership, `X-Approval-Id`, idempotency, approval validation, Kafka Connect 호출, idempotency snapshot을 처리한다. policy engine, change management gate, audit/evidence write-through는 `MutationGate`/설계 표면에는 있으나 현재 mutation runtime path에는 연결되어 있지 않다.
