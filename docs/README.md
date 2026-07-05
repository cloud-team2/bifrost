# Bifrost 문서

Bifrost는 데이터베이스 등록부터 CDC/EDA 파이프라인 생성, 운영 모니터링, evidence 기반 AI 장애 대응까지 다루는 DataOps/AIOps 플랫폼이다.

> 최종 SOT(Source of Truth)는 코드 구현 내용이다. 문서는 현재 구현을 읽기 쉽게 설명하는 보조 레이어이며, 코드와 문서가 다르면 코드를 기준으로 문서를 고친다.

## 읽는 순서

1. [spec.md](./spec.md) — 기능 요구사항, 상태값·임계값 요구사항
2. [scenario.md](./scenario.md) — 시연 critical path와 완료 기준
3. [api/springboot.md](./api/springboot.md), [api/fastapi.md](./api/fastapi.md) — 사람이 읽는 API 카탈로그
4. [design/backend-springboot/overview.md](./design/backend-springboot/overview.md), [design/backend-fastapi/overview.md](./design/backend-fastapi/overview.md), [design/frontend.md](./design/frontend.md), [design/infra.md](./design/infra.md) — 구현 설명
5. [adr/](./adr/) — 방향 전환이 있었던 결정 기록

## 코드 기준

| 항목 | 코드 기준 | 문서 |
| --- | --- | --- |
| Spring 플랫폼 API | `services/operations-backend/src/main/java/com/bifrost/ops/**/controller`, DTO | [api/springboot.md](./api/springboot.md) |
| Spring 내부 운영 API | `internalops/controller`, `governance/MutationGate`, `governance/policy/PolicyGuard` | [backend-springboot/governance.md](./design/backend-springboot/governance.md) |
| metadb schema | `services/operations-backend/src/main/resources/db/migration` | [backend-springboot/data-model.md](./design/backend-springboot/data-model.md) |
| Pipeline 생성·상태·삭제 | `PipelineController`, `PipelineService`, `PipelineStatusServiceImpl`, `StrimziKafkaPipelineProvisioner` | [pipeline.md](./design/backend-springboot/pipeline.md), [lifecycle.md](./design/backend-springboot/lifecycle.md), [provisioning.md](./design/backend-springboot/provisioning.md) |
| Monitoring·Incident | `MonitoringController`, `MonitoringReadService`, `InternalOpsObservabilityController`, `IncidentService` | [monitoring.md](./design/backend-springboot/monitoring.md) |
| FastAPI route·workflow | `services/ai-service/app/api`, `app/workflow`, `app/supervisor`, `app/schemas` | [api/fastapi.md](./api/fastapi.md), [backend-fastapi/server-design.md](./design/backend-fastapi/server-design.md) |
| Agent catalog·tool registry | `services/ai-service/app/catalogs`, `app/tools/registry.py`, `services/ai-service/corpus` | [backend-fastapi/catalog/](./design/backend-fastapi/catalog/), [tool-catalog.md](./design/backend-fastapi/tool-catalog.md) |
| Infra·배포 | `infra/`, `Jenkinsfile`, `docker-compose.yml`, `services/*/helm` | [design/infra.md](./design/infra.md) |

## 책임 경계

- **Spring Boot Operations Backend**: 플랫폼 본체. 인증, workspace, DB registry, pipeline lifecycle, provisioning, monitoring, governance, `/internal/ops` 집행 경계를 담당한다.
- **FastAPI Agent Server**: AI 장애 대응 계층. RCA와 조치 후보 생성을 담당하고, 운영 조회·실행은 Spring `/internal/ops`로 위임한다.
- **Frontend**: 운영 콘솔. Spring `/api/v1`과 FastAPI `/api/v1/agent`를 호출한다. 브라우저가 `/internal/ops/**`를 직접 호출하지 않는다.
- **Infra**: Kafka/Strimzi, Connect image, EKS/GitOps/CI/CD/observability 배포 구조를 관리한다.

## 작성 규칙

- 현재 구현, 계획, 과거 검증 기록을 같은 문단에 섞지 않는다.
- API schema 전체를 문서에 복붙하지 않는다. controller/DTO/OpenAPI를 기준으로 하고, 문서에는 권한·상태코드·주의점만 남긴다.
- 상태값·임계값 요구사항은 [spec.md 부록 B](./spec.md#부록-b--리소스-상태값-정의-및-자동-기준-단일-출처)에 모아 두되, 실제 동작 여부는 코드로 확인한다.
