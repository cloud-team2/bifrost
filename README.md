# Bifröst

> **DB만 연결하면 데이터 파이프라인은 클릭 몇 번 또는 자연어로, 장애는 AI 에이전트가 진단까지.**
> CDC·EDA 데이터 파이프라인을 셀프서비스로 구축·운영하는 AIOps 플랫폼.

---

## 개요

**Bifröst**는 사용자가 소스·싱크 데이터베이스만 등록하면, UI 클릭 또는 자연어 채팅으로 **CDC(Change Data Capture)** 및 **EDA(Event-Driven Architecture)** 파이프라인을 생성·운영할 수 있는 플랫폼입니다.

파이프라인을 만들면 플랫폼이 Kubernetes 위에 **Kafka(Strimzi)·Debezium·Kafka Connect**를 자동 프로비저닝합니다. 운영 중에는 **AI 에이전트**가 파이프라인을 모니터링하고, 장애를 감지해 **근거 기반 RCA(Root Cause Analysis)**를 수행하며, 자연어로 상태 조회와 조치를 지원합니다(변경 조치는 운영자 승인 후 실행).

## 핵심 기능

- **셀프서비스 파이프라인** — DB 등록 → CDC(source→sink) / EDA(fan-out) 파이프라인을 UI·자연어로 생성. Strimzi Kafka·Debezium·JDBC Sink Connector 자동 구성.
- **AI 장애 대응(RCA)** — 8계층·35 근본원인 카탈로그 + evidence matrix 기반 진단, confidence 스코어링·캘리브레이션, 근거 부족 시 기권(`UNKNOWN_WITH_EVIDENCE_GAP`).
- **자연어 운영** — 채팅으로 파이프라인·메트릭·로그·인시던트를 조회하고 조치. planner가 read-only 도구로 라우팅하며, 변경 조치는 HITL(Human-in-the-Loop) 승인을 거칩니다.
- **실시간 관측성** — 파이프라인 상태·consumer lag·인시던트를 실시간 대시보드로 추적.
- **멀티테넌시** — 프로젝트(테넌트)별 DB·파이프라인·인시던트 격리.

## 아키텍처

```
   Browser
      │ /api
      ▼
┌──────────────┐        /api/v1/agent        ┌──────────────────────┐
│  frontend    │ ─────────────────────────▶  │     ai-service       │
│  React/Vite  │                             │     FastAPI (8082)   │
│  (5173)      │                             │  RCA 에이전트 · LLM   │
└──────┬───────┘                             │  pgvector 지식베이스  │
       │ /api                                └───────────┬──────────┘
       ▼                                                 │ /internal/ops
┌──────────────────────────┐  ◀──────────────────────────┘
│  operations-backend       │
│  Spring Boot (8080)       │  인증 · datasource/pipeline 도메인 · K8s/Kafka 자동화
└──────────────┬────────────┘
               │ provisioning
               ▼
┌──────────────────────────────────────────────────────┐
│  Kubernetes (EKS) · Strimzi Kafka                      │
│  Debezium(source) → Kafka Topic → JDBC Connect(sink)   │
└────────────────────────────────────────────────────────┘
```

- **operations-backend** (Spring Boot 모놀리스) — 플랫폼 API, 인증, datasource·pipeline 도메인, K8s/Kafka 자동화. 에이전트 전용 내부 API `/internal/ops/**`는 외부에 노출하지 않습니다.
- **ai-service** (FastAPI) — RCA 에이전트, LLM 도구 라우팅(planner·retrieval), 인시던트 분석, pgvector 지식베이스, 운영자 피드백 gold set.
- **frontend** (React·Vite·TypeScript) — 콘솔 UI, 파이프라인·인시던트 대시보드, AI 채팅 패널.

> 브라우저/외부 클라이언트는 `/api/**` 또는 FastAPI `/api/v1/agent/**`만 사용하고, FastAPI가 내부 DNS로 Spring Boot `/internal/ops/**`를 호출합니다. Spring Boot는 단일 모놀리스이며, core/orchestrator 분리는 [ADR 0004](docs/adr/0004-monorepo-monolith.md)로 흡수되었습니다.

## 기술 스택

| 영역 | 스택 |
| --- | --- |
| Backend | Java 21, Spring Boot, Gradle |
| AI | Python 3.11, FastAPI, LLM, pgvector |
| Frontend | React, Vite, TypeScript, Tailwind CSS |
| Data plane | Apache Kafka (Strimzi), Debezium, Kafka Connect (JDBC) |
| Datastore | PostgreSQL(metadb), pgvector(agentdb), 테넌트 DB(PostgreSQL/MariaDB) |
| Infra | AWS EKS, ArgoCD(GitOps), Harbor, Jenkins(CI/CD) |
| Observability | Prometheus, Grafana, Loki, Tempo |

## 빠른 시작

### Prerequisites

- JDK 21 (Temurin 권장)
- Node 20+
- Python 3.11+
- Docker + Docker Compose

### 로컬 실행

```bash
# 1. 의존 인프라(Kafka, metadb, 테넌트 DB 시뮬레이터, agentdb 등) 기동
docker compose up -d            # 또는 ./scripts/local-up.sh

# 2. operations-backend (Spring Boot)
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :services:operations-backend:bootRun

# 3. ai-service (FastAPI)
cd services/ai-service
python -m venv .venv && .venv/bin/pip install -e .
.venv/bin/uvicorn app.main:app --reload --port 8082

# 4. frontend (React)
cd services/frontend
npm install && npm run dev
```

접속:

| 대상 | URL |
| --- | --- |
| Frontend | http://localhost:5173 |
| Platform API (Swagger) | http://localhost:8080/swagger-ui.html |
| AI Service (OpenAPI) | http://localhost:8082/docs |
| Kafka UI | http://localhost:8090 |

## 프로젝트 구조

```
bifrost/
├─ docs/                     문서·ADR·명세 (Source of Truth)
├─ services/
│  ├─ operations-backend/    Spring Boot — 플랫폼 API·K8s/Kafka 자동화
│  ├─ ai-service/            FastAPI — RCA 에이전트·LLM
│  └─ frontend/              React UI
├─ infra/                    Terraform·Helm·K8s manifest
├─ scripts/                  로컬/배포 스크립트 (local-up.sh 등)
└─ docker-compose.yml        로컬 의존 인프라
```

## 개발

브랜치·커밋·PR 컨벤션은 [docs/team/git-convention.md](docs/team/git-convention.md)를 따릅니다.

- **브랜치 모델**: `main`(배포 가능) ← `develop`(통합) ← 작업 브랜치 `{type}/#{이슈번호}`
- **작업 흐름**: GitHub Issue 생성 → 브랜치 → PR(대상 `develop`) → **Squash & merge**. `main ← develop`은 릴리스 시점에만.
- **타입**: `feat` · `fix` · `chore` · `docs` · `refactor` (커밋은 `test` 추가)
- **커밋 메시지**: `#{이슈번호} [type] 메시지`
- **보호**: `main`·`develop` 직접 푸시 금지, PR·CI 통과 필수

## 팀

| 이름 | 역할 | 담당 | GitHub | Email |
| --- | --- | --- | --- | --- |
| 이성민 | **PM** | Spring Boot | [@seongmin0229](https://github.com/seongmin0229) | leesung2925@gmail.com |
| 정재환 | **PL** | 인프라 · Frontend · CI/CD | [@hwnnn](https://github.com/hwnnn) | 2020112023@dgu.ac.kr |
| 백강민 | Backend | Spring Boot | [@baekkangmin](https://github.com/baekkangmin) | rkdgur1902@naver.com |
| 권세빈 | AI | FastAPI | [@sebeeeen](https://github.com/sebeeeen) | a856412@gmail.com |
| 김연수 | AI | FastAPI | [@sooooscode](https://github.com/sooooscode) | sooooscode@gmail.com |

## 문서

- [문서 인덱스](docs/README.md)
- [기능 명세 (Spec)](docs/spec.md)
- [ADR (Architecture Decision Records)](docs/adr/)
- [Git 컨벤션](docs/team/git-convention.md)

## License

캡스톤 프로젝트 — 라이선스 미정.
