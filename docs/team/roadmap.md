# 로드맵 — 발표(2026-06-24)까지 주차별 마일스톤

> 기준일 **2026-06-02(화)**, 발표일 **2026-06-24(수)**. 이번주 상세 작업은 [todo.md](./todo.md), 역할 분담은 아래 [역할 개요](#역할-개요), 데모 시나리오는 [../scenario.md](../scenario.md), 기능 SoT는 [../spec.md](../spec.md).

## 역할 개요

> (주)/(부)·주차별 투입 인원은 가용 인원과 MVP 우선순위에 맞춰 매주 배정한다. 트랙 간 계약(인터페이스·스키마)을 먼저 고정하면 Spring Boot 두 트랙, FastAPI 두 트랙이 각각 mock/stub으로 병렬 진행된다. (MCP는 [v1 스코프 제외](../design/backend-fastapi/tool-catalog.md#5-mcp-decision).)

| 담당 영역 | 담당(주) | 담당(부) |
| --- | --- | --- |
| 인프라 & Frontend | 정재환| 김연수|
| Spring Boot — 파이프라인 생성 · Kafka CR · Watcher | 이성민| 백강민|
| Spring Boot — 인증 · 프로젝트 · DB · 운영자 API | 권세빈| 이성민|
| FastAPI Agent — 판단(추론 · 카탈로그) | 김연수| 정재환|
| FastAPI Agent — 런타임 · 연동(오케스트레이션) | 백강민| 권세빈|

## 주차 개요

| 주차 | 기간 | 테마 | 핵심 마일스톤 |
| --- | --- | --- | --- |
| **W1** | 6/2(화)~6/5(금) | 파이프라인 생성 흐름 | mock(+green 시 real) EDA/CDC **생성 E2E**(`creating`→`active`, SSE/event/audit) |
| **W2** | 6/8~6/12 | real 흐름 + 모니터링 + 프론트 연동 | real EDA 토픽 적재·CDC sink 반영, 모니터링/이벤트/인시던트 조회, FE 실연동 |
| **W3** | 6/15~6/19 | **AI 장애대응 + 멀티테넌시 + 배포** (마지막 빌드 주, 발표 전주) | diagnose-only RCA + HITL 조치, 워크스페이스 격리 검증, 앱 배포. **기능 freeze** |
| **W4** | 6/22~6/24(수) | 통합·리허설·**발표** | E2E 통합·버그 수정·시연 리허설 → **6/24 발표** |

> "그 전주"(W3, 6/15~19)까지 기능 구현을 끝내고, 발표 주(W4)는 통합·안정화·리허설만 한다.

---

## W1 (6/2~6/5) — 파이프라인 생성 흐름  *(상세: [todo.md](./todo.md))*

**목표**: workspace 생성 → DB 등록 → CDC readiness → EDA/CDC pipeline 생성 → `creating`→`active` → SSE/event/audit. infra green이면 real까지.

- 정재환: `database` 스키마·`SecretStore` 계약 공유(✓ SecretStore #25), connection-test·DB 등록·schema·cdc-readiness, infra green/red 판정.
- 백강민: Fabric8/Strimzi, KafkaConnector real 구현, Watcher→PipelineStatusService, 금요일 부재분 정재환 인계.
- 권세빈: auth·workspace·pipeline CRUD, mock provisioner(`creating`→`active`), SSE/event/audit.
- 김연수: Agent 로직 검토(루프 가드 중앙 집행·fail 캡·latency 보강).
- 이성민: 금요일 PR/API/E2E 검토, mock/real E2E 스크립트.

**Exit**: mock 기준 EDA·CDC 생성 E2E 성공. green이면 real 토픽 적재·sink 반영 확인.

## W2 (6/8~6/12) — real 흐름 + 모니터링 + 프론트 연동

**목표**: 생성에서 끝나지 않고 **운영 가시성**까지. real 파이프라인 안정화 + 모니터링 탭 + 프론트 실연동.

- 이성민/백강민(SB-Fabric8): real provisioner 안정화(EDA 토픽 적재, CDC sink 반영), 부분 실패 코드, Connect 재구독.
- 권세빈/이성민(SB-코어): 모니터링·이벤트·인시던트 조회 API(FR-006~009·019~021) — metrics/consumer-groups/connectors/sync/messages/connection-guide(stub→실데이터), 이벤트 로그, 인시던트 자동 생성(부록 B.6/B.7), DB metrics·schema·pipelines 실데이터, `/internal/ops` DB·pipeline read tool(계약 #31 기반). 권세빈 account/workspace/members/settings API는 6/8 구현 완료, 이후 Swagger/docs와 FE 연결 정합성 유지.
- 백강민/권세빈(FastAPI-런타임): FastAPI Agent 골격 착수 — run/SSE/State/Tool Client Registry, `/internal/ops` 클라이언트, diagnose-only 흐름 배선.
- 김연수/정재환(FastAPI-판단): catalog(failure type·root cause·evidence matrix) 초안, 프롬프트·output schema, RCA/Verifier 골격.
- 정재환/김연수(인프라·FE): monitoring 스택(Prometheus/Grafana, Loki/Tempo) + Kafka Connect replicas 2 + KafkaConnector/KafkaUser real + FE 실연동(SSE 상태 갱신, 파이프라인/DB 상세 탭, AlertsView). ⚠️ 노드 용량(현재 CPU 요청 ~81%) 부족 → monitoring 올리기 전 노드 확장/인스턴스 상향 선행([../design/infra.md](../design/infra.md#11-클러스터-용량-분석-및-대응안-2026-06-02)).

**Exit**: real EDA/CDC 데이터 흐름 + 파이프라인 상세 모니터링·메시지·구독 가이드 동작. 임계 초과 시 인시던트 자동 생성.

## W3 (6/15~6/19) — AI 장애대응 + 멀티테넌시 + 배포  *(발표 전주, 마지막 빌드)*

**목표**: 차별화 기능(AI 장애대응) 완성 + 멀티테넌시 격리 검증 + 클러스터 배포. 주말 전 **기능 freeze**.

- 김연수/정재환(FastAPI-판단): AI 장애대응 판단 — diagnose-only RCA(evidence 기반), 추천 조치 후보, Verifier 통과분만 Report, 자동 인시던트 리포트, catalog/프롬프트 확정(FR-022/025/026, BifrostAgentPanel 연계).
- 백강민/권세빈(FastAPI-런타임): 조치 실행 경로 — **HITL 승인→실행**(Executor·Policy Guard·Approval gate), 진행 SSE, `/internal/ops` mutation client 연동.
- 권세빈/이성민(SB-코어): `/internal/ops` mutation 경로(approval·idempotency 재검증), incident RCA 기록(PATCH .../rca), approval facade(Spring=SoT) 연계.
- 이성민/백강민(SB-Fabric8): 멀티테넌시 격리 검증(2개 워크스페이스 토픽/ACL 격리), 파이프라인 삭제 시 리소스 정리.
- 정재환/김연수(인프라·FE): 앱 배포(`bifrost-system`: FE·operations-backend·FastAPI) + Argo CD Application 연동(현재 0개) + 앱 이미지 Harbor push·GitOps 매니페스트 정리/역추출.
- 이성민: E2E 통합 시나리오 1차.

**Exit**: scenario.md "데모 합격선(DoD)" 항목 대부분 충족. **금요일(6/19) 기능 freeze** — 이후 버그 수정만.

## W4 (6/22~6/24) — 통합·리허설·발표

**목표**: 신규 구현 없음. 통합 테스트·버그 수정·시연 리허설·발표 준비.

- 전원: E2E 통합 테스트(시나리오 1~4 전체), 버그 수정, 엣지 케이스.
- 이성민: 시연 체크리스트·시연 데이터 seed·리허설 진행, 장애 시 fallback(mock 경로) 준비.
- 발표 자료: 아키텍처·차별점(폐쇄망 제어형 + evidence 기반 AI RCA)·데모 스크립트.
- **6/24(수) 발표.**

**Exit**: 리허설 2회 이상 무중단 통과. 발표 자료·데모 환경 확정.

---

## 발표 준비 체크리스트 (W4)

- [ ] 시연 시나리오 1~4 무중단 리허설 (real 우선, 실패 시 mock fallback)
- [ ] 시연용 seed 데이터(워크스페이스·source/sink DB·샘플 테이블) 준비
- [ ] 멀티테넌시 격리 시연(2 워크스페이스)
- [ ] AI 장애대응 시연(자동 감지 → 진단 → HITL 승인 → 실행 → 검증)
- [ ] 발표 슬라이드: 문제 정의·아키텍처·차별점·데모·로드맵
- [ ] 장애 대비: 네트워크/클러스터 다운 시 대체 데모(녹화 or mock)

## 리스크 & 선행 의존

| 리스크 | 영향 | 대응 |
| --- | --- | --- |
| **클러스터 용량 부족**(3×t3.large, CPU 요청 ~81%) | W2 monitoring·앱 배포 시 `Pending` | 노드 확장/인스턴스 상향 선행([infra §11](../design/infra.md#11-클러스터-용량-분석-및-대응안-2026-06-02)) |
| infra red(real 흐름 불가) | 데모 신뢰도 | mock E2E 경로 항상 유지(W1부터) |
| AI Agent 일정 압박(W3 집중) | 차별 기능 미완 | diagnose-only(원인까지)만이라도 확정, 조치 실행은 stretch |
| 수동 배포·GitOps 미연동 | 재현성 | W3에 manifest 역추출/Argo CD Application 등록 |
| 백강민 금요일 부재 | Fabric8/real provisioner 공백 | 정재환이 인수(todo.md 명시) |
