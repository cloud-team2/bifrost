# TODO - 2026-06-08 (Latest)

## 현재 기준

- `workspace -> DB 등록 -> pipeline 생성` 흐름은 **디버깅 완료**로 본다.
- 이번 라운드의 메인 목표는 **모니터링 데이터 -> agent -> 운영 화면 연결**과 **wireframe 기준 settings/account Spring Boot 잔여 gap 정리**이다.
- 오늘부터는 파이프라인 생성 신규 기능보다 다음 3축을 우선한다.
  - Spring Boot monitoring / incidents public API
  - Spring `/internal/ops` + FastAPI agent 실제 연동
  - Settings / Login wireframe 기준 계정·설정 API

## 이번 라운드 핵심 원칙

- 기존 public API(`workspace`, `database`, `pipeline`)는 가능하면 깨지지 않게 유지한다.
- 새 API가 아직 완전하지 않아도, **response shape를 먼저 고정**하고 내부 구현은 stub으로 시작할 수 있다.
- Spring public API와 Spring internalops API는 분리해서 본다.
  - public API: frontend 운영/설정 화면용
  - internalops: FastAPI agent read tool / control plane용
- frontend는 mock 제거가 목표지만, backend가 늦으면 fixture adapter로 중간 연결해도 된다.
- 각 담당자는 아래 TODO를 **GitHub issue로 바로 쪼갤 수 있는 단위**로 관리한다.

## 현재 공백 요약

### Spring Boot

- 이미 되는 것
  - auth / account me / workspace / members / settings / database / pipeline / workspace SSE
  - pipeline detail 관련 일부 read API(topic, consumer group, messages, metrics, sync)

- 아직 비어 있는 것
  - `cluster`, `resource-events`, `incidents` 같은 운영/모니터링 public API
  - `/internal/ops` 공통 envelope와 agent read tool API
  - account/settings API의 frontend 최종 연결과 문서/Swagger 정합성 유지

### FastAPI

- 이미 되는 것
  - `/api/v1/health`, `/ready`, `/version`, `/capabilities`
  - `/api/v1/agent/runs`, `/runs/{id}`, `/runs/{id}/events`
  - router / planner / retrieval / verifier / report 스캐폴드

- 아직 비어 있는 것
  - supervisor 기반 실제 workflow 집행
  - classifier / rca / remediation 단계 실제 연결
  - approvals / actions / reports / catalogs / admin route surface
  - Spring internalops와 실제 맞물리는 tool registry

### Frontend

- 이미 되는 것
  - login / workspace / database / pipeline 화면 기본 연동
  - workspace SSE(`pipeline_status_changed`)

- 아직 비어 있는 것
  - `OperatorCluster`, `OperatorResourceEvents`, `Alerts` 실 API 연동
  - `BifrostAgent`, `OperatorAgentPanel`, `DevAIChatPanel`의 실제 FastAPI run/SSE 연동
  - `Settings` 화면의 서버 연동

## 역할 분담

| 담당 | 메인 축 | 핵심 책임 |
| --- | --- | --- |
| 정재환 | 인프라 + 프론트엔드 | 운영 화면 API 연동, AI 패널 FastAPI/SSE 연동, 배포 환경값 정리 |
| 이성민 | Spring Boot 모니터링 | cluster / resource-events / incidents public API |
| 백강민 | Spring internalops | FastAPI가 읽는 `/internal/ops` read API, 공통 envelope, 계약 문서 |
| 김연수 | FastAPI agent | supervisor 연결, monitoring read tool 연동, incident_analysis 흐름, SSE event 보강 |
| 권세빈 | Spring Boot 설정/계정 | wireframe 기준 `Settings` / `Login` 서버 API 구현 |

---

## 정재환 - 인프라 + 프론트엔드

참고 화면:
- [OperatorCluster.tsx](../../services/frontend/src/pages/op/OperatorCluster.tsx)
- [OperatorResourceEvents.tsx](../../services/frontend/src/pages/op/OperatorResourceEvents.tsx)
- [Alerts.tsx](../../services/frontend/src/pages/Alerts.tsx)
- [BifrostAgent.tsx](../../services/frontend/src/pages/ai/BifrostAgent.tsx)
- [OperatorAgentPanel.tsx](../../services/frontend/src/pages/ai/OperatorAgentPanel.tsx)
- [DevAIChatPanel.tsx](../../services/frontend/src/pages/ai/DevAIChatPanel.tsx)

### 오늘/이번 라운드 TODO

- [ ] `services/frontend/src/lib/api.ts`를 기준으로 platform API와 agent API를 논리적으로 분리한다.
- [ ] `OperatorCluster`, `OperatorResourceEvents`를 실제 API에 붙인다.
- [ ] `Alerts`를 incidents API에 붙인다.
- [ ] AI 패널 중 최소 1개에서 `POST /api/v1/agent/runs` + `GET /api/v1/agent/runs/{run_id}/events`가 실제로 동작하게 만든다.
- [ ] workspace SSE와 agent SSE를 서로 분리된 흐름으로 관리한다.
- [ ] backend가 덜 붙은 화면은 fixture adapter로 응답 shape만 먼저 고정한다.
- [ ] 프론트에서 필요한 env 목록을 정리한다.
  - platform base URL
  - agent base URL
  - workspace SSE URL
  - agent SSE URL
- [ ] `infra/cicd`, `infra/k8s`, `docs/guides` 기준으로 앱 배포/연동에 필요한 환경값과 누락 manifest를 표로 정리한다.

### GitHub issue로 쪼갤 때의 추천 단위

- [ ] `[FE] OperatorCluster + ResourceEvents 실 API 연동`
- [ ] `[FE] Alerts incidents API 연동`
- [ ] `[FE] Agent panel run 생성 연동`
- [ ] `[FE] Agent SSE hook 구현`
- [ ] `[Infra/FE] frontend env 및 base URL 문서화`

### 완료 기준

- [ ] 운영 화면 3개가 mock 없이 실제 API 또는 고정 fixture adapter 기준으로 동작한다.
- [ ] AI 패널 1개 이상이 실제 FastAPI run + SSE를 사용한다.
- [ ] `npm run build`가 계속 통과한다.

---

## 이성민 - Spring Boot 모니터링 / incident public API

참고 문서:
- [frontend.md](../design/frontend.md)
- [monitoring.md](../design/backend-springboot/monitoring.md)

### 오늘/이번 라운드 TODO

- [ ] `GET /api/v1/workspaces/{wsId}/cluster`를 구현한다.
- [ ] `GET /api/v1/workspaces/{wsId}/resource-events`를 구현한다.
- [ ] `GET /api/v1/workspaces/{wsId}/incidents`를 구현한다.
- [ ] `GET /api/v1/workspaces/{wsId}/incidents/{id}`를 구현한다.
- [ ] collector/query/service가 아직 비어 있는 부분은 stub 허용하되 response shape를 먼저 고정한다.
- [ ] incident 응답에서 severity, status, grouping, timeline 기준을 먼저 정리한다.
- [ ] 기존 pipeline monitoring read와 새 operator monitoring read의 DTO/명칭 충돌이 없는지 확인한다.

### GitHub issue로 쪼갤 때의 추천 단위

- [ ] `[SB-Monitoring] cluster API 구현`
- [ ] `[SB-Monitoring] resource-events API 구현`
- [ ] `[SB-Incident] incidents 목록 API 구현`
- [ ] `[SB-Incident] incidents 상세 API 구현`
- [ ] `[SB-Monitoring] monitoring read model/stub 정리`

### 완료 기준

- [ ] frontend 운영 화면이 참조할 public monitoring API shape가 확정된다.
- [ ] incidents 목록/상세 API가 agent와 frontend에서 공통 참조 가능하다.

---

## 백강민 - Spring `/internal/ops` 계약 및 read API

참고 파일:
- [InternalController.java](../../services/operations-backend/src/main/java/com/bifrost/ops/internalops/controller/InternalController.java)
- [spring_client.py](../../services/ai-service/app/tools/spring_client.py)
- [registry.py](../../services/ai-service/app/tools/registry.py)

### 오늘/이번 라운드 TODO

- [x] `/internal/ops/health`, `/internal/ops/ready`, `/internal/ops/version`을 구현한다.
- [x] 내부 운영 공통 응답 봉투를 도입한다.
  - 현재 Spring JSON field는 `requestId`, `auditEventId`다. FastAPI 기대값 `request_id`, `audit_event_id`와의 정합은 WIP.
- [ ] 내부 운영 공통 에러 봉투를 정리한다. (WIP)
- [~] agent 헤더를 받는 공통 규칙을 정리한다. (partial)
  - `X-Agent-Run-Id`
  - `X-Agent-Step-Id`
  - `X-Agent-Name`
  - `X-Request-Id`
  - `X-Actor-Type`
  - `X-Actor-Id`
- [~] FastAPI용 최소 read tool API를 구현한다.
  - [x] `list_project_pipelines`
  - [x] `get_pipeline_topology`
  - [x] `get_connector_status`
  - [~] `get_consumer_lag` — total lag만 반환(partial)
  - [~] `search_logs` — stub
  - [~] `get_incident_summary` — stub
- [~] 실제 데이터가 아직 어렵다면 stub 응답이라도 path와 result schema는 먼저 고정한다. (path는 고정, 일부 result schema mismatch 남음)
- [ ] FastAPI가 기대하는 path와 실제 Spring path 차이를 흡수한다. (WIP)
- [x] `docs/api/internal-ops-read-tools.md`를 새로 만들고, tool 이름 / path / params / result / error mapping을 남긴다.

### GitHub issue로 쪼갤 때의 추천 단위

- [ ] `[SB-InternalOps] 공통 envelope + headers + health/ready/version 구현`
- [ ] `[SB-InternalOps] pipeline 목록 및 topology read API 구현`
- [ ] `[SB-InternalOps] connector status + consumer lag API 구현`
- [ ] `[SB-InternalOps] logs + incident summary API 구현`
- [ ] `[Docs] internal-ops read tools 계약 문서 작성`

### 완료 기준

- [ ] FastAPI `SpringOpsClient`가 path/envelope mismatch 없이 호출 가능하다.
- [ ] monitoring 기반 agent retrieval에 필요한 최소 read tool surface가 열린다.
- [ ] `/internal/ops` 계약 문서가 repo 안에 남는다.

---

## 김연수 - FastAPI agent 실제 workflow

참고 파일:
- [runner.py](../../services/ai-service/app/workflow/runner.py)
- [graph.py](../../services/ai-service/app/supervisor/graph.py)
- [registry.py](../../services/ai-service/app/tools/registry.py)
- [routes_agent.py](../../services/ai-service/app/api/routes_agent.py)

### 오늘/이번 라운드 TODO

- [ ] `run_workflow()`를 `Supervisor`와 실제로 연결한다.
- [ ] mode별 흐름을 정리한다.
  - `simple_query`
  - `incident_analysis`
  - `action_execution`
  - `approval_decision`
- [ ] `classifier`, `rca`, `remediation` skeleton을 실제 run path에 연결한다.
- [ ] tool registry canonical naming을 정리하고 Spring internalops 계약과 맞춘다.
- [ ] monitoring read tool이 실제 Spring internalops를 치도록 연결한다.
- [ ] SSE event를 보강한다.
  - `evidence_collected`
  - `partial_result`
  - `report_preview_available`
  - `approval_required`
  - `run_completed`
- [ ] 기존 테스트(`test_tools_registry.py`, `test_supervisor_graph.py`)를 최신 흐름에 맞춰 갱신한다.

### GitHub issue로 쪼갤 때의 추천 단위

- [ ] `[FA-Agent] Supervisor를 실제 run workflow에 연결`
- [ ] `[FA-Agent] tool registry canonical naming 정리`
- [ ] `[FA-Agent] incident_analysis workflow skeleton 연결`
- [ ] `[FA-Agent] monitoring read tool 실제 호출 연결`
- [ ] `[FA-Agent] SSE event 보강`
- [ ] `[FA-Agent] incident workflow 테스트 보강`

### 완료 기준

- [ ] FastAPI run path가 단순 helper 체인이 아니라 supervisor state 기반으로 동작한다.
- [ ] `incident_analysis`에서 monitoring evidence를 읽고 단계별 SSE를 보낸다.
- [ ] tool 이름과 테스트 기대값 불일치가 해소된다.

---

## 권세빈 - wireframe 기준 Spring Boot 설정 / 계정 구현

참고 화면:
- [Settings.tsx](../../services/frontend/src/pages/Settings.tsx)
- [Login.tsx](../../services/frontend/src/pages/Login.tsx)
- [AppStore.tsx](../../services/frontend/src/store/AppStore.tsx)

### 구현 기준 섹션

- [x] `내 계정`
- [x] `일반`
- [x] `멤버`
- [x] `알림`
- [x] `임계값`
- [x] `AI 자동복구`
- [ ] `Kafka 사용자` / `Kafka 시크릿`은 후순위 백로그로 둔다.

### 오늘/이번 라운드 TODO

- [x] `내 계정` 섹션용 API를 정리한다.
  - `me`
  - 이름 / 이메일 / 역할
  - 가입일
  - 마지막 로그인
- [x] `일반` 섹션용 API를 구현한다.
  - 프로젝트 이름 조회 / 수정
  - 시간대 조회 / 수정
  - 슬러그는 읽기 전용 유지
- [x] `멤버` 섹션용 API를 구현한다.
  - 멤버 목록
  - 초대
  - 역할 변경
  - 제거
- [x] `알림` 섹션용 API를 구현한다.
  - Slack webhook
  - 이메일 수신자
  - severity 정책
- [x] `임계값` 섹션용 API를 구현한다.
  - lag warning threshold
  - lag critical threshold
- [x] `AI 자동복구` 섹션용 API를 구현한다.
  - autonomous
  - approval wait
  - prod lock
- [x] 설정 저장용 persistence 전략을 정한다.
  - 테이블 추가 또는 임시 저장 전략
- [ ] `Kafka 사용자`, `Kafka 시크릿`은 후순위 관리 API로 분리해 백로그화한다.

### GitHub issue로 쪼갤 때의 추천 단위

- [ ] `[SB-Settings] 내 계정(me/session) API 정리`
- [ ] `[SB-Settings] 일반 설정 API 구현`
- [ ] `[SB-Settings] 멤버 목록/초대/역할변경/삭제 API 구현`
- [ ] `[SB-Settings] 알림 설정 API 구현`
- [ ] `[SB-Settings] 임계값 설정 API 구현`
- [ ] `[SB-Settings] AI 자동복구 설정 API 구현`
- [ ] `[SB-Settings][Backlog] Kafka 사용자/시크릿 관리 API`

### 완료 기준

- [ ] frontend `Settings`의 핵심 섹션이 mock/local state가 아니라 서버 API를 볼 수 있다.
- [ ] login/me/session 흐름과 설정 저장 흐름이 충돌 없이 정리된다.

---

## handoff 순서

| 우선순위 | 산출물 | 담당 |
| --- | --- | --- |
| 1 | `/internal/ops` 공통 계약, read tool 목록, envelope, JSON 예시 | 백강민 |
| 2 | overview / cluster / resource-events / incidents public API shape | 이성민 |
| 3 | Settings / Account / Members / Notifications / Thresholds / AI policy API shape | 권세빈 |
| 4 | supervisor 연결, tool naming 정리, incident_analysis 흐름, SSE event 정의 | 김연수 |
| 5 | 운영 화면 실 API 연동, AI 패널 run/SSE 연동, env 정리 | 정재환 |

## 이번 라운드 종료 조건

- [ ] 파이프라인 생성 신규 기능이 아니라 monitoring/agent/settings가 메인 개발 축으로 전환되었다.
- [ ] 운영 화면이 mock 의존에서 벗어나기 시작했다.
- [ ] FastAPI agent가 실제 Spring `/internal/ops` 데이터를 읽기 시작했다.
- [ ] Settings wireframe의 핵심 섹션이 서버 API 기준으로 구현되기 시작했다.
- [ ] 각 담당 TODO가 GitHub issue로 쪼개질 수 있는 수준으로 정리되었다.
