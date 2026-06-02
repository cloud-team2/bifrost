# ADR 0001: 도메인 모델 정렬

- 상태: Accepted
- 결정일: 2026-06-02
- 관련 이슈: #14

## Context

설계 문서(`design/todo.md`, `design/backend/springboot/DETAILS.md`)와 현재 레포의
스캐폴드(`services/core-service/src/main/resources/db/migration/V1~V3`, `persistence/entity/*`,
`libs/common-dto/`) 사이에 도메인 모델 격차가 있다.

| 항목 | 설계 문서 | 코드 |
| --- | --- | --- |
| 테넌트 | `workspace` | `tenants` |
| 사용자 | `app_user` | `users` |
| DB 등록 | `database` | `datasources` |
| pipeline source | `source_db_id` | `source_datasource_id` |
| pipeline sink | `sink_db_id` (`direct`일 때 필수) | 컬럼 없음 |
| pipeline pattern | `fan_out` / `direct` | `type` 컬럼, 기본값 `'CDC'` |
| 멤버 | `project_member` | 없음 |
| 이벤트 / 감사 / 인시던트 | `event`, `audit_event`, `incident` | 없음 |
| Connector 메타 | 별도 테이블 `connector` | 없음 (메타가 `pipelines` 컬럼에 인라인) |
| Flyway 교차 FK 규칙 | V1~V3 금지, V4 모음 | V2가 이미 V1 테이블 FK 선언 |

이대로 두면 권세빈(core)·정재환(database)·백강민(connector) 각자가 서로 다른 가정으로
마이그레이션을 작성해 fresh DB 마이그레이션이 깨지거나, `KafkaPipelineProvisioner` 계약과
Agent `/internal/ops` 경로의 용어가 어긋난다.

## Decision

기존 스캐폴드(V1~V3, entity, common-dto)를 유지하고 신규 도메인은 V4부터 add-only로 추가한다.
설계 용어와 코드 용어는 단일 매핑 표로 공존시킨다.

### 용어 매핑 (단일 출처)

| 설계 용어 | 코드 / DB 용어 |
| --- | --- |
| `workspace` | `tenant` |
| `workspace_id` | `tenant_id` |
| `app_user` | `user` |
| `database` | `datasource` |
| `database_id` | `datasource_id` |
| `source_db_id` | `source_datasource_id` |
| `sink_db_id` | `sink_datasource_id` (신규 추가) |

문서·DTO 이름·주석에 둘 다 등장할 경우 본 매핑을 따른다.

### Flyway 교차 FK 규칙

- 기존 V1~V3의 FK 선언은 유지한다.
- V4 이상에서 추가되는 cross-file FK는 별도 마지막 마이그레이션 파일(예: `V9__fk_cross_v4plus.sql`)에 모은다.
- 단일 파일 안에서 그 파일이 만든 테이블끼리의 FK는 허용한다.

## Consequences

### 영역별 가이드

- **core (권세빈)** — V1을 새로 만들지 않는다. 대신 `V4__add_member_event_audit_incident.sql`
  형태로 `project_member` / `event` / `audit_event` / `incident`를 add-only로 추가.
  pipeline의 `type` 컬럼 enum 확장(`FAN_OUT` / `DIRECT`)과 `sink_datasource_id` 컬럼 추가는
  별도 마이그레이션으로 분리.
- **database (정재환)** — 기존 `V2__create_datasources_pipelines.sql` 유지. `database` 등록 흐름은
  기존 `datasources` 테이블/엔티티에 추가. `SecretStore` 계약은 기존 `secret_ref` 컬럼 위에 그대로.
- **connector (백강민)** — `V3__create_discovered_services.sql` 유지. 신규 `connector` 테이블이
  필요하면 V5 또는 본인 영역 V4에 add-only로 추가. `KafkaPipelineProvisioner` 구현은
  `tenants` / `datasources` 스키마와 호환되도록 작성.
- **Agent (김연수)** — `/internal/ops` 요청·응답 DTO는 설계 용어(`workspace_id`, `database_id`)로
  발행하되 내부적으로 `tenant_id` / `datasource_id`로 변환. 매핑은 controller 레이어에서 흡수.

### Follow-up

- `tenants` → `workspace` 리네임은 v1 정식 출시 이후 별도 ADR로 분리 진행.
- `pipeline.type` 컬럼 `FAN_OUT` / `DIRECT` enum 확장 및 `sink_datasource_id` 컬럼 추가는 별도 이슈.
- `KafkaPipelineProvisioner` interface · request/response DTO 신설은 이슈 #17에서 진행.

## References

- 설계: `design/todo.md`
- 설계: `design/backend/springboot/DETAILS.md` §1, §2, §4
- 코드: `services/core-service/src/main/resources/db/migration/V1~V3`
- 코드: `libs/common-dto/src/main/java/com/platform/common/orchestrator/`
