# Bifrost 문서

엔터프라이즈 내부(폐쇄망·온프레미스) 환경에서 **데이터 파이프라인을 구축·운영·장애대응**하는 플랫폼. Kafka·Kafka Connect 같은 복잡한 내부를 직접 다루지 않고도 데이터 흐름을 만들고(파이프라인 생성), 상태를 보고(모니터링), 문제를 빠르게 대응(AI 운영 보조)할 수 있게 한다.

> AI 장애 대응 원칙: **LLM은 RCA Engine이 아니라 RCA Assistant다.** 원인을 자유 생성하지 않고, 허용된 원인 후보 중 evidence와 가장 잘 맞는 것을 선택·설명한다.

## 문서 인덱스

| 분류 | 문서 | 내용 |
| --- | --- | --- |
| 요구사항(SoT) | [spec.md](./spec.md) | 기능명세서 — FR 카탈로그 + 부록 B(상태값·임계값·이벤트→인시던트 규칙)의 **단일 출처** |
| 시나리오 | [scenario.md](./scenario.md) | 시연 Critical Path, 통합 시나리오, 완료 정의(DoD) |
| 설계 | [design/infra.md](./design/infra.md) | 인프라(단일 EKS·Strimzi·Harbor·CI/CD·Observability) |
| 설계 | [design/frontend.md](./design/frontend.md) | 프론트엔드 화면(FR)별 백엔드 연동 |
| 설계 | [design/backend-springboot/](./design/backend-springboot/overview.md) | Spring Boot Operations Backend(플랫폼 본체·운영 집행) |
| 설계 | [design/backend-fastapi/](./design/backend-fastapi/overview.md) | FastAPI Agent Server(AI 장애대응 workflow) |
| API | [api/springboot.md](./api/springboot.md) | 플랫폼 `/api/v1` + 내부 전용 운영 `/internal/ops` |
| API | [api/fastapi.md](./api/fastapi.md) | Agent `/api/v1/agent…` |
| 결정 | [adr/](./adr/) | 아키텍처 결정 기록(ADR) |
| 팀 | [team/git-convention.md](./team/git-convention.md) | git 컨벤션 |

## 전체 구조

```text
Frontend (운영 콘솔)
  ├─ 플랫폼·모니터링 ───► Spring Boot Operations Backend  (/api/v1)
  └─ AI 장애대응 ───────► FastAPI Agent Server            (/api/v1/agent)
                            └─► Spring Boot (/internal/ops, internal only)
Spring Boot Operations Backend
  -> Fabric8 / Strimzi / Kafka AdminClient / Kafka Connect REST
  -> Prometheus / Loki / Tempo
  -> source/sink DB (JDBC) / 메타데이터 DB
```

- **Spring Boot Operations Backend**가 플랫폼 본체다. 워크스페이스·DB·파이프라인 CRUD, Kafka 프로비저닝(Fabric8/Strimzi), DB 등록·CDC 점검, 모니터링, 메타데이터, 그리고 에이전트 조치의 정책·승인·감사·실행을 담당한다.
- **FastAPI Agent Server**는 AI 장애 대응만 맡는다. Supervisor가 8개 LLM agent와 결정론적 단계로 구성된 workflow를 제어하며, 운영 조회·조치는 내부 서비스 경로로 Spring Boot `/internal/ops/**`에 위임한다. public frontend ingress는 `/internal/ops/**`를 프록시하지 않고, 브라우저/외부 클라이언트는 `/api/**` 계열만 호출한다. (MCP는 v1 미사용)

## 식별자

- **`workspace_id`(=`project_id`, uuid)**: scope·소유권 검증용 내부 키. 프론트/플랫폼은 `workspace_id`, 에이전트/내부 운영 API는 같은 테넌트를 `project_id`로 부른다(v1에서 동일).
- **`projectKey`(슬러그)**: 워크스페이스 이름에서 자동 생성하는 영소문자·숫자·하이픈 문자열. Kafka 토픽·ACL·KafkaUser 등 DNS-safe 리소스 이름의 기준.
- 코드/DB는 `tenant`/`datasource` 용어를 쓰되 설계 용어(`workspace`/`database`)와 공존한다([ADR 0002](./adr/0002-multi-tenancy-model.md), [ADR 0004](./adr/0004-monorepo-monolith.md)).

## 소유권 · SoT 맵

> 같은 정보를 여러 곳에서 정의하지 않도록, 각 항목의 **정본(Source of Truth)** 을 한 곳으로 고정한다. 나머지 문서/서비스는 인용·미러링만 한다.

| 항목 | 소유(SoT) | 정본 위치 |
| --- | --- | --- |
| 기능 요구사항(FR) | 기능명세 | [spec.md](./spec.md) |
| 상태값·임계값·이벤트→인시던트 규칙 | 기능명세 부록 B | [spec.md 부록 B](./spec.md#부록-b--리소스-상태값-정의-및-자동-기준-단일-출처) |
| 식별자(`workspace_id`=`project_id`·`projectKey`) | 공통 규약 | [위 식별자](#식별자) |
| 플랫폼 메타데이터(workspace·DB·pipeline·connector·event·incident·audit) | **Spring**(`metadb`) | [data-model.md](./design/backend-springboot/data-model.md#4-data-model) |
| 운영 operation·집행 allowlist(무엇을 실행하나) | **Spring** | [server.md §7.1](./design/backend-springboot/server.md#71-operation-allowlist-집행-경계-단일-출처) |
| approval·change·idempotency 검증·audit 원본 | **Spring** | [server.md §8](./design/backend-springboot/server.md#8-approval과-change-management) |
| Evidence 원문(운영 raw) | **Spring**(Evidence Store) | [data-model.md §3.9](./design/backend-springboot/data-model.md#39-evidence_ref) |
| DB 연결테스트 오류 5종 분류 | **Spring** | [database-registry.md §2](./design/backend-springboot/database-registry.md#2-step-1--연결-테스트-동적-datasource) |
| 모니터링 수집(상태=watch / 지표=폴링·질의) | **Spring** | [server.md §11.1](./design/backend-springboot/server.md#111-관측모니터링-데이터-수집-상태-vs-지표) |
| RCA 판단 카탈로그(장애유형·root cause·evidence matrix·correlation·runbook·policy) | **FastAPI** | [catalog-* §6~§12](./design/backend-fastapi/catalog/catalog-failure-types.md#6-catalog-failure-types) |
| Agent run 상태(run·state·event·approval facade·report) | **FastAPI**(`agentdb`) | [contract-state-schema §14](./design/backend-fastapi/contract/contract-state-schema.md#14-contract-state-schema) |
| Knowledge 코퍼스(RAG runbook·문서) | **FastAPI**(Vector Store) | [server-design §9](./design/backend-fastapi/server-design.md#2-server-design) |
| Tool 매핑(논리 tool→operation) | **FastAPI** Tool Client Registry | [tool-catalog.md §8·§9](./design/backend-fastapi/tool-catalog.md#4-tool-catalog) |
| CI 파이프라인·이미지 빌드 | **Jenkinsfile** + Jenkins JCasC | [infra/cicd README](../infra/cicd/README.md#ci-파이프라인-job-bifrost-ci) |
| GitOps 배포 구조 | **gitops 브랜치** `argocd/`, `charts/`, `databases/`, `infra/`, `secrets/` | [infra.md §7](./design/infra.md#7-cicd) |
| API 에러코드 | 표면별(각자 소유) | [Spring](./api/springboot.md) · [FastAPI](./api/fastapi.md) |
| 인프라 현황(클러스터·용량) | 인프라 | [infra.md §2](./design/infra.md#2-리소스-계획현황-resource-plan) |

> 방향 주의: **실행(tool/operation·allowlist)은 Spring이 정본**(FastAPI는 미러), **판단(RCA 카탈로그)은 FastAPI가 정본**(Spring은 모름), **임계값·상태 규칙은 spec 부록 B가 정본**(양쪽이 인용).

## 읽는 순서

1. 이 문서(README) → 2. [spec.md](./spec.md)(FR·임계값) → 3. [scenario.md](./scenario.md) → 4. 설계 [design/](./design/) → 5. API [api/](./api/)

상태값(`pipeline.status`·`connector.state` 등)·임계값(consumer lag 5,000/50,000, error rate 0.5%/2% 등)·이벤트→인시던트 규칙의 **단일 출처는 [spec.md 부록 B](./spec.md#부록-b--리소스-상태값-정의-및-자동-기준-단일-출처)**다. 다른 문서는 중복 정의하지 않고 이를 인용한다.
