# Bifrost — 기능 명세서

> 기준: 현재 구현 코드 (와이어프레임)
> 작성일: 2026-05-26 (편입·정합: 2026-06-01)
> 버전: v1.0
> 액터: **사용자** (v1은 단일 콘솔. 구체 액터 구분·시나리오는 추후 결정하며, 본 문서는 일단 "사용자"로 통일한다)
>
> 이 문서는 FR 카탈로그와 부록 B(상태값·임계값·이벤트→인시던트 규칙)를 모아 둔 요구사항 기준 문서다. 최종 SOT는 코드 구현이며, 설계 문서([README](./README.md), [design/backend-springboot/](./design/backend-springboot/overview.md), [design/backend-fastapi/](./design/backend-fastapi/overview.md), [design/frontend.md](./design/frontend.md))는 이 문서를 용어·요구사항 기준으로 인용한다.

---

## 1. 핵심 기능 요구사항 목록

| 요구사항 ID | 우선순위 | 유형 | 요구사항 내용 | 관련 화면 |
|---|---|---|---|---|
| FR-001 | 상 | 일반 | 사용자가 이메일·비밀번호로 로그인하여 콘솔에 진입한다 | LoginView |
| FR-002 | 상 | 일반 | 사용자가 워크스페이스를 생성하고 선택하여 작업 범위를 지정한다 | WorkspaceListView, WorkspaceCreateModal |
| FR-003 | 상 | 일반 | 사용자가 Pipeline 목록을 상태별로 필터링하여 조회한다 | PipelinesView |
| FR-004 | 상 | 일반 | 사용자가 단계별 마법사로 EDA/CDC Pipeline을 생성한다 | CreatePipelineModal |
| FR-005 | 상 | 일반 | 사용자가 Pipeline을 일시정지·재개·삭제한다 | PipelineDetail |
| FR-006 | 상 | 일반 | 사용자가 Pipeline 상세에서 처리량·랙·에러율 추이를 확인한다 | PipelineDetail → Overview 탭 |
| FR-007 | 상 | 일반 | 사용자가 Consumer 그룹별 랙·오프셋을 파티션 단위로 드릴다운한다 | PipelineDetail → Consumers 탭 |
| FR-008 | 상 | 일반 | 사용자가 Connector 인스턴스의 상태·에러율·Poll Batch 시간·재시도 수·마지막 오류를 확인한다 | PipelineDetail → Connector 탭 |
| FR-009 | 중 | 일반 | 사용자가 CDC Pipeline의 Source→Sink 행 수·동기화율·지연 추이를 확인한다 | PipelineDetail → Sync 탭 |
| FR-010 | 중 | 일반 | 사용자가 Pipeline에 실제 흐르는 Debezium 이벤트 메시지를 조회한다 | PipelineDetail → Messages 탭 |
| FR-011 | 중 | 일반 | 사용자가 Kafka 구독에 필요한 정보와 언어별 코드 스니펫을 제공받는다 | PipelineDetail → Connection Guide 탭 |
| FR-012 | 하 | 일반 | 사용자가 Pipeline의 테이블 컬럼·타입 매핑을 확인한다 | PipelineDetail → Table Mapping 탭 |
| FR-013 | 상 | 일반 | 사용자가 Database 목록을 검색·역할별로 조회한다 | DatabasesView |
| FR-014 | 상 | 일반 | 사용자가 신규 Database를 연결 정보와 함께 등록한다 | AddDatabaseModal |
| FR-015 | 상 | 일반 | 사용자가 Database의 CDC 연결 준비도(wal_level·replication slot 등)를 점검한다 | DatabaseDetail → 연결 준비도 탭 |
| FR-016 | 중 | 일반 | 사용자가 Database의 스키마(테이블·컬럼·인덱스)를 탐색한다 | DatabaseDetail → Schema 탭 |
| FR-017 | 중 | 일반 | 사용자가 Database의 TPS·쿼리 응답 시간·활성 연결 지표를 확인한다 | DatabaseDetail → Metrics 탭 |
| FR-018 | 중 | 일반 | 사용자가 Database에 연결된 Pipeline 목록과 상태를 확인한다 | DatabaseDetail → Pipelines 탭 |
| FR-019 | 중 | 일반 | 사용자가 Kafka 이벤트 로그를 Source·레벨별로 필터링하고 상세를 조회한다 | AlertsView |
| FR-020 | - | 일반 | v1 와이어프레임 기준 운영 현황 대시보드는 제공하지 않는다 | 제거됨 |
| FR-021 | 상 | 일반 | 사용자가 인시던트 목록을 확인하고 상세 원인·영향 범위를 조회한다 | AlertsView |
| FR-022 | 상 | 에이전트 | 사용자가 AI 추천 조치를 위험도·예상 소요시간과 함께 검토한 뒤 Run으로 승인 실행한다 (HITL) | AlertsView, BifrostAgentPanel |
| FR-023 | 중 | 일반 | 사용자가 Kafka Broker·Kafka Connect Worker 현황을 클러스터 탭에서 확인한다 | OperatorClusterView |
| FR-024 | 중 | 일반 | 사용자가 클러스터 레이어에서 발생하는 리소스 이벤트 로그를 조회한다 | AlertsView |
| FR-025 | 상 | 에이전트 | 사용자가 AI 채팅 패널에서 Pipeline 조회·상태 확인·Pause/Resume를 자연어로 요청하며 Tool Call이 카드로 시각화된다 | BifrostAgentPanel |
| FR-026 | 상 | 에이전트 | AI가 다중 파이프라인 랙 급증·커넥터 중단을 자동 감지하여 장애 리포트와 근본 원인·조치 옵션을 생성한다 | BifrostAgentPanel |

---

## 2. 요구사항 상세 명세

### FR-001 — 로그인 및 콘솔 진입
- **액터**: 사용자
- **기능 설명**: 사용자가 이메일·비밀번호를 입력하여 로그인한다. 인증 성공 시 WorkspaceListView로 이동한다. 역할 분기 없이 단일 콘솔로 진입한다.
- **사전 조건**: 유효한 계정이 시스템에 등록되어 있어야 한다.
- **기본 흐름**: 1) 이메일·비밀번호 입력 → 2) 시스템이 자격증명 검증 → 3) `currentUser` 설정 후 WorkspaceListView 이동.
- **예외 흐름**: ① 자격증명 불일치 → 로그인 실패 메시지 ② 필드 미입력 → 입력 오류 메시지.
- **API**: `POST /api/v1/auth/register`, `POST /api/v1/auth/login`, `POST /api/v1/auth/refresh`, `GET /api/v1/auth/me`. `/me` 응답 필드는 `userId, email, name, role, joinedAt, lastLoginAt, workspaceId, workspaceName, namespace, workspaceStatus`다([Spring Boot API](./api/springboot.md#auth--account)).
- **비고**: 데모 계정 `ta@bifrost.io / ta1234`(시드 계정명일 뿐, 액터 역할을 고정하지 않음). FR-002의 선행 조건. `/api/auth/**` alias는 404 `RESOURCE_NOT_FOUND` envelope으로 거부하고 `/api/v1/auth/**`만 사용한다.

### FR-002 — 워크스페이스 생성 및 선택
- **액터**: 사용자
- **기능 설명**: 사용자가 기존 워크스페이스를 선택하거나 신규 생성한다. 선택 후 모든 데이터가 해당 워크스페이스 범위로 스코프된다.
- **사전 조건**: `currentUser !== null && currentWorkspace === null`
- **기본 흐름**: 1) 워크스페이스 카드 목록 확인 → 2) 카드 클릭 → `setCurrentWorkspace(ws)` → PipelinesView → 3) 신규는 "+ 새 워크스페이스" → WorkspaceCreateModal → 4) 이름 입력(**슬러그 자동 생성**) → "만들기" → 자동 선택 → PipelinesView.
- **예외 흐름**: ① 워크스페이스 없음 → 빈 상태 + 생성 CTA ② 이름 미입력 → 생성 버튼 비활성화.
- **API**: `GET/POST /api/v1/workspaces`, `GET/PATCH /api/v1/workspaces/{wsId}`, 멤버 API `GET/POST /api/v1/workspaces/{wsId}/members`, `PATCH/DELETE /api/v1/workspaces/{wsId}/members/{userId}`.
- **권한**: 멤버 목록은 워크스페이스 소속 사용자 모두 조회 가능하다. 멤버 추가·역할 변경·삭제와 workspace 수정은 `OWNER`/`ADMIN`만 가능하다.
- **비고**: **슬러그(projectKey)는 영소문자·숫자·하이픈으로 이름에서 자동 생성**한다. 이 슬러그가 Kafka 토픽/ACL/KafkaUser 이름의 기준이 된다.

### FR-002A — Workspace Settings
- **액터**: 워크스페이스 멤버, OWNER/ADMIN
- **기능 설명**: Settings 화면에서 알림, 임계값, AI 자동복구 정책을 조회·수정한다.
- **기본 흐름**: 1) Settings 진입 → 2) notifications/thresholds/ai-policy 섹션 조회 → 3) OWNER/ADMIN이 값을 수정 → 4) 저장 후 최신 설정 반환.
- **예외 흐름**: ① 비멤버 접근 → 403 ② MEMBER 수정 시도 → 403 ③ Slack URL/threshold/approvalWaitMinutes 형식 오류 → 400.
- **API**: `GET/PUT /api/v1/workspaces/{wsId}/settings/notifications`, `GET/PUT /settings/thresholds`, `GET/PUT /settings/ai-policy`.
- **권한**: 조회는 워크스페이스 소속 사용자 모두 가능, 수정은 `OWNER`/`ADMIN`만 가능하다.

### FR-003 — Pipeline 목록 조회
- **액터**: 사용자
- **기능 설명**: 현재 워크스페이스의 Pipeline 목록을 상태 필터로 조회한다. 상단 요약 칩(전체·활성·이상·인시던트)으로 현황을 표시하고 카드 클릭으로 상세 진입한다.
- **사전 조건**: `currentWorkspace !== null`
- **기본 흐름**: 1) Pipeline 카드 목록 표시 → 2) 요약 칩 클릭 시 상태 필터 적용 → 3) 카드 클릭 → PipelineDetail → 4) "Pipeline 연결" → CreatePipelineModal.
- **예외 흐름**: ① Pipeline 없음 → 빈 상태 + CTA ② 필터 결과 없음 → "해당 상태의 Pipeline이 없습니다".
- **비고**: 정렬 `error → lag → creating → active → paused`. 요약 칩 "인시던트" 클릭 시 AlertsView로 이동.

### FR-004 — Pipeline 신규 생성 마법사
- **액터**: 사용자
- **기능 설명**: 단계별 마법사로 EDA(이벤트 스트림) 또는 CDC(데이터 동기화) Pipeline을 생성한다. Kafka Topic 이름·파티션·오프셋 등 인프라 설정은 시스템이 자동 처리하여 노출하지 않는다.
- **사전 조건**: 하나 이상의 Source DB 등록. CDC는 Sink DB도 등록.
- **기본 흐름**: 1) Step1 연결 방식(EDA fan-out / CDC direct) → 2) Step2 Source DB 선택 → 3) Step3 대상 테이블 선택(ok/warning/blocked 즉시 표시) → 4) Step4 (CDC만) Sink DB 선택 → 5) Step5 이름 입력 → "생성" → `creating` → `active`로 전이(와이어프레임 mock은 약 3초, 실제는 일반적으로 Connector RUNNING까지 최대 30초 — 부록 B.1).
- **예외 흐름**: ① 등록 Source DB 없음 → 등록 안내 ② 테이블 `blocked` → 생성 비활성화 + 준비도 안내 ③ 이름 미입력·중복 → 오류 ④ 생성 중 오류 → `error` + 재시도 안내.
- **사후 조건**: 워크스페이스 pipeline에 추가, Kafka Topic 자동 생성, 상태 `active` 전이.
- **비고**: EDA `pattern='fan-out'` sink=null / CDC `pattern='direct'` sink=DB id. 백엔드는 `fan_out`도 하위 호환으로 파싱하지만 API 응답과 프론트 정본 표기는 `fan-out`이다. FR-015 점검 권장.

### FR-005 — Pipeline 일시정지·재개·삭제
- **액터**: 사용자
- **기능 설명**: PipelineDetail 헤더 버튼으로 Pause·Resume·Delete.
- **사전 조건**: Pipeline 존재, PipelineDetail 진입.
- **기본 흐름**: 1) 상태에 따라 Pause/Resume 버튼 → 2) Pause → `paused` → 3) Resume → `active` → 4) Delete → 확인 다이얼로그 → 삭제 후 PipelinesView.
- **예외 흐름**: ① `creating` 상태 Pause/Delete 비활성화 ② 삭제 취소 → 동작 없음.
- **비고**: 인시던트 대응 시 FR-022 AI 추천 조치로 Pause 실행될 수 있음.

### FR-006 — Pipeline 모니터링 개요 (Overview)
- **기능 설명**: 실시간 Produce/Consume Rate·Consumer Lag·Error Rate 카드와 시계열 차트.
- **사전 조건**: Pipeline `active` 또는 `lag`.
- **기본 흐름**: 1) Overview 탭 → 2) 4개 지표 카드 → 3) Produce/Consume Rate 차트 → 4) Consumer Lag ≥ 5,000 → amber 강조.
- **예외 흐름**: ① `paused` → 지표 0 ② 데이터 없음 → 빈 차트.
- **비고**: Consumer Lag 임계값 5,000 msg (부록 B.1). EDA는 외부 구독자만 소비하므로 lag 상태/인시던트는 **CDC sink consumer group에만** 적용한다(B.1).

### FR-007 — Consumer 랙 모니터링
- **기능 설명**: Consumer 그룹별 총 랙과 파티션별 Current/Log End Offset·Lag 드릴다운.
- **기본 흐름**: 1) Consumers 탭 → 2) 그룹 목록(그룹명·총 랙·상태) → 3) 그룹 클릭 → 파티션 Offset 테이블 → 4) Lag ≥ 5,000 파티션 amber 강조.
- **예외 흐름**: ① 그룹 없음 안내 ② `REBALANCING` 상태 배지.
- **비고**: FR-025 "랙 현황 알려줘" 인텐트와 연계.

### FR-008 — Connector 인스턴스 모니터링
- **기능 설명**: Source/Sink Connector 인스턴스의 상태·Task 수·에러율·Poll Batch(avg/max)·재시도 누적·마지막 오류 시각·Records/sec 추이. JVM Heap·CPU·GC 등 **Worker 인프라 지표는 OperatorCluster > KafkaConnect 탭**에서 확인. DLQ는 자동 생성 미지원이므로 노출하지 않음.
- **기본 흐름**: 1) Connector 탭 → 2) Source 카드(상태·Tasks·Records/s·에러율·Poll Batch·재시도·마지막 오류·Uptime) → 3) (CDC) Sink 카드 → 4) 임계 초과 amber/red → 5) 추이 차트.
- **예외 흐름**: ① `FAILED` → red 배지 + 오류 메시지.
- **비고**: Kafka Connect REST를 사용자에게 직접 노출하지 않고 백엔드 경유.

### FR-009 — CDC 동기화 현황
- **기능 설명**: Source·Sink DB 행 수 비교·동기화율·소스 지연 추이.
- **사전 조건**: `pattern='direct'`.
- **기본 흐름**: 1) Sync 탭 → 2) Source/Sink 행 수·동기화율(%)·지연 ms 카드 → 3) 지연 추이 차트 → 4) 동기화율 < 100% 경고.
- **예외 흐름**: ① EDA Pipeline → "CDC에서만 사용 가능" ② Sink 연결 불가 → 안내.
- **비고**: 동기화율 급하락 시 FR-026 자동 감지 트리거. 동기화율은 **Kafka 오프셋 기반 근사**(sink 소비 오프셋 vs source 생산량)로 산출하며, 고객 DB `count(*)` 정밀 비교는 하지 않는다(v1).

### FR-010 — 토픽 메시지 조회
- **기능 설명**: Debezium 이벤트 메시지 목록과 페이로드(before/after).
- **사전 조건**: `active` 상태 + 메시지 존재.
- **기본 흐름**: 1) Messages 탭 → 2) Offset·Partition·Timestamp·Operation·Key 목록 → 3) 행 클릭 → before/after JSON.
- **비고**: Topic 이름은 이 탭에서 노출하지 않고 별칭(alias)으로만 표시. 메시지는 토픽 **최근 N건을 bounded consume**해 before/after를 표시한다(v1 기본).

### FR-011 — 구독 가이드 및 코드 스니펫
- **기능 설명**: Topic Name·Bootstrap Server·Consumer Group ID + 언어별(Java/Python/Node.js) 코드 스니펫 복사.
- **사전 조건**: Pipeline 존재 + Topic 생성.
- **기본 흐름**: 1) Connection Guide 탭 → 2) 연결 정보 → 3) 언어 탭 선택 → 4) "복사" → 클립보드 + 토스트.
- **예외 흐름**: ① `creating` → Topic 이름 미확정 안내.
- **비고**: **Topic 이름이 일반 화면에서 노출되는 유일한 탭.** 사용자(또는 구독 측)가 Kafka 구독 구현에 필요한 정보를 획득한다.

### FR-012 — 테이블 매핑
- **기능 설명**: Source 테이블 컬럼명·타입과 Kafka 메시지 필드 매핑.
- **기본 흐름**: 1) Table Mapping 탭 → 2) 매핑 테이블 표시.
- **예외 흐름**: ① 매핑 정보 없음 안내.

### FR-013 — Database 목록 조회
- **기능 설명**: 등록 Database를 검색어·DB 기술(PostgreSQL/MariaDB)·역할(source/sink)로 필터.
- **사전 조건**: `currentWorkspace !== null`
- **기본 흐름**: 1) DatabasesView → 2) 검색 실시간 필터 → 3) 기술 필터 → 4) 카드 클릭 → DatabaseDetail → 5) "+ Database 연결" → AddDatabaseModal.
- **비고**: source/sink 역할은 파이프라인에서 결정되며 DB 자체에 고정 역할 속성은 없다. 목록의 `role` 필터는 **파생값**(해당 DB가 1개 이상 파이프라인에서 source/sink로 쓰이는지)이고, 파이프라인 생성 마법사의 소스 선택은 role로 거르지 않고 CDC-ready DB 전체를 대상으로 한다(FR-004).

### FR-014 — Database 등록
- **기능 설명**: 연결 정보(이름·호스트·포트·DB명·계정·비밀번호) 입력, 연결 테스트 후 등록.
- **사전 조건**: `currentWorkspace !== null`
- **기본 흐름**: 1) AddDatabaseModal → 2) 기술 선택 → 3) 연결 정보 입력 → 4) "연결 테스트" → 5) "등록".
- **예외 흐름**: ① 연결 테스트 실패 → 분류된 오류 + 재입력 ② 필수 필드 미입력 → 비활성화 ③ 이름 중복 → 오류.
- **비고**: 등록 후 FR-015 점검 권장. 자격증명은 secretRef로 보관(메타DB에 평문·암호문 저장 금지).

### FR-015 — CDC 연결 준비도 점검
- **기능 설명**: 해당 DB가 CDC Source로 사용 가능한지 점검. 요약 카드 + 기술 항목(wal_level·replication 권한·replication slot·wal_senders) 토글.
- **기본 흐름**: 1) 연결 준비도 탭 → 2) 요약 카드("준비 완료" / "N개 항목 주의") → 3) 항목별 상태(OK/WARNING/BLOCKED) + hint.
- **예외 흐름**: ① wal_level이 logical 아님 → BLOCKED + 가이드 ② slot 한도 근접 → WARNING ③ 조회 실패 → 재시도 안내.
- **비고**: FR-004 마법사의 선행 점검.

### FR-016 — Schema 탐색
- **기능 설명**: 테이블 목록 + 컬럼·타입·제약·인덱스.
- **기본 흐름**: 1) Schema 탭 → 2) 테이블 목록(테이블명·행 수·크기) → 3) 클릭 → 컬럼 목록.

### FR-017 — Database Metrics
- **기능 설명**: TPS·쿼리 응답 시간(avg/p95)·활성 연결 수 + 시계열.
- **기본 흐름**: 1) Metrics 탭 → 2) 지표 카드 → 3) 차트.
- **비고**: 지표는 inspector가 엔진별 stat을 질의해 산출한다(v1 기본: 활성 연결·기본 TPS — 예: PG `pg_stat_activity`/`pg_stat_database`, MariaDB `SHOW GLOBAL STATUS`).

### FR-018 — 연결된 Pipeline 목록
- **기능 설명**: 해당 DB를 Source/Sink로 쓰는 Pipeline 목록과 상태.
- **기본 흐름**: 1) Pipelines 탭 → 2) 목록(이름·역할·상태) → 3) 클릭 → PipelineDetail.

### FR-019 — 이벤트 로그 조회
- **기능 설명**: AlertsView 통합 이벤트 로그에서 Kafka 파이프라인 이벤트와 리소스 이벤트를 실 API 데이터로 조회한다.
- **기본 흐름**: 1) AlertsView → 2) Source/레벨 필터 → 3) 이벤트 row 클릭 → 4) 상세 패널(시각·타입·메시지·연결 Pipeline/Incident/Resource).
- **비고**: 데이터는 `/events`와 `/monitoring/resource-events` 응답을 사용하며, 자동 갱신은 동일 API 재조회로 처리한다. Kafka 브로커 내부 인프라 이벤트는 노출하지 않음.

### FR-020 — 운영 현황 대시보드
- **상태**: v1 와이어프레임 기준 제거됨.
- **비고**: 파이프라인 현황은 PipelinesView/PipelineDetail, 인시던트 진입은 AlertsView에서 제공한다.

### FR-021 — 인시던트 목록 및 상세 조회
- **기능 설명**: 미해결 인시던트를 배너로 인지, 통합 이벤트 스트림에서 이벤트-인시던트 연결 확인. 인시던트 클릭 시 우측 슬라이드 패널에서 근본 원인·영향 파이프라인·관련 이벤트 타임라인·추천 조치.
- **기본 흐름**: 1) AlertsView 상단 `OPEN`/`INVESTIGATING` 배너(심각도 색) → 2) 이벤트 스트림(최신순, 인시던트 연결 이벤트에 `[inc-N ↗]` 배지) → 3) 레벨 필터 → 4) 배너/배지 클릭 → 우측 패널 슬라이드인 → 5) 패널(상태·영향 Pipeline·영향 행·근본 원인·관련 이벤트 타임라인(trigger 강조)·추천 조치 Run) → 6) 해결된 인시던트는 하단 텍스트 링크.
- **비고**: FR-022 연계. 부록 B.7 이벤트-인시던트 연결 모델 참조. 패널의 **영향 행**은 sync gap/consumer lag 기반 **추정치**다(정밀 카운트 아님).

### FR-022 — AI 추천 조치 실행 (HITL)
- **액터**: 사용자, AI 에이전트
- **기능 설명**: AI 추천 조치를 위험도(low/medium/high)·예상 소요시간과 함께 검토 후 "Run"으로 승인 실행. 모든 조치는 actor·시각과 함께 타임라인 기록.
- **기본 흐름**: 1) 추천 조치 목록 확인 → 2) 검토 → 3) "Run" → 실행 → 4) 타임라인에 actor(사용자)·시각·내용 기록 → 5) 인시던트 상태 `INVESTIGATING` 또는 `RESOLVED` 갱신.
- **예외 흐름**: ① `high` 조치 → 추가 확인 ② 실행 실패 → 실패 기록 + 재검토 ③ 이미 `RESOLVED` → 차단 ④ AI 요약 실패 → 수동 로그 경로.
- **비고**: AI는 의사결정 지원만. 실행은 반드시 사용자 승인 후(HITL).

### FR-023 — 클러스터 현황
- **기능 설명**: Broker·Kafka Connect Worker 현황(탭별). Topic·Consumer Group은 Pipeline 상세에서 관리. Broker CPU/디스크/네트워크 인프라 지표는 인프라 운영 영역으로 미노출.
- **기본 흐름**: 1) OperatorClusterView(Brokers·Kafka Connect 탭) → 2) Brokers(목록·상태·리더 파티션 수·연결 정보) → 3) Kafka Connect(Worker JVM Heap·CPU·GC + Connector 목록).

### FR-024 — 리소스 이벤트 로그
- **기능 설명**: 파티션 재분배·리더 선출·컨슈머 그룹 리밸런싱 등 클러스터 리소스 이벤트 타임라인.
- **기본 흐름**: 1) AlertsView → 2) Resource 필터 → 3) 이벤트 row 클릭 → 4) 상세.

### FR-025 — AI 채팅 어시스턴트
- **액터**: 사용자, AI 에이전트
- **기능 설명**: 헤더 AI 버튼으로 채팅 패널, Pipeline 조회·상태 확인·Pause/Resume를 자연어 요청. 인텐트·Tool Call 결과가 카드로 시각화.
- **기본 흐름**: 1) AI 버튼 → BifrostAgentPanel → 2) 자연어 입력 → 3) 인텐트 분류 후 Tool Call → 4) 결과 카드 → 5) 분석·요약·권장 조치 텍스트 → 6) 조치 요청(Pause/Resume) → 확인 카드 → 사용자 승인 후 실행.
- **예외 흐름**: ① 인텐트 인식 실패 안내 ② Tool 호출 실패 + 재시도.
- **비고**: 인텐트 유형: 상태/랙/에러 조회, Pause/Resume, 이벤트 로그 요약. 파이프라인 **생성은 마법사(FR-004) 전용**이며 v1 agent는 생성을 실행하지 않는다.

### FR-026 — AI 인시던트 자동 감지 및 장애 리포트
- **액터**: 사용자, AI 에이전트
- **기능 설명**: 여러 파이프라인의 Lag 급증·Connector 중단·CDC 동기화 이탈을 자동 감지해 장애 리포트(근본 원인 추론·영향 파이프라인·영향 행·추천 조치) 생성. 사용자가 시나리오 선택·실행하고 HITL 승인.
- **기본 흐름**: 1) 임계 초과 감지(Lag ≥ 5,000 또는 Connector FAILED 등) → 2) 영향 Pipeline 공통 원인 분석 → 3) 리포트 생성 → 4) 시나리오 카드 선택 → 5) "Run" → 사용자 승인 → 6) 타임라인 기록.
- **예외 흐름**: ① 근본 원인 신뢰도 낮음 → "불확실" 라벨 + 수동 경로 ② 실행 실패 기록 ③ 에이전트 타임아웃 → 알림 + 수동 안내.
- **비고**: 모든 조치는 사용자 승인 필수(HITL). 인시던트는 자동 생성되나 **RCA 분석 run은 사용자가 AlertsView에서 시작**한다(v1 — 자동 run 트리거 아님). **영향 행**은 sync gap/lag 추정치.

---

## 부록 — 공통 UI 패턴

### 토스트 알림
| 트리거 | 메시지 유형 |
|---|---|
| Pipeline 생성/상태 변경 완료 | success |
| Database 등록 완료 | success |
| 코드 스니펫 복사 | success |
| 조치 실행 완료 | success |
| 오류 발생 | error |
| 경고 상태 변경 | warning |

### 상태 색상 규칙
| 상태 | 색상 |
|---|---|
| active / healthy / ok | emerald(green) |
| lag / warning | amber |
| error / critical | red/rose |
| paused | gray |
| creating | blue(pulse) |

### 라우팅 맵
`login` LoginView · `workspaces` WorkspaceListView · `pipelines` PipelinesView · `pipeline-detail` PipelineDetail · `databases` DatabasesView · `database-detail` DatabaseDetail · `alerts` AlertsView · `cluster` OperatorClusterView · `settings` SettingsView

---

<a id="부록-b--리소스-상태값-정의-및-자동-기준-단일-출처"></a>

## 부록 B — 리소스 상태값 정의 및 자동 기준

> **원칙**: 상태값은 구현 가능한 지표에서 직접 파생한다. 임의 라벨 없이 산정 가능한 값만 사용한다.
> 아래의 **이 기준을 초과할 때 인시던트를 자동 생성**하고, 사이드바 Incidents 배지 + 이벤트 로그에 기록한다.
> 설계 문서(Data Model `pipeline.status`/`connector.state`/`incident`, Provisioning watch, Evidence Matrix)는 이 부록을 용어·요구사항 기준으로 공유한다. 현재 구현 여부와 실제 산정 로직은 Spring/FastAPI 코드가 최종 기준이다.

### B.1 Pipeline 상태값
**데이터 소스**: Kafka Consumer Group API, Connector REST API

| 상태값 | 정의 | 표시 색상 |
|---|---|---|
| `active` | Connector RUNNING + CDC sink consumer group lag < 경고 임계값(기본 5,000) | emerald |
| `lag` | Connector RUNNING이지만 CDC sink consumer group lag ≥ 경고 임계값(기본 5,000) | amber |
| `error` | Connector FAILED/PARTIALLY_FAILED 상태이거나 Error Rate > 2% | red |
| `paused` | 사용자가 명시적으로 일시정지 | gray |
| `creating` | 생성 후 Connector RUNNING 전이 대기(일반적으로 최대 30초, 기본 5분 초과 시 `error`) | blue |

**자동 생성 기준**

| 조건 | 심각도 | 이벤트 레벨 | 비고 |
|---|---|---|---|
| CDC sink consumer group lag ≥ 5,000 | WARNING | WARN | Pipeline `lag` 전이 |
| CDC sink consumer group lag ≥ 50,000 | CRITICAL | ERROR | 사실상 정지 수준 |
| Connector Task FAILED 또는 PARTIALLY_FAILED | CRITICAL | ERROR | Pipeline `error` 전이 |
| Error Rate > 0.5% | WARNING | WARN | |
| Error Rate > 2% | CRITICAL | ERROR | Pipeline `error` 전이 |
| Pipeline `creating` 5분 초과 | CRITICAL | ERROR | Pipeline `error` 전이. 기본값 `pipeline.provisioning-timeout=PT5M` |

**구현**: Consumer lag = `ListOffsets endOffset − OffsetFetch committedOffset` · Connector 상태 = Connect REST `GET /connectors/{name}/status` `tasks[].state` · Error Rate = 처리량 대비 실패/누락 비율(예: Source `(poll-write)/poll`) · `creating` hard timeout = `pipeline.provisioning-timeout` 기본 `PT5M`. 정확한 JMX 지표·산식은 구현 시 확정한다(현 `poll/write` 단순 비율 표기는 정의가 모호해 보정 대상).

> **EDA(fan-out) vs CDC(direct)의 lag 적용 범위**: lag 기반 상태(`lag`)와 lag 인시던트는 Bifrost가 컨슈머를 소유하는 **CDC(JDBC Sink consumer group)** 에만 적용한다. EDA는 Sink가 없어 토픽을 외부 구독자만 소비하므로, EDA 파이프라인 상태는 **Source Connector state로만** 산정하고(`creating`/`active`/`error`/`paused`), 외부 consumer group lag으로 `lag` 전이나 인시던트를 만들지 않는다(외부 컨슈머는 Bifrost 직접 복구 대상이 아님). EDA 토픽의 외부 구독자 lag은 참고 지표로만 노출한다.

### B.2 Connector 인스턴스 상태값
**데이터 소스**: Kafka Connect REST `GET /connectors/{name}/status`

| 상태값 | 정의 | 색상 |
|---|---|---|
| `RUNNING` | 모든 Task RUNNING | emerald |
| `PARTIALLY_FAILED` | 일부 Task FAILED, 나머지 RUNNING (Bifrost 합성 상태) | amber |
| `FAILED` | 모든 Task FAILED 또는 Connector 자체 FAILED | red |
| `PAUSED` | 사용자 또는 Bifrost가 명시적 일시정지 | gray |
| `UNASSIGNED` | Worker에 아직 미할당(기동 진행) | blue |

| 지표 | 데이터 소스 | 임계 | 심각도 |
|---|---|---|---|
| Poll Batch 최대 처리 시간 | JMX `connector-task-metrics` `batch-size-max` | > 500ms | WARNING |
| Retry 누적 횟수 | Connect REST 오류 이력 | > 50회/시간 | WARNING |
| 자동 재시작 횟수 | Bifrost 내부 이벤트 로그 | 1시간 내 3회 | CRITICAL |
| Error Rate | JMX 실패/누락율((poll-write)/poll 등, 구현 시 확정) | > 0.5% WARNING, > 2% CRITICAL | |

### B.3 Database(Node) 상태값
**데이터 소스**: DB 직접 연결 테스트, `pg_replication_slots` / `SHOW SLAVE STATUS`

| 상태값 | 정의 | 색상 |
|---|---|---|
| `healthy` | 연결 성공 + Replication lag < 1,000ms | emerald |
| `warning` | 연결 성공이지만 Replication lag ≥ 1,000ms 또는 slot retainedWAL 한도 근접 | amber |
| `error` | 연결 실패 또는 Replication slot 비정상 | red |

| 조건 | 심각도 | 이벤트 레벨 |
|---|---|---|
| DB 연결 실패 | CRITICAL | ERROR |
| Replication lag ≥ 1,000ms (Source) | WARNING | WARN |
| Replication lag ≥ 5,000ms (Source) | CRITICAL | ERROR |
| retainedWAL ≥ 500MB | WARNING | WARN |
| retainedWAL ≥ 1GB | CRITICAL | ERROR |

**구현**: 연결 = 주기 `SELECT 1`(5초) · PG lag = `confirmed_flush_lsn` diff · MariaDB lag = `Seconds_Behind_Master` · retainedWAL = `pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)`.

### B.4 Consumer Group 상태값
**데이터 소스**: Kafka Admin `DescribeConsumerGroups`

| 상태값 | 정의 | 색상 |
|---|---|---|
| `STABLE` | 모든 파티션 Member 할당된 정상 | emerald |
| `REBALANCING` | 멤버 변동으로 재할당 진행 | amber |
| `DEAD` | Member 없고 offset도 없음 | gray |
| `EMPTY` | Member 없지만 offset 존재 | gray |

| 조건 | 심각도 | 이벤트 레벨 |
|---|---|---|
| lag ≥ 5,000 | WARNING | WARN |
| lag ≥ 50,000 | CRITICAL | ERROR |
| `DEAD` (과거 STABLE) | WARNING | WARN |
| `REBALANCING` 5분 이상 지속 | WARNING | WARN |
| 특정 파티션 Member 미할당(lag 증가 중) | WARNING | WARN |

### B.5 이벤트 로그 레벨 정의
| 레벨 | 정의 | 인시던트 자동 생성 |
|---|---|---|
| `INFO` | 정상 운영 중 사용자 액션·예상된 상태 변화 | 없음 |
| `WARN` | 임계 초과 또는 일시 오류, 조치 권고 | 조건부 WARNING 인시던트 |
| `ERROR` | 서비스 중단·데이터 손실 위험 | 항상 CRITICAL 인시던트 |

### B.6 이벤트 카탈로그
> 아래 조건에서 이벤트를 생성한다. 이벤트는 항상 이벤트 로그에 기록되고, 인시던트 생성 여부는 "인시던트" 열을 따른다.

#### B.6.1 Pipeline 이벤트 (Kafka Admin 30초, Connect REST 10초)
| 트리거 | 레벨 | Pipeline 상태 | 인시던트 |
|---|---|---|---|
| Pipeline 생성 | INFO | → `creating` | 없음 |
| 활성화 (`creating`→`active`) | INFO | → `active` | 없음 |
| `creating` 5분 초과 | ERROR | → `error` | CRITICAL |
| Pause / Resume / 삭제 (사용자) | INFO | → `paused` / `active` / (삭제) | 없음 |
| Consumer lag ≥ 5,000 (최초) | WARN | → `lag` | WARNING |
| Consumer lag ≥ 50,000 | ERROR | `lag` 유지 | CRITICAL |
| Consumer lag < 5,000 복구 | INFO | → `active` | 없음(복구 확인 권고) |
| Connector Task ≥ 1 FAILED 또는 PARTIALLY_FAILED | ERROR | → `error` | CRITICAL |
| Error Rate > 0.5% / > 2% | WARN / ERROR | – / → `error` | WARNING / CRITICAL |
| Connector 자동 재시작 1회 / 1시간 3회 | WARN / ERROR | – / → `error` | 없음 / CRITICAL |

> Consumer lag 행(`≥ 5,000`/`≥ 50,000`)은 Bifrost가 컨슈머를 소유하는 **CDC(JDBC Sink) consumer group**에만 적용한다. EDA(fan-out)는 Source Connector state로만 상태를 산정하고 외부 구독자 lag으로 인시던트를 만들지 않는다(B.1).

#### B.6.2 Database 이벤트 (ping 5초, lag 30초)
| 트리거 | 레벨 | DB 상태 | 인시던트 |
|---|---|---|---|
| DB 등록 / 연결 성공(복구) | INFO | → `healthy` | 없음 |
| DB 연결 실패 (3회 연속) | ERROR | → `error` | CRITICAL |
| Replication lag ≥ 1,000ms / ≥ 5,000ms | WARN / ERROR | → `warning` | WARNING / CRITICAL |
| Replication lag 복구 (< 1,000ms) | INFO | → `healthy` | 없음 |
| retainedWAL ≥ 500MB / ≥ 1GB | WARN / ERROR | `warning` 유지 | WARNING / CRITICAL |

#### B.6.3 Consumer Group 이벤트 (30초, lag는 Pipeline 이벤트와 공유)
| 트리거 | 레벨 | CG 상태 | 인시던트 |
|---|---|---|---|
| STABLE → REBALANCING / 복구 | INFO | → `REBALANCING` / `STABLE` | 없음 |
| REBALANCING 5분 이상 | WARN | 유지 | WARNING |
| STABLE → DEAD | ERROR | → `DEAD` | WARNING |
| 특정 파티션 member 미할당 + lag 증가 | WARN | – | WARNING |

#### B.6.4 Kafka Connect Worker 이벤트 (Jolokia JMX 60초)
| 트리거 | 레벨 | Worker 상태 | 인시던트 |
|---|---|---|---|
| Worker 응답 없음(30초) | ERROR | → `UNREACHABLE` | CRITICAL |
| JVM Heap ≥ 85% / ≥ 95% | WARN / ERROR | → `WARNING` | WARNING / CRITICAL |
| CPU ≥ 70% | WARN | → `WARNING` | WARNING |
| GC 누적(30초 윈도우) > 2초 / > 5초 | WARN / ERROR | → `WARNING` | WARNING / CRITICAL |
| 복구 (모든 지표 정상) | INFO | → `RUNNING` | 없음 |

#### B.6.5 사용자 액션 이벤트 (전부 INFO, 인시던트 없음)
파이프라인 생성/일시정지/재개/삭제, DB 등록, Connector 수동 재시작, 멤버 추가/삭제, Kafka User 추가, Secret 교체 등 — `[사용자명]이 …` 형식으로 기록.

### B.7 인시던트 자동 생성 및 그룹화 규칙
인시던트는 B.6에서 "인시던트"가 명시된 경우에만 자동 생성된다. 동일 근본 원인으로 판단되는 이벤트는 **하나의 인시던트로 그룹화**한다.

**이벤트-인시던트 연결 모델**
| 필드 | 위치 | 타입 | 설명 |
|---|---|---|---|
| `triggerEventId` | Incident | string | 인시던트를 최초 생성한 이벤트 ID(최초 감지) |
| `incidentId` | Event | string? | 이벤트가 연결된 인시던트 ID(없으면 null). **그룹 멤버십의 단일 출처** |

그룹에 묶인 이벤트는 별도 배열(`relatedEventIds`)로 중복 저장하지 않고 **`event.incidentId`로 역참조**해 구하며, 관련 이벤트 타임라인은 `occurredAt` 순으로 정렬하고 `triggerEventId`를 "최초 감지"로 강조한다. (배열은 `incidentId`와 같은 정보를 이중 저장해 불일치·무결성 문제가 있어 두지 않는다 — 데이터 모델 정합: [Spring Boot DETAILS §3.7](./design/backend-springboot/data-model.md#37-incident-fr-021-fr-026).)

**생성 조건**
| 이벤트 레벨 | 조건 | 결과 | 심각도 |
|---|---|---|---|
| ERROR | 즉시 | 인시던트 즉시 생성 | CRITICAL |
| WARN | 동일 리소스 30분 내 2건 이상 | 인시던트 생성 | WARNING |
| WARN | 단건 | 이벤트 로그만 | — |
| INFO | 항상 | 이벤트 로그만 | — |

**심각도 결정**: 관련 이벤트 중 ERROR 포함 → CRITICAL · 전부 WARN → WARNING · WARNING 인시던트에 ERROR 추가 → CRITICAL로 에스컬레이션.

**그룹화 키**: 동일 Source DB의 여러 Pipeline FAILED → Source DB ID · 동일 Worker의 여러 Connector 문제 → Worker ID · 동일 Consumer Group lag+REBALANCING → Consumer Group ID · 연쇄 Replication lag→Pipeline lag → Source DB ID.

**인시던트 타이틀**: 연결된 이벤트의 정제 메시지(원인 유형 + 대상, 예: `'orders-eda' 소스 커넥터 오류: DB 연결 실패 (호스트·포트·네트워크 확인)`)를 사용한다. 정제 원인을 특정할 수 없으면 상태 기반 fallback(`Pipeline '{name}' status {STATUS}`).

**복구 처리(recovery)**: 트리거 조건이 정상 복구되면 권고 메시지 표시(사용자가 직접 `RESOLVED`) · CRITICAL 인시던트는 자동 닫기 없음(사용자 확인 필수) · WARNING이고 복구 이벤트 발생 시 자동 `INVESTIGATING` + 사용자 복구 알림.

**인시던트 상태값**
| 상태값 | 정의 |
|---|---|
| `OPEN` | 자동 감지됨, 사용자 미확인 |
| `INVESTIGATING` | 사용자 확인·조치 중 |
| `RESOLVED` | 원인 해소 확인됨(사용자 수동 전이) |

**인시던트 심각도**: `WARNING` / `CRITICAL` 2단계. (에이전트 내부 분석은 동일 2단계를 사용하며, 정책 에스컬레이션 시에만 가산한다 — [catalog-policy-matrix.md §6 Severity 보정](./design/backend-fastapi/catalog/catalog-policy-matrix.md#6-severity-보정) 참조.)
