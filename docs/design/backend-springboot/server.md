# Spring Boot Operations Backend — Server Design

> 요약은 [overview.md](./overview.md). 이 파일은 서버 자체 설계(목적·책임·신뢰경계·계층·패키지·관측 수집·보안)를 다룬다.
>
> **식별자**: 내부 운영 API의 `project_id`는 프론트/플랫폼 API의 `workspace_id`와 **동일한 테넌트**다(v1에서 `project_id` = `workspace_id`). tool 논리명 ↔ 엔드포인트 매핑은 FastAPI 영역의 Tool Catalog 표를 기준으로 한다(이 문서는 path 기준).

## 1. Server Design

### 1. 목적

Spring Boot Operations Backend는 Bifrost의 **플랫폼 본체이자 실제 운영 제어 계층**이다. 두 가지 책임을 가진다.

1. **플랫폼 기능(frontend-facing)**: 워크스페이스·Database·Pipeline CRUD, Kafka 리소스 프로비저닝([§2 Provisioning](./provisioning.md#2-provisioning)), DB 등록·CDC 점검([§3 Database Registry](./database-registry.md#3-database-registry)), 모니터링·이벤트 조회, 메타데이터 저장([§4 Data Model](./data-model.md#4-data-model)).
2. **운영 조치 실행(agent-facing)**: FastAPI Agent가 만든 판단과 action 후보를 받아 정책·권한·승인·감사·idempotency를 검증한 뒤 Kubernetes, Kafka, Kafka Connect, Prometheus, Strimzi 리소스에 접근한다.

두 경로 모두 최종적으로 같은 정책·감사·프로비저닝 계층을 공유한다.

전체 backend 구조는 [../../README.md](../../README.md)를 기준으로 하고, 이 문서는 Spring Boot 서버 자체만 다룬다. API 상세는 [§5 API Reference](../../api/springboot.md), FastAPI 설계는 [FastAPI 설계](../backend-fastapi/overview.md)를 따른다.

### 2. 책임

Spring Boot가 담당한다.

- FastAPI service identity 검증
- project scope와 resource ownership 검증
- operation allowlist 검증
- approval id와 params hash 검증
- change ticket, execution window, rollback plan 검증
- idempotency 처리
- audit log와 action timeline 저장
- Evidence Store reference 생성
- Fabric8 Kubernetes Client 호출
- Kafka AdminClient 호출
- Kafka Connect REST 호출
- Schema Registry API 호출
- Prometheus/Loki/OpenSearch/Tempo 조회
- KafkaRebalance CR 생성, 조회, approve/refresh

Spring Boot가 담당하지 않는다.

- LLM 호출
- RCA reasoning
- prompt 관리
- Agent State graph orchestration
- Agent run progress streaming(FastAPI SSE/WebSocket)
- Report 문장 생성

### 3. 신뢰 경계

Spring Boot는 FastAPI Agent의 판단을 신뢰하지 않는다. FastAPI가 이미 Policy Guard를 통과했다고 해도 Spring Boot는 실행 직전에 다시 검증한다.

> **이 재검증은 중복 낭비가 아니라 신뢰 경계를 넘는 방어적 설계(defense-in-depth)다.** 두 검사는 목적·신뢰수준이 다르다 — FastAPI의 Policy Guard는 **UX/빠른 피드백**(승인 요청 전 미리 판단해 사용자에게 보여줌)이고 안전엔 필수가 아니다(없어도 Spring이 막는다). Spring의 검사는 **보안·무결성의 최종 집행(SoT)**이다. 부수효과 직전에 반드시 다시 봐야 하는 이유: (a) **TOCTOU** — 판단 시점과 실행 사이에 상태가 변할 수 있다(승인 만료, ownership 변경, params 변조), (b) **confused deputy** — FastAPI가 버그·탈취돼도 Spring이 차단해야 한다. approval·params hash·idempotency는 본질적으로 서버측에서만 보장된다(클라이언트 validation + 서버 validation과 같은 구조).

검증 항목:

| 항목 | 설명 |
| --- | --- |
| service identity | FastAPI 서버가 허용된 caller인가 |
| user/project scope | 요청 사용자가 project 권한을 갖는가 |
| resource ownership | resource가 해당 project 소유 또는 허용 범위인가 |
| operation allowlist | 등록된 operation인가 |
| approval | approval id, action id, params hash가 일치하는가 |
| change management | ticket, window, rollback plan이 유효한가 |
| idempotency | 중복 mutation이 아닌가 |

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
| Change Management | change ticket, 실행 window, rollback plan 검증 |
| Idempotency | 중복 실행 방지와 replay response |
| Operation Service | 운영 의도 단위 use case 처리 |
| Resource Adapter | Fabric8, Kafka, Prometheus 등 외부 client |
| Evidence Writer | raw result 저장과 evidence reference 생성 |
| Audit | 모든 요청, 실행, 차단 사유 기록 |

### 5. 패키지 구조

**구성 원칙 — package-by-feature**: platform 도메인은 각자 `controller/service/repository/dto/entity`를 품어 응집도를 높이고(한 기능을 한 패키지에서 본다), 전역 관심사는 `config`/`common`으로 분리한다. agent-facing(`/internal/ops`)은 인증·응답봉투·idempotency가 platform과 근본적으로 다르고 여러 도메인을 가로지르므로 `internalops` 한 곳에 모은다.

```text
com.bifrost.ops
  ├─ config               # 전역: SecurityConfig, JacksonConfig, OpenApiConfig, WebConfig, KubernetesClientConfig ...
  ├─ common               # request_id, response envelope, 공통 error mapping, validation helper
  ├─ auth                 # 로그인/JWT (controller·service)
  ├─ workspace            # 워크스페이스 (FR-002)
  │   ├─ controller       #   /api/v1/workspaces ...
  │   ├─ service
  │   ├─ repository
  │   ├─ dto
  │   └─ entity
  ├─ database             # DB 등록·연결테스트·CDC 점검 (FR-013~015)
  │   ├─ controller · service · repository · dto · entity
  │   ├─ cdc              #   CdcReadinessChecker 인터페이스 + Postgres/Mariadb 구현
  │   └─ inspector        #   동적 DataSource 조회(연결테스트·schema)
  ├─ pipeline             # 파이프라인 CRUD·생명주기 (FR-003~005)
  │   └─ controller · service · repository · dto · entity
  ├─ provisioning         # 파이프라인 리소스 생성 추상화 + 구현
  │   ├─ port             #   KafkaPipelineProvisioner 인터페이스
  │   ├─ dto              #   command/result/status/resource ref
  │   ├─ mock             #   mock-first E2E 구현
  │   ├─ impl.strimzi     #   Fabric8/Strimzi real 구현
  │   └─ watcher          #   KafkaConnector watch → PipelineStatusService
  ├─ secret               # 자격증명 secretRef 보관(K8s Secret/Secrets Manager)
  ├─ streaming            # platform SSE: pipeline_status_changed 등
  ├─ internalops          # ── agent-facing 전용(/internal/ops). 여러 도메인을 가로지름 ──
  │   ├─ controller · dto · error      # platform과 다른 인증·봉투·idempotency
  │   ├─ project          #   내부 ops project scope/ownership·resource registry
  │   └─ operations       #   observability / kafka / k8s / strimzi / dependency / schema / workflow
  ├─ policy · approval · changemanagement · idempotency   # 정책·승인·변경관리·멱등성 (platform·internalops 공용)
  ├─ audit · evidence
  └─ adapters             # 외부 client: kubernetes · kafka · connect · prometheus · logstore · tempo · notification · schemaregistry
```

설계 의도:
- **platform 도메인 = feature별 레이어드**: `workspace`/`database`/`pipeline`이 각각 `controller·service·repository·dto·entity`를 가져 한 기능 변경이 한 패키지 안에서 끝난다.
- **`internalops` 분리**: agent-facing API는 인증 방식·response envelope·idempotency·evidence/audit 필드가 platform과 달라 섞지 않는다. project scope/ownership과 운영 조회(`operations.*`)도 여기 둔다.
- **`config`/`common` 분리**: 전역 설정과 공통 응답/에러 유틸을 도메인에서 떼어낸다.
- `provisioning.port/dto/mock`은 파이프라인 생성 흐름을 먼저 완성하기 위한 안정 계약, `provisioning.impl.strimzi`·`watcher`는 Fabric8/Strimzi 실제 구현. Platform SSE는 `streaming`, Agent run progress streaming은 FastAPI 담당.

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

정책 기준은 FastAPI catalog와 맞춰야 하지만, 최종 집행은 Spring Boot가 한다.

### 8. Approval과 Change Management

Approval 검증:

1. approval id 존재 여부
2. approval status가 approved인지 확인
3. approval action id와 요청 action id 일치
4. approval tool/operation과 실제 operation 일치
5. params hash 일치
6. 승인자 권한 확인
7. expiry 확인
8. single-use 여부 확인

Change Management 검증:

1. change ticket 존재
2. ticket status approved
3. 현재 시간이 execution window 안
4. rollback plan 존재
5. impact analysis 존재
6. requested operation과 ticket scope 일치

### 9. Idempotency

모든 mutation API는 `X-Idempotency-Key`를 요구한다.

처리 규칙:

| 상황 | 처리 |
| --- | --- |
| 같은 key + 같은 params | 이전 response replay |
| 같은 key + 다른 params | `CONFLICT` |
| key 없음 | `VALIDATION_FAILED` |
| 실행 중 중복 요청 | 기존 execution status 반환 |

Mutation timeout이 발생해도 자동 재시도하지 않는다. read-only after-check로 실제 상태를 확인한다.

### 10. Evidence와 Audit

Spring Boot는 운영 조회 결과와 실행 전후 snapshot을 Evidence Store에 저장하고 reference만 FastAPI에 반환한다.

Audit event는 성공, 실패, 차단을 모두 기록한다.

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
| Tempo Client | trace summary query |
| Notification / Ticket Adapter | escalation, notification, ticket 생성 |
| Schema Registry Client | schema subject/version/change |

Adapter는 controller에서 직접 호출하지 않고 operation service를 통해 호출한다.

### 11.1 관측·모니터링 데이터 수집 (상태 vs 지표)

모니터링은 단일 메커니즘이 아니다. **상태(state)와 지표(metric)를 다른 경로로** 모은다 — Watcher만으로는 부족하고(상태 전이 전용), 지표·로그·트레이스는 폴링/질의로 가져온다. 데이터 소스·주기의 단일 출처는 [기능명세서 부록 B](../../spec.md#부록-b--리소스-상태값-정의-및-자동-기준-단일-출처)다.

| 경로 | 방식 | 수집 대상 | 소스 / 주기 |
| --- | --- | --- | --- |
| Watcher (Fabric8) | watch(event) | connector/pipeline **상태 전이** | KafkaConnector CR `.status` → `PipelineStatusService` → SSE ([§2 Provisioning §6](./provisioning.md#2-provisioning)) |
| 폴링 수집기 | 주기 polling | consumer lag·offset·그룹 상태, connector task, worker JVM, DB 상태 | Kafka AdminClient(30s)·Connect REST(10s)·Jolokia JMX(60s)·DB ping(5s) (부록 B.1/B.2/B.4/B.6) |
| 쿼리 어댑터 | on-demand | metric·log·trace | Prometheus·Loki·Tempo HTTP client ([§11](#11-resource-adapter)) |

수집 결과의 쓰임: (a) 임계 초과 시 `event`/`incident` **자동 생성**([부록 B.6/B.7](../../spec.md#b6-이벤트-카탈로그)) + SSE, (b) 플랫폼 API(`/api/v1/.../metrics`·`/consumer-groups`·`/connectors`·`/cluster` 등)로 **프론트 시각화**(FR-006~009·017·023, [frontend §6/§7](../frontend.md)), (c) Agent의 `/internal/ops` read tool 근거.

**임베딩하지 않는 것** (별도 스택을 두고 Spring이 질의/소비):

- **Prometheus·Loki·Tempo**: `monitoring` 네임스페이스의 **별도 스택**([infra §6.7](../infra.md#2-리소스-계획현황-resource-plan)). Spring은 HTTP client로 **질의만** 하고, 자신의 지표는 `/actuator/prometheus`로 노출해 scrape 대상이 된다.
- **Grafana**: 운영자용 대시보드이며 제품 화면이 아니다. 사용자 화면은 프론트가 위 플랫폼 API로 구성한다.
- **Kafka UI**: 제품에 포함하지 않는다(로컬 `docker-compose` 개발 편의 도구 한정 — [guide](../../guides/getting-started-infra.md)). "Connect REST를 사용자에게 직접 노출하지 않는다"는 원칙과 일치한다.

### 12. 보안 원칙

1. internal API는 외부 공개하지 않는다.
2. FastAPI service account만 호출할 수 있다.
3. 사용자 권한은 FastAPI 전달값을 믿지 않고 backend에서 재확인한다.
4. Secret 원문을 반환하지 않는다.
5. Kubernetes RBAC은 namespace/resource 단위 최소 권한으로 둔다.
6. Kafka credential은 operation별 service account로 제한한다.
7. 모든 mutation은 audit와 idempotency를 필수로 한다.

### 13. 테스트 기준

- read operation은 approval 없이 성공해야 한다.
- mutation은 approval 없이 실패해야 한다.
- params hash 불일치 approval은 실패해야 한다.
- change window 밖 execution은 실패해야 한다.
- idempotency replay가 중복 실행을 만들지 않아야 한다.
- before/after evidence reference가 생성되어야 한다.
- forbidden operation은 endpoint가 없거나 policy deny되어야 한다.
- FastAPI service identity가 없으면 실패해야 한다.

### 14. 결론

Spring Boot Operations Backend는 Bifrost 운영 제어의 최종 집행자다. Agent가 무엇을 제안하더라도 Spring Boot가 project scope, policy, approval, change management, idempotency, audit를 통과시킨 요청만 runtime에 반영한다.
