# Bifrost

> 데이터베이스를 등록하면 CDC/EDA 파이프라인을 구성하고, 운영 중 발생하는 장애 신호를 evidence 기반 AI Agent가 진단하는 DataOps/AIOps 플랫폼.

![Java 21](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)
![Spring Boot 3.3.5](https://img.shields.io/badge/Spring%20Boot-3.3.5-6DB33F?logo=springboot&logoColor=white)
![Python 3.11+](https://img.shields.io/badge/Python-3.11%2B-3776AB?logo=python&logoColor=white)
![FastAPI](https://img.shields.io/badge/FastAPI-0.115%2B-009688?logo=fastapi&logoColor=white)
![React 18](https://img.shields.io/badge/React-18.3-61DAFB?logo=react&logoColor=111111)
![Kafka / Strimzi](https://img.shields.io/badge/Kafka%20%2F%20Strimzi-Connect-231F20?logo=apachekafka&logoColor=white)
![Kubernetes / EKS](https://img.shields.io/badge/Kubernetes-EKS-326CE5?logo=kubernetes&logoColor=white)

Kafka, Debezium, Kafka Connect, Kubernetes를 직접 다루지 않고도 데이터베이스 중심으로 스트리밍 파이프라인을 만들고 운영할 수 있게 하는 것이 목표입니다. 사용자는 Workspace에 Source/Sink DB를 등록하고 테이블 단위로 CDC(direct)·EDA(fan-out) 파이프라인을 생성하며, lag·connector failure·schema mismatch 같은 운영 신호를 콘솔과 AI Agent로 확인합니다.

핵심적으로 구현한 것은 다음 세 가지입니다.

- **Strimzi 기반 connector provisioning** — UI/API 입력을 Fabric8로 `KafkaConnector` CR로 만들고 status watcher로 lifecycle을 추적
- **`/internal/ops` mutation governance** — 운영 변경을 policy·approval·idempotency·audit 경계 하나로 강제
- **evidence 기반 RCA** — root cause catalog + evidence matrix + verifier loop로 LLM 진단의 환각을 억제

## 이 프로젝트에서 다룬 문제

운영 자동화에 LLM을 붙일 때 가장 큰 위험은 **AI가 인프라를 직접, 검증 없이 바꾸는 것**입니다. Bifrost는 이를 구조로 제한합니다.

- **AI는 운영 리소스를 직접 변경하지 않는다.** FastAPI Agent는 read-only tool과 root cause catalog로 RCA를 수행하고 근거(evidence)를 매트릭스로 제시하며, verifier로 자기 검증한 뒤 불확실하면 단정하지 않고 unknown/evidence gap으로 보류하거나 추가 evidence를 요구합니다. RCA·remediation 단계는 *조치 후보*까지만 만듭니다.
- **모든 mutation은 단일 경계를 통과한다.** connector restart/pause/resume, consumer group restart 같은 변경은 Spring `/internal/ops`에서 idempotency·policy·evidence·audit 경계를 거쳐 실행됩니다. AI 서비스는 이 경계 밖으로 운영 리소스를 만질 수 없습니다.
- **위험 조치는 사람이 승인한다(HITL).** `PolicyGuard` 판정에 따라 위험 조치는 approval·change ticket 대상이 되고, platform mutation gate(`/internal/ops`) 기준으로 낮은 위험 조치(예: resume)는 자동 허용됩니다.

이 경계 덕분에 "AI가 장애를 진단한다"와 "AI가 운영을 직접 바꾼다"를 분리할 수 있습니다.

## 핵심 기능

| 영역 | 내용 |
| --- | --- |
| 인증·Workspace | 이메일/비밀번호 로그인, JWT, Workspace 생성·멤버·설정, Workspace 단위 Kafka principal secret reference 조회 |
| Database Registry | PostgreSQL/MariaDB 연결 테스트·등록, schema 탐색, CDC source/sink readiness 점검, DB metrics |
| Pipeline | UI wizard·API로 CDC(direct)·EDA(fan-out) 생성, 테이블 단위 Debezium source / JDBC sink, pause·resume·delete lifecycle |
| Provisioning | Fabric8 + Strimzi `KafkaConnector` CR 생성, status watcher, topic/connector naming 규칙, `timestamptz` custom converter 포함 Connect 이미지 |
| Monitoring | pipeline·connector·task 상태, consumer lag, topic messages, CDC sync 근사치, cluster overview, SSE event stream |
| AI Agent | 자연어 질의, incident RCA workflow, 35종 root cause catalog, evidence matrix, verifier loop, HITL approval·action |
| Governance | `/internal/ops` 경계, mutation allowlist, `PolicyGuard`, approval·change ticket·idempotency·audit |

## 아키텍처

```mermaid
flowchart TB
    User[Browser] --> FE[Frontend<br/>React + Vite]
    FE -->|auth · workspaces · databases · pipelines · monitoring| OPS[Operations Backend<br/>Spring Boot]
    FE -->|agent query · SSE| AI[AI Service<br/>FastAPI Agent]
    AI -->|/internal/ops only| OPS

    subgraph ControlPlane[Control Plane]
      OPS --> META[(metadb<br/>PostgreSQL)]
      AI --> AGENTDB[(agentdb<br/>PostgreSQL + pgvector)]
      AI --> LLM[LLM Provider<br/>optional]
    end

    subgraph Runtime[Data / Runtime Plane]
      OPS --> K8S[Fabric8 / Kubernetes API]
      OPS --> STRIMZI[Strimzi KafkaConnector CR]
      OPS --> CONNECT[Kafka Connect REST]
      OPS --> KAFKA[Kafka AdminClient]
      OPS --> OBS[Prometheus / Loki / Tempo]
      STRIMZI --> CONNECT
      CONNECT --> TOPIC[Kafka Topic]
      SOURCE[(Source DB)] --> CONNECT
      CONNECT --> SINK[(Sink DB · CDC direct)]
      TOPIC --> CONSUMER[External Consumer · EDA fan-out]
    end
```

대표적인 요청 흐름은 세 가지입니다.

1. **파이프라인 생성** — Frontend wizard → Spring platform API → Fabric8가 Strimzi `KafkaConnector` CR 생성 → watcher가 상태 추적
2. **장애 진단** — 사용자 질의/incident → FastAPI Agent가 read-only 조회 + catalog/evidence 기반 RCA → verifier 검증 → 근거와 함께 응답(SSE)
3. **승인된 조치 실행** — Agent가 만든 조치 후보 → `/internal/ops`에서 policy·approval·idempotency·audit → Kafka Connect REST 조치

### 서비스 책임

- **`services/operations-backend`** (Spring Boot) — 플랫폼 본체. 인증, Workspace, DB registry, pipeline lifecycle, provisioning, monitoring, governance, `/internal/ops` 집행 경계.
- **`services/ai-service`** (FastAPI) — 장애대응 AI 계층. Supervisor가 진단 경로(`correlation → planner → retrieval → classifier → rca → verifier → report`)를 제어하고, remediation 요청 시 `remediation → policy_guard → approval_gate`가 덧붙으며, 승인된 조치 실행은 별도 `action_execution` 경로(executor)에서 Spring `/internal/ops`로 위임.
- **`services/frontend`** (React/Vite) — 운영 콘솔. Spring·FastAPI API를 함께 호출, pipeline·database·alerts·cluster·settings 화면과 AI Agent panel 제공.
- **`connect-plugins/timestamptz-converter`** — Debezium PostgreSQL `timestamptz`를 Kafka Connect `Timestamp` 논리 타입으로 변환하는 custom converter.

## 기술 스택

| 구분 | 사용 기술 |
| --- | --- |
| JVM Backend | Java 21, Spring Boot 3.3.5, Spring Security/Data JPA/Kafka, SSE, springdoc-openapi, Gradle multi-project |
| Infra Client | Fabric8 Kubernetes Client 6.13.4, Strimzi API 0.45.0, Kafka Clients 3.7.1, Flyway, PostgreSQL·MariaDB JDBC |
| AI Backend | Python 3.11+, FastAPI, Pydantic v2, httpx, asyncpg, Alembic, OpenAI SDK, OpenTelemetry |
| Frontend | React 18.3, TypeScript 5.7, Vite 6, Tailwind CSS 4, React Router, Recharts, React Markdown, Three.js, Vitest |
| Data Plane | Apache Kafka, Strimzi, Kafka Connect, Debezium PostgreSQL/MariaDB, Confluent JDBC Sink |
| Storage | PostgreSQL metadb, PostgreSQL/pgvector agentdb, PostgreSQL·MariaDB tenant DB |
| CI/CD · Infra | Terraform(EKS), Kubernetes, Jenkins, Kaniko, Harbor, Argo CD GitOps, SealedSecrets(gitops branch) |
| Observability | Prometheus, OpenTelemetry OTLP (Grafana·Loki·Tempo 연동) |

## 디렉터리 구조

```text
bifrost/
├─ services/
│  ├─ operations-backend/      Spring Boot 플랫폼 API · internal ops executor
│  ├─ ai-service/              FastAPI RCA/Agent 서비스
│  └─ frontend/                React/Vite 운영 콘솔
├─ connect-plugins/
│  └─ timestamptz-converter/   Kafka Connect custom converter (Gradle 모듈)
├─ infra/
│  ├─ terraform/               EKS Terraform (environments/dev · modules/eks)
│  ├─ k8s/                     Strimzi · Kafka · Connect · monitoring manifest
│  ├─ cicd/                    Jenkins · Argo CD · Harbor bootstrap
│  ├─ docker/kafka-connect/    Custom Kafka Connect 이미지
│  └─ local/                   로컬 tenant DB · smoke-test helper
├─ docs/                       스펙 · 설계 · API · ADR
├─ docker-compose.yml          로컬 Kafka/metadb/tenantdb/agentdb/WireMock 스택
├─ Jenkinsfile                 CI/CD 파이프라인
└─ Makefile                    Terraform · kubeconfig · Strimzi · compose 단축 명령
```

## 로컬 실행

로컬은 **의존 인프라를 Compose로 띄우고 각 애플리케이션 서비스를 직접 실행**하는 구성입니다. `docker-compose.yml`은 Kafka·Connect·DB·WireMock 등 인프라와 `ai-service`만 정의하며, `operations-backend`와 `frontend`는 아래처럼 별도로 실행합니다.

**Prerequisites** — JDK 21 · Node.js 20+ · Python 3.11+와 `uv` · Docker Compose (선택: Strimzi 대상 `KUBECONFIG`)

> 파이프라인 provisioning의 정본 경로는 Strimzi `KafkaConnector` CR입니다. 로컬 Compose 스택은 개발/스모크 용도이며, CR 생성·watcher까지 보려면 kind/EKS 등 Kubernetes + Strimzi가 필요합니다.

```bash
# 1. 로컬 의존 인프라 기동 (make local-up 과 동일)
docker compose up -d meta-db tenant-postgres tenant-mariadb kafka kafka-connect kafka-ui agentdb wiremock

# 2. Operations Backend (Spring) — dev 프로필, env는 예시값
SPRING_PROFILES_ACTIVE=dev \
META_DB_URL=jdbc:postgresql://localhost:5433/metadb \
KAFKA_BOOTSTRAP=localhost:9094 \
KAFKA_CONNECT_REST_URL=http://localhost:8083 \
INTERNAL_OPS_AUTH_DISABLED=true \
./gradlew :services:operations-backend:bootRun

# 3. AI Service (FastAPI) — 별도 터미널, agentdb는 Compose에서 localhost:5435
(cd services/ai-service && uv sync && \
  AI_DATABASE_URL=postgresql+asyncpg://agent:agent@localhost:5435/agentdb uv run alembic upgrade head)
(cd services/ai-service && \
  AI_SPRING_OPS_BASE_URL=http://localhost:8080 \
  AI_DATABASE_URL=postgresql+asyncpg://agent:agent@localhost:5435/agentdb \
  uv run uvicorn app.main:app --port 8082 --reload)

# 4. Frontend — 별도 터미널
(cd services/frontend && npm install && npm run dev)
```

LLM API key가 없으면 AI Service는 fallback 응답으로 동작합니다. 서비스별 필수 환경변수는 각 서비스 디렉터리의 설정과 `application-dev.yml`을 참고하세요. README에는 민감한 production secret을 포함하지 않습니다.

| 대상 | URL |
| --- | --- |
| Frontend | `http://localhost:5173` |
| Spring Swagger UI | `http://localhost:8080/swagger-ui.html` |
| FastAPI OpenAPI | `http://localhost:8082/docs` |
| Kafka UI | `http://localhost:8090` |
| Kafka Connect REST | `http://localhost:8083` |

## 테스트와 빌드

```bash
./gradlew :services:operations-backend:test            # Spring Boot
./gradlew :connect-plugins:timestamptz-converter:test  # Kafka Connect converter
cd services/ai-service && uv run pytest                # FastAPI
cd services/frontend && npm run test && npm run build  # Frontend
```

주요 테스트 축은 governance gate, Strimzi mapper·provisioner, RCA catalog·evidence·verifier, frontend Agent SSE·pipeline wizard helper입니다.

## 인프라와 배포

- `infra/terraform`이 기존 VPC/Subnet 위에 EKS 클러스터·노드그룹을 구성합니다(`environments/dev`, `modules/eks`).
- Kubernetes runtime은 namespace 경계로 Kafka/Connect, Bifrost services, registry, CI/CD, GitOps, monitoring, metadb·agentdb·tenantdb를 분리합니다.
- Kafka는 Strimzi 기반 KRaft cluster, Kafka Connect는 Debezium·JDBC Sink·custom `timestamptz` converter를 포함한 custom image를 사용합니다.
- Jenkins는 변경된 app service만 Kaniko로 build/push하고 `gitops` 브랜치의 Helm values image tag를 갱신합니다.
- **이 저장소(기본 브랜치)** 에는 EKS Terraform(`infra/terraform`), Strimzi/Kafka/Connect/monitoring manifest, Jenkins·Argo CD·Harbor bootstrap이 있습니다.
- **애플리케이션 배포 정본(app-of-apps, 서비스 Helm chart, SealedSecrets)은 별도 `gitops` 브랜치**에 있으며 Argo CD가 이를 reconcile합니다.
- secret 값·token·production endpoint는 README에 포함하지 않습니다.

## 설계에서 신경 쓴 점

- **v1 Agent는 pipeline 생성을 직접 실행하지 않습니다.** 파이프라인 생성은 UI wizard·Spring platform API가 정본 경로입니다.
- **Agent mutation과 platform 조치의 책임이 다릅니다.** `POST /pipelines/{id}/pause|resume`(platform)와 Agent mutation tool은 경로가 구분되며, Agent 변경은 `/internal/ops`에서 approval·idempotency·audit을 거칩니다.
- Workspace KafkaUser provisioning은 SCRAM-SHA-512 인증 CR을 생성합니다. Kafka ACL authorization은 cluster authorizer 설정에 의존해 현재 코드에서 제외돼 있습니다.
- monitoring poller는 event row를 만들지만 완전 자동 incident 생성·RCA trigger는 제한적이며, RCA run은 주로 Alerts/Agent 경로에서 사용자 시작으로 수행됩니다.
- DB credential은 metadb secret store에 저장될 수 있으나 API 응답·로그에 raw material을 노출하지 않습니다.

## 문서

[문서 인덱스](docs/README.md) · [기능 명세](docs/spec.md) · [Spring 설계](docs/design/backend-springboot/overview.md) · [FastAPI 설계](docs/design/backend-fastapi/overview.md) · [Frontend 설계](docs/design/frontend.md) · [Infra 설계](docs/design/infra.md) · [Spring API](docs/api/springboot.md) · [FastAPI API](docs/api/fastapi.md) · [ADR](docs/adr/) · [Git Convention](docs/team/git-convention.md)

## Team

| 이름 | 역할 | 담당 |
| --- | --- | --- |
| 이성민 | PM | Spring Boot |
| 정재환 | PL | Infra · Frontend · CI/CD |
| 백강민 | Backend | Spring Boot |
| 권세빈 | AI | FastAPI |
| 김연수 | AI | FastAPI |

## License

루트 `LICENSE` 파일이 아직 없습니다. 공개 배포 전 라이선스 확정이 필요합니다.
