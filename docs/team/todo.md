# TODO - Spring Boot 구현 배정 + Agent/마무리 역할

## 이번주 목표

- **목요일까지** Spring Boot에서 EDA/CDC 파이프라인 생성 흐름을 끝낸다.
- mock 기준 필수 흐름: workspace 생성 -> DB 등록 -> CDC readiness 조회 -> EDA/CDC pipeline 생성 -> `creating` -> `active` -> SSE/event/audit 기록.
- infra green이면 real 흐름까지 확인한다: EDA source insert -> topic 적재, CDC source insert/update -> sink 반영.
- **금요일은** 신규 구현보다 연동, 테스트, 버그 수정, 다음주 TODO 작성에 집중한다.

## 참조 문서

| 문서 | 이번주에 사용할 내용 |
| --- | --- |
| [기능명세서](../spec.md) | FR-001~005, FR-013~018, FR-019, FR-022/025/026, 부록 B 상태값 |
| [springboot/api.md](../api/springboot.md) | A.0 공통 응답, A.1 auth, A.2 workspace, A.3 database, A.4 pipeline, A.8 SSE, Part B `/internal/ops` |
| [springboot/](../design/backend-springboot/overview.md) | §2 Provisioning, §3 Database Registry, §4 Data Model, Evidence/Audit |
| [infra/DETAILS.md](../design/infra.md) | Kafka Connect 목표 구조, KafkaConnector, KafkaUser, 배포 순서 |
| [fastapi/](../design/backend-fastapi/overview.md) | Spring `/internal/ops` read tool 계약을 잡을 때만 참조 |

## 배정 원칙

| 담당 | 영역 | 소유 패키지/파일 |
| --- | --- | --- |
| 정재환 | **DB 쪽 전부** | `database`, `database/cdc`, `secret`, `V2__database.sql`, DB 관련 `/api/v1`와 Agent read 계약 |
| 백강민 | **Fabric8/Strimzi 쪽 전부** | `provisioning/impl`, `adapters/kafka`, `watcher`, `V3__connector.sql`, KafkaConnector/Watcher |
| 권세빈 | **나머지 Spring Boot 전부** | `global`(config·common), `auth`, `workspace`, `pipeline`, `provisioning` interface/mock/DTO, `monitoring`/`event`/`incident`, `governance`(policy·approval·changemanagement·idempotency·audit), `streaming`, `V1__core.sql`, status |
| 김연수 | **Agent 점검/마무리** | 목요일 Agent 로직 검토, 금요일 Agent 마무리 |
| 이성민 | **Spring Boot 검토/연동/마무리** | 금요일 Spring Boot PR/API/E2E 검토와 마무리 |

남의 패키지와 Flyway 파일은 직접 수정하지 않는다. 경계가 필요하면 interface/DTO를 먼저 합의하고 각자 영역에서 구현한다.

**Flyway 규칙(교차 FK)**: `V1__core.sql`/`V2__database.sql`/`V3__connector.sql`은 각자 자기 파일 안의 테이블만 만들고, **다른 파일의 테이블을 가리키는 FK 제약은 선언하지 않는다**(해당 컬럼은 `uuid`로만 둔다). 파일 간 교차 FK(`pipeline.source_db_id`/`sink_db_id` → `database`, `connector.pipeline_id` → `pipeline`)는 전부 마지막 공용 마이그레이션 `V4__fk_constraints.sql`(권세빈 소유)에 모아 V1~V3 이후에 건다. Flyway는 버전 번호 순으로 실행되므로 V1의 `pipeline`이 V2의 `database`를 FK로 참조하면 fresh DB에서 마이그레이션이 깨진다.
금요일에는 백강민이 없으므로 Fabric8/Strimzi 잔여 이슈와 real provisioner 연동 책임은 정재환이 인수한다.

## 요일별 산출물

### 화요일 - 계약과 스키마 먼저 고정

- 권세빈: Spring 공통 skeleton, 응답 envelope, error code, `V1__core.sql`, `KafkaPipelineProvisioner` interface/DTO/mock 초안.
- 정재환: `V2__database.sql`, `database` table, `secret_ref`, `SecretStore.resolve` signature를 먼저 고정하고 공유.
- 정재환: Agent 설계 검토 시작. Spring `/internal/ops` read tool 후보, service token/header, evidence 반환 규칙, FastAPI와 Spring 책임 경계를 초안으로 정리.
- 백강민: Fabric8/Strimzi dependency, KafkaConnector CRD 접근 확인, 수동 KafkaConnector apply PoC.

완료 기준:
- 권세빈/정재환/백강민이 서로 기다리지 않도록 pipeline DTO, DB id/secret_ref, connector naming의 초안이 나온다.
- Flyway 교차 FK 규칙 합의: 개별 V1~V3엔 교차 FK를 넣지 않고 `V4__fk_constraints.sql`(권세빈 소유)에 모은다.

### 수요일 - API와 real provisioner 골격

- 권세빈: auth/login/refresh, workspace 생성, pipeline 생성, pattern 검증, mock `creating -> active`, SSE `pipeline_status_changed`.
- 정재환: 인프라 마무리 우선 진행 후 DB API 구현. connection-test, DB 등록, schema 조회, cdc-readiness.
- 정재환: Agent 기반 다지기. `list_project_pipelines`, `get_pipeline_topology`, `get_connector_status`, `get_consumer_lag`, `search_logs`, `get_incident_summary`의 input/output과 error mapping 확정.
- 백강민: Source Debezium mapper, CDC Sink JDBC mapper, real provisioner skeleton, Watcher skeleton.

판정:
- 정재환이 infra `green/red`를 기록한다.
- green이면 목요일 real E2E 리허설, red면 목요일은 mock E2E를 확정한다.

### 목요일 - 파이프라인 생성 성공

- 권세빈: `PipelineStatusService` 단일 writer 확정, event/audit/SSE 연결, 생성 응답 DTO 마감.
- 정재환: DB API 누락 정리, source/sink seed data와 smoke command 준비, Agent read tool용 Spring 내부 API 계약 정리.
- 백강민: mock/real provisioner 스왑, KafkaConnector 생성, Watcher -> `PipelineStatusService` 연결, 금요일 부재 전 잔여 이슈/운영 방법을 정재환에게 인계.
- 김연수: Agent 쪽 로직에 문제 있는지 확인. Tool Client Registry, State, diagnose-only workflow, SSE progress event, Spring `/internal/ops` client 경계가 설계와 맞는지 검토하고 이슈를 정리.
- 김연수: **루프 가드 중앙 집행 검증** — 전역 step(`MAX_STEPS`=24)·revision·fail·gap·scope·revise_action 상한이 `run` namespace 카운터로 Supervisor 한 곳에서 집행되는지, 어떤 분기도 가드 검사를 우회하지 않는지 확인([fastapi DETAILS §15.5.1](../design/backend-fastapi/contracts.md#51-루프-방지와-종료-보장)).
- 김연수: **fail 경로 캡 확인** — `Verifier fail → Planner`가 `MAX_FAIL_LOOPS`로 막히는지(과거 무제한 되돌림 여부) 점검.
- 김연수: **latency 보강 검토** — Retrieval 독립 read tool 병렬 실행, 부분 결과 스트리밍(`report_preview_available`/`partial_result`), stage별 timeout이 설계대로 들어가는지 확인([fastapi DETAILS §15.4.2](../design/backend-fastapi/contracts.md#42-지연-최소화latency-원칙)).

완료 기준:
- mock 기준 EDA/CDC 생성 E2E 성공.
- infra green이면 real 기준 EDA topic 적재와 CDC sink 반영까지 확인.
- Agent 로직 검토 결과가 이슈 목록 또는 수정 TODO로 남아 있다.

### 금요일 - 연동, 테스트, Agent/Spring Boot 마무리, 다음주 TODO

- 권세빈/정재환: EDA/CDC 생성 버그 수정, API/SSE 계약 불일치 정리, mock/real config 정리.
- 정재환: 백강민 부재분 책임. Fabric8/Strimzi, KafkaConnector, Watcher, real provisioner 잔여 이슈를 인수해 연동/테스트를 마무리한다.
- 김연수: Agent 쪽 마무리 작업. diagnose-only 흐름, read tool 연동, SSE progress event, Spring client error handling, ready/capabilities를 정리. Retrieval 병렬화·부분 결과 스트리밍·루프 가드(fail 캡 포함) 반영 마무리.
- 이성민: Spring Boot 쪽 검토 및 연동 + 마무리 작업. PR 리뷰, API 계약 대조, mock/real E2E 스크립트, 시연 체크리스트, 다음주 TODO 작성.

## 정재환 - DB 쪽 작업 전체

참조:
- [기능명세서](../spec.md): FR-013~018, 부록 B.3
- [springboot/api.md](../api/springboot.md): A.3 Database, Part B 공통 response/error
- [springboot/](../design/backend-springboot/overview.md): §3 Database Registry, §4 Data Model의 `database`

작업:
- [ ] `V2__database.sql` 작성 (교차 FK 미선언 — `database` 테이블만 생성, `pipeline`→`database` FK는 권세빈 `V4__fk_constraints.sql`로 위임)
- [ ] `database` table 작성: workspace/project scope, name, engine, host, port, db_name, username, `secret_ref`, timestamps
- [ ] credential 원문 저장 금지 처리
- [ ] `SecretStore` interface 작성
- [ ] `SecretStore` mock 작성
- [ ] `SecretStore.resolve` 입력/출력 shape 확정
- [ ] `secret_ref` naming 규칙 확정 후 권세빈/백강민에게 공유
- [ ] DB 등록 DTO/request validation 작성
- [ ] DB 목록 API: `GET /api/v1/workspaces/{wsId}/databases?role=&engine=&q=`
- [ ] DB 연결 테스트 API: `POST /api/v1/workspaces/{wsId}/databases/connection-test`
- [ ] 연결 테스트는 HikariCP `SELECT 1`, timeout 5s 기준
- [ ] 연결 오류 분류: timeout, auth failed, network, db not found, unknown
- [ ] DB 등록 API: `POST /api/v1/workspaces/{wsId}/databases`
- [ ] DB 상세 API: `GET /api/v1/workspaces/{wsId}/databases/{dbId}`
- [ ] DB schema 조회 API: `GET /api/v1/workspaces/{wsId}/databases/{dbId}/schema`
- [ ] schema 응답: table, column, type, nullable, pk/index 여부
- [ ] CDC readiness API: `GET /api/v1/workspaces/{wsId}/databases/{dbId}/cdc-readiness`
- [ ] PostgreSQL readiness: `wal_level`, replication 권한, slot 생성 가능 여부, publication 가능 여부
- [ ] MariaDB readiness: binlog enabled, row format, server_id, replication 권한
- [ ] DB metrics API는 이번주 최소 stub 또는 mock 응답으로 계약만 맞춤
- [ ] DB pipelines API는 pipeline table 연동 기준으로 구현 또는 stub
- [ ] DB 관련 controller/service/repository 테스트
- [ ] Agent read tool 계약 정리: DB/pipeline 조회에 필요한 `/internal/ops` 입력/출력, error mapping, service token header
- [ ] 화요일 Agent 설계 검토: FastAPI와 Spring 책임 경계, credential 금지, `/internal/ops` read tool 후보 정리
- [ ] 수요일 Agent 기반 다지기: read tool input/output, error mapping, evidence reference 반환 규칙, ready dependency 정리
- [ ] 인프라 마무리 지원: KafkaConnect `platform-connect`, source/sink DB, metadb/RBAC, smoke command, 수요일 green/red 판정

완료 기준:
- `database` schema와 `SecretStore.resolve` 계약이 먼저 공유되어 권세빈/백강민이 막히지 않는다.
- connection-test, DB 등록, schema, cdc-readiness가 동작한다.
- secret 원문이 DB/로그/응답에 남지 않는다.

## 백강민 - Fabric8/Strimzi 쪽 작업 전체

참조:
- [기능명세서](../spec.md): FR-004, FR-005, FR-008, 부록 B.2
- [springboot/api.md](../api/springboot.md): A.4 Pipeline, Part B §14 Kafka Connect, §17 Strimzi
- [springboot/](../design/backend-springboot/overview.md): §2 Provisioning
- [infra/DETAILS.md](../design/infra.md): Kafka Connect 목표 구조

작업:
- [ ] `V3__connector.sql` 작성 (교차 FK 미선언 — `connector` 테이블만 생성, `connector`→`pipeline` FK는 권세빈 `V4__fk_constraints.sql`로 위임)
- [ ] `connector` table 또는 connector metadata migration 작성
- [ ] Fabric8 client 설정
- [ ] Strimzi KafkaConnector CRD 접근 설정
- [ ] KafkaConnector CR 생성 DTO/mapper 작성
- [ ] connector naming 규칙 확정: project_key, dbName, schema, table 포함
- [ ] 권세빈의 `KafkaPipelineProvisioner` interface real 구현 작성
- [ ] EDA fan_out 생성 구현: Source Debezium connector 1개 생성
- [ ] Source connector `tasksMax=1`
- [ ] CDC direct 생성 구현: Source Debezium connector + Sink JDBC connector 생성
- [ ] Sink connector upsert 설정
- [ ] Sink connector `tasksMax=3`까지 허용
- [ ] 정재환의 `SecretStore.resolve` 결과 사용
- [ ] workspace KafkaUser credential 이름 규칙 적용
- [ ] Secret 원문 로그/응답 금지
- [ ] KafkaConnector 생성 실패 처리
- [ ] Topic/Secret/Connector 단계별 실패 원인 코드 구분
- [ ] KafkaConnector Watcher 구현
- [ ] Watcher 상태 매핑: RUNNING -> active, FAILED -> error, 일부 task FAILED -> lag 또는 partial failure event
- [ ] Watcher는 pipeline row를 직접 수정하지 않고 권세빈의 `PipelineStatusService` 호출
- [ ] real/mock provisioner 설정 스왑 가능하게 구성
- [ ] Fabric8/Watcher 테스트 또는 최소 smoke 테스트

완료 기준:
- EDA 생성 요청이 Source KafkaConnector CR 생성까지 이어진다.
- CDC 생성 요청이 Source/Sink KafkaConnector CR 생성까지 이어진다.
- Watcher가 connector 상태를 `PipelineStatusService`로 전달한다.

## 권세빈 - 나머지 Spring Boot 작업 전체

참조:
- [기능명세서](../spec.md): FR-001~005, FR-019~021, 부록 B.1/B.5/B.6
- [springboot/api.md](../api/springboot.md): A.0, A.1, A.2, A.4, A.6, A.7, A.8, Part B 공통 규칙
- [springboot/](../design/backend-springboot/overview.md): §1 Server Design, §2 Provisioning interface, §4 Data Model

작업:
- [ ] Spring package skeleton 정리
- [ ] build.gradle 의존성 머지 관리
- [ ] 공통 응답 envelope 구현: `{ok, request_id, data}` / `{ok, request_id, error}`
- [ ] 표준 error code 구조 구현
- [ ] request_id 생성/전파
- [ ] global exception handler
- [ ] auth/login API
- [ ] auth/refresh API
- [ ] 데모 계정 seed
- [ ] JWT 발급/검증 필터
- [ ] workspace 목록 API
- [ ] workspace 생성 API
- [ ] workspace 상세 API
- [ ] `project_key` slug 생성
- [ ] workspace 생성자 `project_member` 등록
- [ ] KafkaUser/ACL 트리거 호출부 interface 또는 stub
- [ ] `V1__core.sql` 작성: app_user, workspace, project_member, pipeline, event, audit_event, incident 등 core table (교차 FK 미선언 — `pipeline.source_db_id`/`sink_db_id`는 `uuid` 컬럼만)
- [ ] `V4__fk_constraints.sql` 작성: 파일 간 교차 FK(`pipeline`→`database`, `connector`→`pipeline`)를 V1~V3 이후에 추가
- [ ] pipeline 목록 API: `GET /api/v1/workspaces/{wsId}/pipelines?status=`
- [ ] pipeline 생성 API: `POST /api/v1/workspaces/{wsId}/pipelines`
- [ ] pipeline 상세 API
- [ ] pipeline pause API
- [ ] pipeline resume API
- [ ] pipeline delete API
- [ ] pattern 검증: `fan_out`은 sink 없음, `direct`는 sink 필수
- [ ] sourceDbId/sinkDbId workspace ownership 검증
- [ ] duplicate pipeline name 검증
- [ ] `KafkaPipelineProvisioner` interface 작성
- [ ] provisioner request/response DTO 작성
- [ ] mock provisioner 작성
- [ ] mock 기준 `creating -> active` 상태 전이
- [ ] `PipelineStatusService` 작성
- [ ] pipeline 상태 변경 단일 writer 보장
- [ ] 상태 변경 시 event 기록
- [ ] 상태 변경 시 audit_event 기록
- [ ] SSE publisher 작성
- [ ] `GET /api/v1/workspaces/{wsId}/events/stream`
- [ ] SSE event: `pipeline_status_changed`
- [ ] SSE event: `connector_state_changed`
- [ ] 이벤트 로그 API: `GET /api/v1/workspaces/{wsId}/events?level=&pipelineId=`
- [ ] 운영 overview API는 이번주 최소 stub/mock 응답
- [ ] incident 목록/상세 API는 이번주 최소 stub/mock 응답
- [ ] pipeline 상세 탭 API는 이번주 생성 E2E에 필요한 범위만 stub/mock 응답
- [ ] 내부 운영 API 공통 header/response/error 구조 skeleton
- [ ] `/internal/ops/health`, `/internal/ops/ready`, `/internal/ops/version`
- [ ] `/internal/ops/projects/{project_id}/pipelines`
- [ ] `/internal/ops/projects/{project_id}/pipelines/{pipeline_id}`
- [ ] `/internal/ops/projects/{project_id}/kafka/connectors/{connector_name}/status`는 백강민 구현과 연결 또는 stub
- [ ] `/internal/ops/projects/{project_id}/incidents/{incident_id}/summary`는 stub
- [ ] controller/service 테스트
- [ ] mock E2E 테스트: workspace -> DB 등록 -> pipeline 생성 -> active -> SSE/event/audit

완료 기준:
- mock 기준 EDA/CDC pipeline 생성 E2E가 성공한다.
- 상태 변경은 `PipelineStatusService`만 통해 발생한다.
- 백강민이 real provisioner를 붙일 interface/DTO가 고정되어 있다.
- 정재환 DB API와 pipeline 생성 검증이 연결되어 있다.

## 최종 체크리스트

- [ ] 정재환: DB schema/SecretStore 계약 공유
- [ ] 정재환: 화/수 Agent 설계 검토와 기반 다지기 완료
- [ ] 정재환: DB 등록/connection-test/schema/cdc-readiness 완료
- [ ] 정재환: infra green/red 판정
- [ ] 정재환: 금요일 백강민 부재분 Fabric8/real provisioner 잔여 책임 인수
- [ ] 백강민: real provisioner 구현
- [ ] 백강민: Watcher -> PipelineStatusService 연결
- [ ] 백강민: 목요일까지 Fabric8/Strimzi 잔여 이슈를 정재환에게 인계
- [ ] 권세빈: auth/workspace/pipeline/mock/SSE/event/audit 완료
- [ ] 김연수: 목요일 Agent 로직 문제 검토 완료
- [ ] 김연수: 루프 가드 중앙 집행 + fail 경로 캡 검증 완료
- [ ] 김연수: Retrieval 병렬화 + 부분 결과 스트리밍 + stage timeout 반영 확인
- [ ] 김연수: 금요일 Agent 마무리 완료
- [ ] 이성민: 금요일 Spring Boot 검토/연동/마무리 완료
- [ ] EDA mock 생성 E2E 성공
- [ ] CDC mock 생성 E2E 성공
- [ ] infra green이면 EDA real topic 적재 확인
- [ ] infra green이면 CDC real sink 반영 확인
- [ ] 금요일 통합 테스트/시연 절차 작성
- [ ] 다음주 TODO 작성
