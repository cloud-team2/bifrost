# 팀 R&R 분담표

**범위**: MVP (Sprint 1~3 대시보드 핵심 + Sprint 4 채팅 stretch)
**팀**: 5명
**기간**: 10주 (Sprint 0 + 1~5)

---

## 한 페이지 요약

| 사람 | 역할 | 주 담당 |
| --- | --- | --- |
| **A** | DevOps / Infra Lead | EKS, Strimzi, Kafka, CI/CD, 모니터링, 서비스 배포 |
| **B** | Backend Domain Lead | operations-backend — 도메인/DB/Inspector/MetaDB/API ([ADR 0002](../adr/0002-monorepo-monolith.md)로 core+orchestrator 병합) |
| **C** | K8s/Kafka Automation Lead | operations-backend — provisioning/watcher (테넌트 프로비저닝, Connector 자동화) |
| **D** | AI/LLM Engineer | ai-service (FastAPI, Sprint 4부터), Sprint 1~3엔 ops 보조 |
| **E** | Frontend Lead | React, React Flow 캔버스, 위저드, 채팅 UI |

---

## 1. A — DevOps / Infra Lead

### 1.1 핵심 책임

**한 줄**: "다른 사람들이 코드를 짜고 배포할 수 있는 환경을 만들고 유지."

다른 4명이 의존하니까 **Sprint 1에 인프라 골격이 안 서면 전체 일정 밀림**. 가장 먼저 일을 시작하고, 가장 빨리 결과물 나와야 함.

### 1.2 산출물

| 영역 | 산출물 |
| --- | --- |
| 인프라 코드 | `terraform/` (VPC, EKS, ECR, Route53, ACM, IAM) |
| K8s 매니페스트 | `helm/` (Strimzi, Kafka, Connect, MetaDB Postgres) |
| Kafka Connect 이미지 | Debezium PostgreSQL + MariaDB plugin 포함 커스텀 이미지 |
| 서비스 배포 | 3개 서비스(core, orchestrator, ai)의 Helm chart |
| CI/CD | `.github/workflows/` (각 서비스 build → push to ECR → deploy) |
| 모니터링 | Prometheus, Grafana, Loki, Alertmanager 셋업 + 대시보드 |
| 로컬 개발 | `docker-compose.yml` (개발자 PC에서 다 띄울 수 있게) |
| 문서 | 개발자 가이드, 운영 가이드 |

### 1.3 Sprint별 작업

#### Sprint 1 (Week 1-2): 인프라 베이스라인
- [ ] AWS 계정 셋업, Terraform로 VPC + EKS 생성
- [ ] ECR 리포지토리 3개 (core, orchestrator, ai)
- [ ] Helm + ArgoCD 또는 GitHub Actions deploy 파이프라인
- [ ] Strimzi Operator 설치
- [ ] Kafka 클러스터 (3 broker, replication factor 3) 띄우기
- [ ] **Kafka Connect 커스텀 이미지 빌드**: base + Debezium PG + Debezium MariaDB plugin
- [ ] Connect 클러스터 띄우기
- [ ] MetaDB Postgres (EBS PVC 또는 RDS - 캡스톤이면 EBS 권장)
- [ ] 로컬 docker-compose.yml 작성 (Kafka, Postgres, Connect 포함)
- [ ] 다른 팀원들이 개발할 수 있도록 환경 가이드 작성

**완료 기준**:
- 누가 손으로 yaml 작성해서 KafkaConnector 만들면 → PG/MariaDB 둘 다 CDC 동작
- 다른 팀원이 자기 PC에서 `docker-compose up` 한 번으로 개발 환경 띄움

#### Sprint 2 (Week 3-4): 배포 + 모니터링
- [ ] 3개 백엔드 서비스의 Dockerfile, Helm chart
- [ ] GitHub Actions: PR 머지 시 자동 build → push → deploy
- [ ] Frontend 정적 빌드 + S3/CloudFront 또는 nginx Pod 배포
- [ ] ALB Ingress Controller, Route53, ACM 인증서 (HTTPS)
- [ ] Prometheus + Grafana 셋업
- [ ] 기본 대시보드: Kafka 메트릭, Connector status, 노드 자원
- [ ] Loki + Promtail 로그 수집
- [ ] **JWT 서명 키 Secret 관리** (모든 서비스에 마운트)

**완료 기준**:
- main 브랜치 머지 → 자동 배포 → 5분 내 staging 환경에 반영
- Grafana에서 Kafka topic lag, Connector status 등 모니터링 가능

#### Sprint 3 (Week 5-6): 안정화 + 운영
- [ ] Alertmanager 룰: Connector FAILED, Kafka broker 다운, lag 임계치
- [ ] 백업 정책 (MetaDB 자동 백업)
- [ ] 인증서 자동 갱신 (cert-manager)
- [ ] 부하 테스트용 도구 (k6 또는 단순 스크립트)
- [ ] 운영 문서 정리

#### Sprint 4 (Week 7-8): 카오스 테스트
- [ ] Connector 강제 종료 → 자동 복구 확인
- [ ] Kafka broker 하나 죽이기 → 토픽 가용성 확인
- [ ] 네트워크 분할 시뮬레이션
- [ ] Pod OOM 시 복구 확인

#### Sprint 5 (Week 9-10): 시연 환경 준비
- [ ] 시연용 staging 환경 안정화
- [ ] 시연 시나리오 자동 setup 스크립트
- [ ] 발표 데모 환경

### 1.4 다른 사람에게 제공해야 할 것

| 누구에게 | 무엇을 | 언제까지 |
| --- | --- | --- |
| B/C/D | EKS 클러스터 접근 권한 (kubeconfig) | Sprint 1 Week 1 끝 |
| B/C/D | Kafka 접속 정보, MetaDB DSN | Sprint 1 Week 1 끝 |
| 모두 | 로컬 docker-compose | Sprint 1 Week 1 끝 |
| 모두 | ECR push 권한 | Sprint 1 Week 2 |
| B/C/D | Helm chart 템플릿 | Sprint 2 Week 3 |
| E | Frontend 배포 파이프라인 | Sprint 2 Week 3 |

---

## 2. B — Backend Domain Lead (core-service)

### 2.1 핵심 책임

**한 줄**: "사용자가 보는 API의 90%를 책임지는 사람. 도메인 모델의 일관성도 책임."

전체 시스템에서 가장 무거운 역할. core가 거의 모든 기능의 조율자.

### 2.2 산출물

| 영역 | 산출물 |
| --- | --- |
| Spring Boot 프로젝트 | `core-service/` (Gradle 멀티 모듈) |
| 인증/보안 | JWT 발급/검증, Spring Security 설정 |
| 도메인 서비스 | TenantService, UserService, DatasourceService, PipelineService 등 |
| Inspector 모듈 | DatabaseInspector 인터페이스 + PostgresInspector + MariaDBInspector |
| 영속성 | JPA Entity, Repository, Flyway 마이그레이션 |
| REST API | 13개+ 엔드포인트 |
| WebSocket | 실시간 이벤트 push |
| Kafka 통합 | orchestrator의 이벤트 소비 |
| 클라이언트 | orchestrator/ai-service 호출 클라이언트 |

### 2.3 모듈 구조 (제안)

```
core-service/
├─ api/                  ← REST Controller, WebSocket Handler, DTO
├─ core/                 ← 도메인 서비스 (TenantService, PipelineService, ...)
├─ inspector/            ← DB 추상화
│   ├─ DatabaseInspector.java (interface)
│   ├─ PostgresInspector.java
│   └─ MariaDBInspector.java
├─ persistence/          ← JPA, Flyway
├─ security/             ← JWT, Spring Security
├─ events/               ← Kafka consumer (status 변경 이벤트 수신)
└─ client/               ← OrchestratorClient, AiServiceClient
```

### 2.4 Sprint별 작업

#### Sprint 1 (Week 1-2): 골격
- [ ] Gradle 프로젝트 셋업 (멀티 모듈 또는 단일)
- [ ] Spring Boot 3.3 + Java 21
- [ ] Flyway 셋업, 초기 스키마 마이그레이션 (tenants, users)
- [ ] JPA Entity 클래스 (Tenant, User)
- [ ] Spring Security + JWT 발급/검증
- [ ] `POST /api/auth/register`, `POST /api/auth/login`, `GET /api/auth/me`
- [ ] **회원가입 시 orchestrator에 프로비저닝 호출** (C와 인터페이스 합의 필요)
- [ ] **`DatabaseInspector` 인터페이스 + DTO 정의 PR** ← C가 의존하니까 빨리
- [ ] 헬스체크 엔드포인트

**완료 기준**:
- 회원가입하면 MetaDB에 tenant/user 저장 + K8s에 namespace 등 생성됨
- JWT로 로그인 가능

#### Sprint 2 (Week 3-4): Datasource + Inspector
- [ ] datasources 테이블 Flyway
- [ ] Datasource JPA Entity, Repository
- [ ] DatasourceService (CRUD)
- [ ] `POST/GET/DELETE /api/datasources`, `GET /api/datasources/{id}/tables`
- [ ] **K8s Secret 생성 코드** (사용자 DB credentials) — fabric8 client
  - 또는 orchestrator API로 위임할지 C와 결정
- [ ] **PostgresInspector 완성**:
  - testConnection, listTables, checkCDCReadiness
  - CDC readiness 5개 항목 검사
- [ ] **MariaDBInspector 완성**:
  - 동일한 인터페이스, MariaDB 특화 검사
- [ ] 백그라운드 검사 (Spring `@Async` 또는 별도 큐)
- [ ] WebSocket 골격, DATASOURCE_INSPECTED 이벤트 푸시

**완료 기준**:
- PG와 MariaDB 둘 다 등록 → CDC readiness 검사 → 결과가 응답에 + WebSocket으로 도착

#### Sprint 3 (Week 5-6): Pipeline + 실시간
- [ ] pipelines, discovered_services 테이블
- [ ] Pipeline Entity, Service
- [ ] `POST/GET/DELETE /api/pipelines`
- [ ] **Inspector.generateConnectorConfig() 완성** (PG + MariaDB Debezium config 생성)
- [ ] orchestrator 호출 (`POST /internal/pipelines`)
- [ ] Kafka consumer 구현:
  - `platform.internal.connector-status` 구독 → Pipeline status 업데이트
  - `platform.internal.service-discovered` 구독 → discovered_services 저장
- [ ] WebSocket 이벤트:
  - PIPELINE_STATUS_CHANGED
  - SERVICE_DISCOVERED
  - SERVICE_LAG_UPDATED
- [ ] `GET /api/services`, `PATCH /api/services/{id}`
- [ ] Consumer 연결 정보 생성 (`consumerConnectionInfo`):
  - KafkaUser Secret B에서 비밀번호 읽기
  - Java/Python 코드 스니펫 생성

**완료 기준**:
- 대시보드만으로 Pipeline 생성 → status 변경 실시간 반영 → Consumer 연결 정보 제공
- 실제로 Consumer App이 연결하면 캔버스에 Service 노드 자동 등장

#### Sprint 4 (Week 7-8): 채팅 forwarding
- [ ] chat_sessions, chat_messages 테이블
- [ ] `GET/POST /api/chat/sessions`, `GET /api/chat/sessions/{id}/messages`
- [ ] `POST /api/chat/sessions/{id}/messages` (SSE) — ai-service에 위임
- [ ] ai-service의 SSE 응답을 그대로 Frontend로 forwarding
- [ ] ai-service 도구 호출용 인증 처리 (user JWT를 ai-service가 가지고 다시 core 호출)

#### Sprint 5 (Week 9-10): 안정화
- [ ] 버그 수정
- [ ] E2E 테스트
- [ ] 성능 튜닝

### 2.5 다른 사람에게 제공해야 할 것

| 누구에게 | 무엇을 | 언제까지 |
| --- | --- | --- |
| C | `DatabaseInspector` 인터페이스 + DebeziumConnectorConfig DTO | Sprint 1 Week 1 끝 |
| C | TenantProvisioner 호출 인터페이스 합의 | Sprint 1 Week 1 |
| E | REST API OpenAPI 스펙 (자동 생성) | Sprint 1부터 점진적 |
| E | WebSocket 이벤트 스키마 | Sprint 2 Week 3 |
| D | core API 명세 (ai-service의 도구 호출 대상) | Sprint 3 끝 |

---

## 3. C — K8s/Kafka Automation Lead (orchestrator-service)

### 3.1 핵심 책임

**한 줄**: "K8s와 Kafka에서 일어나는 모든 자동화. 멀티 테넌시의 실질적 구현자."

### 3.2 산출물

| 영역 | 산출물 |
| --- | --- |
| Spring Boot 프로젝트 | `orchestrator-service/` |
| K8s 클라이언트 | fabric8 wrapper |
| TenantProvisioner | 회원가입 시 자동 생성 (Namespace, KafkaUser, Quota, NetPol) |
| ConnectorManager | KafkaConnector CRD 생성/조회/삭제 |
| TopicManager | KafkaTopic CRD 생성 |
| Watcher | KafkaConnector status watch → Kafka 이벤트 발행 |
| ConsumerGroupDiscoverer | Kafka Admin API 폴링 → 이벤트 발행 |
| 내부 API | 5개 엔드포인트 |

### 3.3 모듈 구조

```
orchestrator-service/
├─ api/                       ← 내부 REST Controller
├─ kafka/
│   ├─ tenant/
│   │   └─ TenantProvisioner.java
│   ├─ connector/
│   │   └─ ConnectorManager.java
│   ├─ topic/
│   │   └─ TopicManager.java
│   └─ admin/
│       └─ ConsumerGroupDiscoverer.java
├─ k8s/                       ← fabric8 wrapper
├─ events/                    ← Kafka producer (status 변경 이벤트)
└─ watch/                     ← K8s watch
```

### 3.4 Sprint별 작업

#### Sprint 1 (Week 1-2): 골격 + 프로비저닝
- [ ] Gradle 프로젝트 셋업
- [ ] fabric8 kubernetes-client 의존성
- [ ] Strimzi CRD model 의존성 (`io.strimzi:api`)
- [ ] **수동으로 KafkaConnector yaml 작성해서 PG/MariaDB 둘 다 CDC 검증** (A와 협업)
- [ ] **TenantProvisioner 구현**:
  - Namespace 생성
  - KafkaUser CRD 생성 (SCRAM + ACL: prefix tenant-{id}.)
  - ResourceQuota
  - NetworkPolicy (cross-tenant 차단)
- [ ] `POST /internal/tenants/provision`
- [ ] `DELETE /internal/tenants/{tenantId}` (Namespace 삭제로 cascade)
- [ ] Idempotency (이미 있으면 OK)

**완료 기준**:
- 회원가입 시 자동으로 위 5개 K8s 리소스가 생성됨
- 두 번째 회원가입 시 다른 tenant의 리소스는 별도로 격리됨

#### Sprint 2 (Week 3-4): Connector 자동화
- [ ] **ConnectorManager 구현**:
  - KafkaConnector CRD 생성 (Debezium config 받아서)
  - KafkaConnector CRD 조회 (status 포함)
  - KafkaConnector CRD 삭제
- [ ] **TopicManager 구현**:
  - KafkaTopic CRD 생성 (partition, replication factor 등)
  - 토픽 prefix 검증 (`tenant-{id}.`로 시작하는지)
- [ ] `POST /internal/pipelines`, `GET /internal/pipelines/{id}/status`, `DELETE /internal/pipelines/{id}`
- [ ] core 호출 시 인증 (X-Tenant-Id 헤더 또는 그냥 body)

**완료 기준**:
- core에서 POST /internal/pipelines 호출하면 → KafkaTopic + KafkaConnector 둘 다 생성 → 잠시 후 RUNNING 됨

#### Sprint 3 (Week 5-6): 실시간 이벤트
- [ ] **Connector Status Watcher**:
  - 모든 namespace의 KafkaConnector watch (K8s informer)
  - status 변경 감지 시 Kafka 이벤트 발행
- [ ] **Consumer Group Discoverer**:
  - Kafka Admin API로 30초마다 폴링
  - tenant prefix로 식별
  - 새 그룹 → SERVICE_DISCOVERED
  - lag 변화 → SERVICE_LAG_UPDATED
  - 5분 무활동 → SERVICE_DISCONNECTED
- [ ] Kafka producer: `platform.internal.*` 토픽에 이벤트 발행
- [ ] 이벤트 스키마는 B와 합의 (JSON, 필수 필드)

**완료 기준**:
- Connector가 RUNNING이 되는 순간 Kafka 이벤트가 발행되고, core가 받아서 DB 업데이트 + WebSocket
- 새 Consumer App이 Kafka에 붙으면 30초 내 SERVICE_DISCOVERED 이벤트

#### Sprint 4 (Week 7-8): 안정화
- [ ] 에러 처리 강화 (K8s API 일시적 실패, 재시도)
- [ ] 부분 실패 복구 (Namespace는 만들었는데 KafkaUser가 실패 등)
- [ ] 운영 로그 강화

#### Sprint 5: 시연 준비
- [ ] 디버깅, 시연 시나리오 검증

### 3.5 다른 사람에게 제공해야 할 것

| 누구에게 | 무엇을 | 언제까지 |
| --- | --- | --- |
| B | TenantProvisioner API 명세 | Sprint 1 Week 1 |
| B | ConnectorManager API 명세 | Sprint 2 Week 3 |
| B | Kafka 이벤트 스키마 | Sprint 3 Week 5 |
| A | 운영 메트릭 (Connector 수, 이벤트 발행 수 등) | Sprint 3 |

---

## 4. D — AI/LLM Engineer

### 4.1 핵심 책임

**한 줄**: "ai-service의 모든 것. Sprint 1~3엔 도움 + 준비, Sprint 4에 본격 가동."

### 4.2 Sprint 1~3에 무엇을 하는가 (애매한 시기 대응)

ai-service는 Sprint 4부터 본격이지만 D가 1~3주 동안 놀면 안 됨. 선택지:

#### 옵션 A: core 보조 (B를 도움)
- B의 작업 중 일부 인계 (예: Inspector 한쪽, 일부 API)
- 가장 실용적

#### 옵션 B: ai-service 사전 작업
- LangChain4j PoC, OpenAI 도구 호출 실험
- MCP 도구 인터페이스 정의 (Phase 2 대비)
- 채팅 UX 디자인 (E와 협업)
- Prompt 엔지니어링 사전 작업

#### 옵션 C: 인프라 보조 (A를 도움)
- A가 무거우면 일부 인수

**추천**: **옵션 A + 옵션 B 섞기**. Sprint 1엔 ai-service 셋업 + B 보조, Sprint 2~3엔 도구 정의 사전 작업 + 백엔드 일부 인계.

### 4.3 산출물

| 영역 | 산출물 |
| --- | --- |
| Spring Boot 프로젝트 | `ai-service/` |
| LangChain4j 통합 | OpenAI 호출 wrapper |
| 도구 정의 | 6개 도구 (datasource 조회, pipeline 생성 등) |
| 채팅 핸들러 | SSE 스트리밍 |
| Prompt | 시스템 프롬프트, few-shot 예시 |

### 4.4 Sprint별 작업

#### Sprint 1 (Week 1-2): 사전 작업
- [ ] OpenAI API 키 발급, 사용량 제한 셋업
- [ ] LangChain4j 0.34 PoC: 간단한 도구 호출 테스트
- [ ] ai-service 프로젝트 골격 (Spring Boot 스켈레톤만)
- [ ] core의 Inspector 또는 API 일부 협업 (B의 부담 분담)

#### Sprint 2 (Week 3-4): 도구 정의 + 사전 설계
- [ ] 6개 도구의 OpenAI function schema 정의:
  - `list_datasources`, `get_datasource_tables`, `inspect_datasource_readiness`
  - `create_cdc_pipeline`, `list_pipelines`, `get_pipeline_detail`
- [ ] 도구가 호출할 core API 동작 검증 (B와 함께)
- [ ] 시스템 프롬프트 초안 작성
- [ ] 채팅 UX 디자인 (E와 함께)

#### Sprint 3 (Week 5-6): ai-service 본격 시작
- [ ] ai-service Spring Boot 기본 구조
- [ ] OpenAI 호출 wrapper (LangChain4j)
- [ ] 도구 호출 흐름 (LLM이 도구 결정 → core API 호출 → 결과 LLM에 다시)
- [ ] SSE 스트리밍 응답
- [ ] 도구 호출 결과를 SSE 이벤트로 변환 (사용자에게 진행 상황 표시)
- [ ] core가 호출할 내부 API 정의: `POST /internal/ai/chat`

#### Sprint 4 (Week 7-8): 마무리 + 통합
- [ ] core와 통합 테스트
- [ ] Prompt 튜닝 (한국어 처리 등)
- [ ] 에러 처리 (LLM 환각, 도구 호출 실패)
- [ ] 채팅 히스토리 컨텍스트 관리

#### Sprint 5: 데모 시나리오 다듬기
- [ ] 시연용 대화 시나리오
- [ ] 실패 케이스 fallback

### 4.5 다른 사람에게 제공해야 할 것

| 누구에게 | 무엇을 | 언제까지 |
| --- | --- | --- |
| E | 채팅 SSE 이벤트 스키마 | Sprint 3 Week 5 |
| B | ai-service의 `POST /internal/ai/chat` 명세 | Sprint 3 Week 5 |

---

## 5. E — Frontend Lead

### 5.1 핵심 책임

**한 줄**: "사용자가 실제로 보는 모든 것. UX 일관성, 실시간성, 시각화의 책임."

### 5.2 산출물

| 영역 | 산출물 |
| --- | --- |
| React 프로젝트 | `frontend/` (Vite + TS 5 + React 18) |
| 라우팅 | React Router |
| 상태 관리 | TanStack Query (서버 상태), Zustand (UI 상태) |
| 캔버스 | React Flow 노드/엣지 컴포넌트 |
| 위저드 | Datasource 등록, Pipeline 생성 |
| 패널 | Datasource/Pipeline/Service 상세 |
| 채팅 UI | Sprint 4 |
| WebSocket | 실시간 갱신 hook |
| API 클라이언트 | OpenAPI 생성 또는 수동 |
| 스타일 | Tailwind, 디자인 시스템 |

### 5.3 컴포넌트 구조

```
frontend/src/
├─ pages/
│   ├─ LoginPage.tsx
│   ├─ RegisterPage.tsx
│   └─ MainPage.tsx
├─ components/
│   ├─ canvas/
│   │   ├─ Canvas.tsx
│   │   ├─ nodes/
│   │   │   ├─ DatasourceNode.tsx
│   │   │   ├─ PipelineNode.tsx
│   │   │   └─ ServiceNode.tsx
│   │   └─ edges/
│   ├─ wizards/
│   │   ├─ DatasourceWizard.tsx
│   │   └─ PipelineWizard.tsx
│   ├─ panels/
│   │   ├─ DatasourceDetailPanel.tsx
│   │   ├─ PipelineDetailPanel.tsx
│   │   └─ ServiceDetailPanel.tsx
│   └─ chat/                  ← Sprint 4
│       ├─ ChatPanel.tsx
│       └─ MessageBubble.tsx
├─ api/
│   ├─ client.ts              ← axios + 인터셉터 (JWT)
│   ├─ auth.ts
│   ├─ datasources.ts
│   ├─ pipelines.ts
│   └─ services.ts
├─ hooks/
│   ├─ useAuth.ts
│   ├─ useWebSocket.ts
│   ├─ useCanvasData.ts
│   └─ useChat.ts (Sprint 4)
├─ store/
│   └─ canvasStore.ts (Zustand)
└─ styles/
```

### 5.4 Sprint별 작업

#### Sprint 1 (Week 1-2): 골격 + 로그인
- [ ] Vite + React 18 + TS 셋업
- [ ] Tailwind, 디자인 시스템 토큰
- [ ] 라우팅 (Login, Register, Main)
- [ ] 로그인/회원가입 화면 (mock API)
- [ ] JWT 저장 + axios 인터셉터
- [ ] 메인 화면 골격 (헤더, 사이드바, 본문 영역)
- [ ] React Flow 빈 캔버스
- [ ] B와 OpenAPI 자동 생성 셋업 (SpringDoc → TS client)

**완료 기준**:
- 로그인 화면 보임, mock으로 메인 화면 진입 가능

#### Sprint 2 (Week 3-4): Datasource
- [ ] 로그인/회원가입 실제 API 연동
- [ ] **Datasource 위저드**:
  - Step 1: 이름 + dbType (PostgreSQL/MariaDB 토글)
  - Step 2: 연결 정보 (host, port, dbName, username, password)
  - Step 3: 등록 후 CDC readiness 결과 표시
  - RED인 경우 항목별 remediation 표시
- [ ] 캔버스에 Datasource 노드 표시
- [ ] Datasource 상세 패널
- [ ] **WebSocket 연결** (DATASOURCE_INSPECTED 이벤트로 캔버스 갱신)

**완료 기준**:
- PG 또는 MariaDB Datasource 등록 → 노드 등장 → readiness 결과 실시간 표시

#### Sprint 3 (Week 5-6): Pipeline + 실시간 (가장 무거움)
- [ ] **Pipeline 위저드**:
  - Step 1: 이름
  - Step 2: Source Datasource 선택 (cdcReadiness=GREEN만)
  - Step 3: 테이블 선택 (스키마-테이블 트리 체크박스)
  - Step 4: 생성
- [ ] 캔버스에 Pipeline 표시 (노드 또는 엣지)
- [ ] **Pipeline 상세 패널**:
  - 상태, 토픽 이름
  - Consumer 연결 정보 (탭으로 Java/Python 코드 스니펫)
  - 복사 버튼
- [ ] Service 노드 자동 등장 (WebSocket SERVICE_DISCOVERED)
- [ ] Service 상세 패널 (라벨/설명 편집)
- [ ] 캔버스 자동 레이아웃 (dagre 또는 자체 알고리즘)
- [ ] 노드 간 연결선 그리기 (Datasource → Pipeline → Service)

**완료 기준**:
- 처음부터 끝까지 캔버스만으로 시연 가능

#### Sprint 4 (Week 7-8): 채팅
- [ ] **채팅 패널 UI**:
  - 좌측 토글, 슬라이딩
  - 메시지 입력
  - 메시지 버블 (USER/ASSISTANT)
- [ ] SSE 응답 처리 (스트리밍 텍스트)
- [ ] 도구 호출 진행 상황 시각화:
  - "Datasource를 조회하는 중..." (thinking)
  - "✓ list_datasources 완료" (tool_result)
- [ ] 채팅 히스토리 (세션 목록)
- [ ] 채팅으로 Pipeline 생성 시 캔버스 자동 갱신 (WebSocket 연동)

#### Sprint 5: 다듬기
- [ ] 디자인 polish
- [ ] 빈 상태, 에러 상태 화면
- [ ] 로딩 상태
- [ ] 접근성

### 5.5 다른 사람에게 제공해야 할 것

| 누구에게 | 무엇을 | 언제까지 |
| --- | --- | --- |
| 모두 | UI 와이어프레임/디자인 | Sprint 1 Week 2 |
| B | API 요청 패턴 검증 (실제 어떻게 쓸지) | 지속적 |
| 모두 | UX 피드백 모음 (불편한 점 보고) | 지속적 |

---

## 6. 의존성 매트릭스

누가 누구에게 의존하는가:

```
A (Infra) ─── 모든 사람 의존: EKS, Kafka, 배포, 로컬 환경
              ↓
B (core) ←── E (Frontend가 API 호출)
              ↑
              D (ai-service가 도구로 core API 호출)
              ↑
C (orchestrator) ←── B (core가 orchestrator 호출)
              ↓
              Kafka 이벤트 발행 → B가 구독

D (ai-service) ←── B (core가 채팅 SSE 위임)
              ↑
              E (채팅 UI가 SSE 받음, core 경유)
```

### 시작 순서

**Week 1 Day 1-2**:
- A: AWS 셋업 시작
- B: 도메인 모델 정의, Inspector 인터페이스 정의
- C: fabric8 PoC
- D: OpenAI PoC, 사전 학습
- E: 디자인 + 프로젝트 셋업

**Week 1 Day 3-4 (블록 발생 지점)**:
- A의 EKS가 안 되어있으면 → C는 로컬 K8s (kind/minikube)로 진행
- B의 Inspector 인터페이스가 안 나오면 → C는 mock 인터페이스 만들어서 진행
- B의 API 명세가 안 나오면 → E는 mock client로 진행

**해결책**: Sprint 0에 **mock 정책** 합의. 누가 누구를 mock해도 되는지 명확히.

---

## 7. 주간 동기화

### 매일 (10분 스탠드업)
- 어제 한 거
- 오늘 할 거
- 블록된 거 (누구를 기다리는가)

### 매주 (1시간 sprint review)
- 마일스톤 진행도
- 다음 주 계획
- 인터페이스 변경사항 공유

### 인터페이스 변경 시
- API 변경 → OpenAPI 갱신 → PR 머지 → 다른 팀원 notified
- 이벤트 스키마 변경 → 문서 갱신 → 영향받는 사람과 직접 확인
- DTO 변경 → common-dto 모듈 (있다면) → SemVer 따라 변경

---

## 8. 부담 균형 분석

부담을 점수화하면 (대략):

| 사람 | Sprint 1 | Sprint 2 | Sprint 3 | Sprint 4 | Sprint 5 |
| --- | --- | --- | --- | --- | --- |
| A | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐ | ⭐⭐ |
| B | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐ |
| C | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐ | ⭐⭐ |
| D | ⭐⭐ | ⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ |
| E | ⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐ |

**잠재적 문제**:
- **A**: Sprint 1에 과부하 (인프라 전부 깔아야 함). 도와줄 수 있는 사람 있나 검토.
- **B**: Sprint 2-3 연속 과부하 (Inspector + Pipeline 둘 다). D가 보조 가능?
- **D**: Sprint 1-2에 한가함. 명시적으로 어디 도울지 정해야.

### 조정 권장사항

**Sprint 1 D의 역할 명확화**:
- core의 일부 모듈 (예: Inspector 한쪽) 인계
- 또는 인프라 보조 (A가 Helm chart 만들 때 옆에서)

**Sprint 2-3 B의 부담 분산**:
- D가 Inspector의 한쪽 (예: MariaDBInspector) 담당 가능
- 또는 일부 도메인 서비스 (예: DiscoveredServiceService) D가

이건 팀 구성원 강점/약점 보고 실제 분배.

---

## 9. 위험 요소 + 대응

| 위험 | 영향 | 대응 |
| --- | --- | --- |
| A의 EKS 셋업 지연 | 전 팀 막힘 | 로컬 docker-compose로 우회. 또는 minikube. |
| C의 fabric8 학습 곡선 | Sprint 2 일정 영향 | Sprint 0에 PoC 끝내기. 막히면 B/D 지원. |
| B의 부담 과중 | 도메인 모델 일관성 깨짐 | Sprint 2에 D가 명시적으로 도움. 코드 리뷰 강화. |
| LLM이 도구 호출 안정성 | Sprint 4 시연 실패 | 대시보드 모드는 이미 됨 → 자연어는 stretch로 명시 |
| WebSocket 안정성 | 실시간 갱신 실패 | 폴백: 5초 polling. 시연용 안전장치. |
| Kafka 이벤트 손실 | core가 status 못 받음 | At-least-once 보장 (consumer commit 신중히) |

---

## 10. Sprint 0 (Week 0)에 정해야 할 것

이거 정리 안 하면 Sprint 1에 다 충돌:

1. ✅ 서비스 경계 (4개 서비스, 본 문서)
2. ✅ R&R (본 문서)
3. ⏳ 인증 방식 (외부 JWT만, 내부는 NetworkPolicy로)
4. ⏳ 공통 DTO 모듈 여부
5. ⏳ 에러 응답 표준
6. ⏳ 로깅 포맷 (JSON, 필수 필드)
7. ⏳ OpenAPI 자동 생성 셋업
8. ⏳ Git 브랜치 전략
9. ⏳ 코드 리뷰 정책
10. ⏳ 회의 주기

---

## 11. 완료 정의 (다시 한 번)

Sprint 3 끝에 이거 다 되면 MVP 완성 (자연어 없이도 시연 가능):

- [ ] 회원가입 → 자동 K8s 프로비저닝
- [ ] PG와 MariaDB 둘 다 등록 → CDC readiness 검사
- [ ] 캔버스에서 Pipeline 생성 → KafkaConnector 자동 생성 → 데이터 흐름
- [ ] Consumer 연결 시 Service 노드 자동 등장
- [ ] 멀티 테넌트 격리 검증 (2개 계정 동시 사용)
- [ ] Pipeline 삭제 시 리소스 정리

Sprint 4 끝에 추가로:
- [ ] 채팅으로 같은 흐름 가능

---

이 R&R은 살아있는 문서. 실제 작업하면서 부담 불균형 보이면 조정.
