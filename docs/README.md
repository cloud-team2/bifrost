# Bifrost 문서

엔터프라이즈 내부(폐쇄망·온프레미스) 환경에서 **데이터 파이프라인을 구축·운영·장애대응**하는 플랫폼. Kafka·Kafka Connect 같은 복잡한 내부를 직접 다루지 않고도 데이터 흐름을 만들고(파이프라인 생성), 상태를 보고(모니터링), 문제를 빠르게 대응(AI 운영 보조)할 수 있게 한다.

> AI 장애 대응 원칙: **LLM은 RCA Engine이 아니라 RCA Assistant다.** 원인을 자유 생성하지 않고, 허용된 원인 후보 중 evidence와 가장 잘 맞는 것을 선택·설명한다.

## 문서 인덱스

| 분류 | 문서 | 내용 |
| --- | --- | --- |
| 요구사항 | [spec.md](./spec.md) | 기능명세서 — FR 카탈로그 + 상태값·임계값·이벤트→인시던트 규칙 정리 |
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

## 코드 기준 정본 맵

> 최종 SOT(Source of Truth)는 코드 구현 내용이다. 문서는 현재 구현을 읽기 쉽게 설명하는 보조 레이어이며, 코드와 문서가 다르면 코드를 기준으로 문서를 고친다.

| 항목 | 코드 기준 | 사람이 읽는 문서 |
| --- | --- | --- |
| 플랫폼 API(`/api/v1`) | `services/operations-backend/src/main/java/com/bifrost/ops/**/controller`, DTO | [api/springboot.md](./api/springboot.md) |
| 내부 운영 API(`/internal/ops`) | `internalops/controller`, `MutationGate`, `PolicyGuard` | [backend-springboot/governance.md](./design/backend-springboot/governance.md) |
| 메타데이터 DB | `services/operations-backend/src/main/resources/db/migration` | [backend-springboot/data-model.md](./design/backend-springboot/data-model.md) |
| Pipeline 생성·상태·삭제 | `PipelineController`, `PipelineService`, `PipelineStatusServiceImpl`, `StrimziKafkaPipelineProvisioner` | [pipeline.md](./design/backend-springboot/pipeline.md), [lifecycle.md](./design/backend-springboot/lifecycle.md), [provisioning.md](./design/backend-springboot/provisioning.md) |
| Monitoring·Incident read | `MonitoringController`, `MonitoringReadService`, `InternalOpsObservabilityController`, `IncidentService` | [monitoring.md](./design/backend-springboot/monitoring.md) |
| FastAPI route | `services/ai-service/app/api/routes_*.py` | [api/fastapi.md](./api/fastapi.md) |
| Agent workflow·state | `services/ai-service/app/workflow`, `app/supervisor`, `app/schemas` | [backend-fastapi/server-design.md](./design/backend-fastapi/server-design.md), [contract/](./design/backend-fastapi/contract/) |
| RCA catalog·tool registry | `services/ai-service/app/catalogs`, `app/tools/registry.py`, `services/ai-service/corpus` | [backend-fastapi/catalog/](./design/backend-fastapi/catalog/), [tool-catalog.md](./design/backend-fastapi/tool-catalog.md) |
| 인프라·배포 | `infra/`, `Jenkinsfile`, `docker-compose.yml`, `services/*/helm` | [design/infra.md](./design/infra.md) |
| 기능 요구사항·시나리오 | 구현과 맞춰 갱신해야 하는 프로젝트 계약 | [spec.md](./spec.md), [scenario.md](./scenario.md) |

## 읽는 순서

1. 이 문서(README) → 2. [spec.md](./spec.md)(FR·임계값) → 3. [scenario.md](./scenario.md) → 4. 설계 [design/](./design/) → 5. API [api/](./api/)

상태값(`pipeline.status`·`connector.state` 등)·임계값(consumer lag 5,000/50,000, error rate 0.5%/2% 등)·이벤트→인시던트 규칙은 [spec.md 부록 B](./spec.md#부록-b--리소스-상태값-정의-및-자동-기준-단일-출처)에 모아 둔다. 단, 실제 현재 동작 여부는 항상 위 코드 기준 정본 맵의 구현을 확인한다.
