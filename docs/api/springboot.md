# Spring Boot API Reference

> 대상 서비스: `services/operations-backend`
> 기본 prefix: `/api/v1`
> Swagger UI: `/swagger-ui.html`, OpenAPI JSON: `/v3/api-docs`

이 문서는 프론트엔드가 호출하는 플랫폼 API 중 account/workspace/members/settings/monitoring 표면을 상세 정리하고, 나머지 Spring `@RestController` 표면은 controller family 수준으로 빠짐없이 표시한다.
런타임 계약의 정본은 Spring controller/DTO와 런타임 OpenAPI 산출물(`/v3/api-docs`)이며, 이 문서는 사람이 읽는 수동 카탈로그다.

수동 카탈로그와 자동 산출물의 경계: 이 파일은 권한, 상태 코드, 주요 DTO field, 운영상 주의점처럼 사람이 검토해야 하는 계약을 유지한다. 모든 endpoint의 전체 schema는 controller `@Operation`과 `/v3/api-docs` 산출물을 기준으로 확인한다. endpoint 추가 시 먼저 controller/DTO를 바꾸고, 이 문서에는 노출 범위와 사람이 해석해야 하는 정책만 동기화한다.

## 공통 규칙

- 인증: `Authorization: Bearer <accessToken>`
- 성공 응답: controller DTO를 그대로 반환한다.
- 실패 응답: [error-codes.md](./error-codes.md)의 `ErrorResponse` envelope을 따른다.
- 레거시 alias `/api/auth/**`는 v1 controller가 없다. Security matcher는 legacy login만 permitAll, legacy refresh/me는 authenticated로 둔다. 따라서 `/api/auth/login`은 handler가 없어 404, `/api/auth/me`·`/api/auth/refresh`는 Bearer 없이 401이고 유효 Bearer가 있으면 handler가 없어 404, `/api/auth/register`는 Bearer 없이 401이다(`SecurityPaths`, `AuthControllerTest` 기준).
- 워크스페이스 범위 API의 `{wsId}`는 `workspaceId` UUID다.

## Auth / Account

정본 근거: `AuthController` base path와 status는 `services/operations-backend/src/main/java/com/bifrost/ops/auth/controller/AuthController.java:25-67`, DTO field는 `services/operations-backend/src/main/java/com/bifrost/ops/auth/dto/RegisterRequest.java:8-16`, `services/operations-backend/src/main/java/com/bifrost/ops/auth/dto/LoginRequest.java:6-8`, `services/operations-backend/src/main/java/com/bifrost/ops/auth/dto/AuthTokensResponse.java:5-10`, `services/operations-backend/src/main/java/com/bifrost/ops/auth/dto/MeResponse.java:8-19`.

| Method | Path | Auth | Status | Request | Response | 설명 |
| --- | --- | --- | --- | --- | --- | --- |
| `POST` | `/api/v1/auth/register` | no | `201` | `email`, `name`, `password`, `workspaceName`, `namespace` | `accessToken`, `tokenType`, `expiresInSeconds`, `userId`, `workspaceId` | 사용자와 최초 워크스페이스 생성, access token 발급 |
| `POST` | `/api/v1/auth/login` | no | `200` | `email`, `password` | `accessToken`, `tokenType`, `expiresInSeconds`, `userId`, `workspaceId` | 이메일/비밀번호 로그인 |
| `POST` | `/api/v1/auth/refresh` | yes | `200` | 없음 | `accessToken`, `tokenType`, `expiresInSeconds`, `userId`, `workspaceId` | 현재 Bearer token 주체에게 새 token 발급 |
| `GET` | `/api/v1/auth/me` | yes | `200` | 없음 | `MeResponse` | 내 계정과 현재 워크스페이스 컨텍스트 조회 |

`GET /api/v1/auth/me` 응답 필드(`services/operations-backend/src/main/java/com/bifrost/ops/auth/dto/MeResponse.java:8-19`):

| 필드 | 설명 |
| --- | --- |
| `userId`, `email`, `name` | 사용자 식별자와 표시 정보 |
| `role` | 현재 워크스페이스에서의 `OWNER`/`ADMIN`/`MEMBER` |
| `joinedAt`, `lastLoginAt` | 멤버 가입 시각, 최근 로그인 시각 |
| `workspaceId`, `workspaceName`, `namespace`, `workspaceStatus` | 현재 워크스페이스 정보. `namespace`는 workspace API의 `projectKey`와 같은 슬러그다. |

## Workspace

정본 근거: `services/operations-backend/src/main/java/com/bifrost/ops/workspace/controller/WorkspaceController.java:36-82`, `services/operations-backend/src/main/java/com/bifrost/ops/workspace/dto/WorkspaceCreateRequest.java:9-10`, `services/operations-backend/src/main/java/com/bifrost/ops/workspace/dto/WorkspaceUpdateRequest.java:5-8`, `services/operations-backend/src/main/java/com/bifrost/ops/workspace/dto/WorkspaceResponse.java:14-23`.

| Method | Path | Auth | 권한 | Status | Request | Response | 설명 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `GET` | `/api/v1/workspaces` | yes | 소속 사용자 | `200` | 없음 | `WorkspaceResponse[]` | 내 워크스페이스 목록 |
| `POST` | `/api/v1/workspaces` | yes | 인증 사용자 | `201` | `name` | `WorkspaceResponse` | 워크스페이스 생성, 생성자를 OWNER로 등록 |
| `GET` | `/api/v1/workspaces/{wsId}` | yes | 소속 사용자 | `200` | 없음 | `WorkspaceResponse` | 워크스페이스 상세 |
| `PATCH` | `/api/v1/workspaces/{wsId}` | yes | OWNER/ADMIN | `200` | `name`, `timezone` | `WorkspaceResponse` | `name`, `timezone` 수정 |

`WorkspaceResponse` field:

| 필드 | 설명 |
| --- | --- |
| `id`, `name`, `projectKey`, `timezone`, `status`, `createdAt` | 워크스페이스 식별자, 표시명, 슬러그, timezone, 상태, 생성 시각 |
| `pipelineCount`, `activePipelineCount` | 목록/상세 카드용 파이프라인 집계 |

`PATCH /api/v1/workspaces/{wsId}` request:

```json
{
  "name": "Platform Team",
  "timezone": "Asia/Seoul"
}
```

- `name`이 null이면 이름을 유지한다.
- `timezone`이 blank이면 `null`로 저장한다.

## Members

정본 근거: `services/operations-backend/src/main/java/com/bifrost/ops/workspace/controller/WorkspaceController.java:84-119`, `services/operations-backend/src/main/java/com/bifrost/ops/workspace/dto/ProjectMemberAddRequest.java:8-10`, `services/operations-backend/src/main/java/com/bifrost/ops/workspace/dto/ProjectMemberUpdateRequest.java:6-8`, `services/operations-backend/src/main/java/com/bifrost/ops/workspace/dto/ProjectMemberResponse.java:8-14`, `services/operations-backend/src/main/java/com/bifrost/ops/workspace/service/ProjectMemberService.java:56-118`.

| Method | Path | Auth | 권한 | Status | Request | Response | 설명 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `GET` | `/api/v1/workspaces/{wsId}/members` | yes | 소속 사용자 | `200` | 없음 | `ProjectMemberResponse[]` | 멤버 목록 조회 |
| `POST` | `/api/v1/workspaces/{wsId}/members` | yes | OWNER/ADMIN | `201` | `email`, `role` | `ProjectMemberResponse` | 이메일로 멤버 추가 |
| `PATCH` | `/api/v1/workspaces/{wsId}/members/{userId}` | yes | OWNER/ADMIN | `200` | `role` | `ProjectMemberResponse` | 멤버 역할 변경 |
| `DELETE` | `/api/v1/workspaces/{wsId}/members/{userId}` | yes | OWNER/ADMIN | `204` | 없음 | 없음 | 멤버 제거 |

`ProjectMemberResponse` field:

| 필드 | 설명 |
| --- | --- |
| `workspaceId`, `userId`, `email`, `role`, `joinedAt` | 멤버가 속한 워크스페이스, 사용자, 이메일, 역할, 가입 시각 |

역할 규칙:

| 역할 | 멤버 목록 GET | 멤버 추가/수정/삭제 | 설정 수정 |
| --- | --- | --- | --- |
| `OWNER` | 가능 | 가능 | 가능 |
| `ADMIN` | 가능 | 가능 | 가능 |
| `MEMBER` | 가능 | 불가 (`WORKSPACE_FORBIDDEN`) | 불가 (`WORKSPACE_FORBIDDEN`) |

OWNER 정책의 현재 코드 사실:

- `POST` 멤버 추가에서 `role=OWNER` 요청은 `ADMIN`으로 저장된다(`services/operations-backend/src/main/java/com/bifrost/ops/workspace/service/ProjectMemberService.java:56-66`).
- `PATCH` 역할 변경은 기존 `OWNER`를 non-OWNER로 바꾸는 요청만 막는다(`services/operations-backend/src/main/java/com/bifrost/ops/workspace/service/ProjectMemberService.java:70-78`).
- 기존 `ADMIN`/`MEMBER` 대상에 `role=OWNER`를 보내면 현재 코드는 요청 역할을 그대로 저장한다(`services/operations-backend/src/main/java/com/bifrost/ops/workspace/service/ProjectMemberService.java:70-79`). 본 PR은 docs-only이므로 이 동작을 문서화하고, OWNER 이관 차단/승인 흐름은 별도 코드 PR에서 다룬다.
- `DELETE`는 기존 `OWNER` 삭제를 막는다(`services/operations-backend/src/main/java/com/bifrost/ops/workspace/service/ProjectMemberService.java:84-92`).

## Workspace Settings

정본 근거: `services/operations-backend/src/main/java/com/bifrost/ops/workspace/controller/WorkspaceSettingsController.java:27-93`, request/response DTO는 `services/operations-backend/src/main/java/com/bifrost/ops/workspace/dto/NotificationSettingsRequest.java:7-12`, `services/operations-backend/src/main/java/com/bifrost/ops/workspace/dto/NotificationSettingsResponse.java:7-19`, `services/operations-backend/src/main/java/com/bifrost/ops/workspace/dto/ThresholdSettingsRequest.java:5-8`, `services/operations-backend/src/main/java/com/bifrost/ops/workspace/dto/ThresholdSettingsResponse.java:5-10`, `services/operations-backend/src/main/java/com/bifrost/ops/workspace/dto/AiPolicySettingsRequest.java:5-9`, `services/operations-backend/src/main/java/com/bifrost/ops/workspace/dto/AiPolicySettingsResponse.java:5-15`. nullable partial update 동작은 `services/operations-backend/src/main/java/com/bifrost/ops/workspace/service/WorkspaceSettingsService.java:55-121`.

| Method | Path | Auth | 권한 | Status | Request | Response | 설명 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `GET` | `/api/v1/workspaces/{wsId}/settings/notifications` | yes | 소속 사용자 | `200` | 없음 | `slackEnabled`, `slackWebhookUrl`, `emailRecipients`, `severity` | Slack/email 알림 설정 조회 |
| `PUT` | `/api/v1/workspaces/{wsId}/settings/notifications` | yes | OWNER/ADMIN | `200` | nullable `slackEnabled`, `slackWebhookUrl`, `emailRecipients`, `severity` | 동일 | 알림 설정 수정 |
| `GET` | `/api/v1/workspaces/{wsId}/settings/thresholds` | yes | 소속 사용자 | `200` | 없음 | `warning`, `critical` | lag warning/critical 임계값 조회 |
| `PUT` | `/api/v1/workspaces/{wsId}/settings/thresholds` | yes | OWNER/ADMIN | `200` | nullable `warning`, `critical` | 동일 | 임계값 수정 |
| `GET` | `/api/v1/workspaces/{wsId}/settings/ai-policy` | yes | 소속 사용자 | `200` | 없음 | `autonomous`, `approvalWaitMinutes`, `prodLock` | AI 자동복구 정책 조회 |
| `PUT` | `/api/v1/workspaces/{wsId}/settings/ai-policy` | yes | OWNER/ADMIN | `200` | nullable `autonomous`, `approvalWaitMinutes`, `prodLock` | 동일 | AI 자동복구 정책 수정 |

설정 도메인:

- notifications: `slackEnabled`, `slackWebhookUrl`, `emailRecipients`, `severity`
- thresholds: `warning`, `critical`
- ai-policy: `autonomous`, `approvalWaitMinutes`, `prodLock`
- request alias: `slackWebhook` -> `slackWebhookUrl`, `severityPolicy` -> `severity`, `lagWarningThreshold` -> `warning`, `lagCriticalThreshold` -> `critical`, `aiAutonomous` -> `autonomous`, `aiApprovalWaitMinutes` -> `approvalWaitMinutes`, `aiProdLock` -> `prodLock`.
- nullable partial update: `slackEnabled`, `emailRecipients`, `severity`, `warning`, `critical`, `autonomous`, `approvalWaitMinutes`, `prodLock`이 `null`이면 기존 설정을 유지한다. 예외적으로 `slackWebhookUrl`은 요청값을 항상 `normalizeNullable()`에 통과시켜 `null`/blank를 `null`로 저장하며, Slack enabled 상태에서는 `https://hooks.slack.com/services/...` 형식이어야 한다(`services/operations-backend/src/main/java/com/bifrost/ops/workspace/service/WorkspaceSettingsService.java:61-68`, `services/operations-backend/src/main/java/com/bifrost/ops/workspace/service/WorkspaceSettingsService.java:89-120`, `services/operations-backend/src/main/java/com/bifrost/ops/workspace/service/WorkspaceSettingsService.java:151-168`, `services/operations-backend/src/main/java/com/bifrost/ops/workspace/service/WorkspaceSettingsService.java:201-207`).
- DB column은 `severity_policy`이고 API field는 `severity`다(`services/operations-backend/src/main/resources/db/migration/V12__workspace_settings.sql:7`, `services/operations-backend/src/main/java/com/bifrost/ops/workspace/persistence/entity/WorkspaceSettingsEntity.java:39-40`, `services/operations-backend/src/main/java/com/bifrost/ops/workspace/dto/NotificationSettingsRequest.java:11`, `services/operations-backend/src/main/java/com/bifrost/ops/workspace/dto/NotificationSettingsResponse.java:11`).

## Pipeline Runtime Metadata / Kafka Secret

정본 근거: route는 `services/operations-backend/src/main/java/com/bifrost/ops/pipeline/controller/PipelineController.java:130-144`, `services/operations-backend/src/main/java/com/bifrost/ops/workspace/kafka/KafkaPrincipalController.java:64-70`; DTO field는 `services/operations-backend/src/main/java/com/bifrost/ops/pipeline/dto/ConnectionGuideResponse.java:12-41`, `services/operations-backend/src/main/java/com/bifrost/ops/pipeline/dto/TableMappingResponse.java:7-17`, `services/operations-backend/src/main/java/com/bifrost/ops/workspace/kafka/dto/KafkaPrincipalSecretResponse.java:13-23`.

| Method | Path | Auth | 권한 | Status | Query | Response | 설명 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `GET` | `/api/v1/workspaces/{wsId}/pipelines/{id}/connection-guide` | yes | `WorkspaceAccessGuard.requireAccess` | `200` | 없음 | `ConnectionGuideResponse` | Kafka consumer 연결용 bootstrap, group id, 인증 템플릿, topic 목록 |
| `GET` | `/api/v1/workspaces/{wsId}/pipelines/{id}/table-mapping` | yes | `WorkspaceAccessGuard.requireAccess` | `200` | 없음 | `TableMappingResponse` | KafkaConnector config 기준 source table → topic → sink table 매핑 |
| `GET` | `/api/v1/workspaces/{wsId}/kafka/principals/{id}/secret` | yes | OWNER/ADMIN·owner | `200` | 없음 | `KafkaPrincipalSecretResponse` | Strimzi KafkaUser Secret **레퍼런스/마스킹 조회** (requireManager, 원문 미반환) |

`ConnectionGuideResponse` field:

| 필드 | 설명 |
| --- | --- |
| `pipelineId`, `pipelineName` | 대상 파이프라인 |
| `bootstrapServers` | Strimzi Kafka CR `status.listeners[].bootstrapServers` 우선, 없으면 Kafka CR listener service 이름으로 계산하고 최종 fallback은 `spring.kafka.bootstrap-servers`(`services/operations-backend/src/main/java/com/bifrost/ops/pipeline/runtime/PipelineRuntimeMetadataService.java:208-233`) |
| `recommendedGroupId` | `bifrost.{workspace.namespace}.{pipelineId}` 형식(`services/operations-backend/src/main/java/com/bifrost/ops/pipeline/runtime/PipelineRuntimeMetadataService.java:133-135`) |
| `authenticationMethod`, `authenticationTemplates` | SCRAM-SHA-512/mTLS client property 템플릿. template은 placeholder와 Secret key ref만 포함하며 raw secret은 포함하지 않는다(`services/operations-backend/src/main/java/com/bifrost/ops/pipeline/runtime/PipelineRuntimeMetadataService.java:187-205`) |
| `credentialReference` | `ConnectorNaming.kafkaUserName(workspace.namespace)`로 계산한 KafkaUser Secret 이름과 key 목록/참조(`services/operations-backend/src/main/java/com/bifrost/ops/pipeline/runtime/PipelineRuntimeMetadataService.java:96-99`, `services/operations-backend/src/main/java/com/bifrost/ops/pipeline/runtime/PipelineRuntimeMetadataService.java:158-179`) |
| `topics` | pipeline topic과 KafkaConnector config에서 추출한 topic 목록(`services/operations-backend/src/main/java/com/bifrost/ops/pipeline/runtime/PipelineRuntimeMetadataService.java:137-149`) |

`TableMappingResponse` 정책:

- 인가와 pipeline 소속 검증은 `loadPipeline()`에서 `WorkspaceAccessGuard.requireAccess` 후 `pipelineRepository.findByIdAndTenantId`로 수행한다(`services/operations-backend/src/main/java/com/bifrost/ops/pipeline/runtime/PipelineRuntimeMetadataService.java:122-125`).
- source/sink connector 이름은 pipeline row의 `sourceConnectorName`/`sinkConnectorName`을 우선 사용하고, sink 이름이 없으면 connector 메타 행 또는 결정적 이름(`{pipelineId}-sink`)으로 fallback한다(`services/operations-backend/src/main/java/com/bifrost/ops/pipeline/runtime/PipelineRuntimeMetadataService.java:331-357`).
- config source는 Kubernetes `KafkaConnector` CR `spec.config` 우선, 없으면 Kafka Connect REST `/connectors/{name}/config` fallback이다(`services/operations-backend/src/main/java/com/bifrost/ops/pipeline/runtime/PipelineRuntimeMetadataService.java:360-395`).
- mapping은 Debezium `table.include.list`, `topic.prefix`, sink `topics`, `table.name.format`/route transform에서 추출한다. config가 없거나 table 정보가 없으면 `mappings: []`로 반환하고 에러 처리하지 않는다(`services/operations-backend/src/main/java/com/bifrost/ops/pipeline/runtime/PipelineRuntimeMetadataService.java:112-120`, `services/operations-backend/src/main/java/com/bifrost/ops/pipeline/runtime/PipelineRuntimeMetadataService.java:300-328`).
- connector config에는 DB password가 있을 수 있으므로 API는 config 원문을 반환하지 않는다(`services/operations-backend/src/main/java/com/bifrost/ops/pipeline/runtime/PipelineRuntimeMetadataService.java:44-49`).

`KafkaPrincipalSecretResponse` 보안 정책:

- 인가: 기존 `KafkaPrincipalService.requireManager` 패턴과 동일하게 OWNER/ADMIN 또는 workspace owner만 허용한다. 실패는 `WORKSPACE_FORBIDDEN`이다(`services/operations-backend/src/main/java/com/bifrost/ops/workspace/kafka/KafkaPrincipalService.java:197-209`).
- principal은 workspace에 속해야 하며 없으면 `KAFKA_PRINCIPAL_NOT_FOUND` 404다(`services/operations-backend/src/main/java/com/bifrost/ops/workspace/kafka/KafkaPrincipalService.java:184-187`).
- revoked/inactive principal은 Secret 조회를 거부한다. 현재 정책은 `ACTIVE`만 허용하고 그 외는 `WORKSPACE_FORBIDDEN`이다(`services/operations-backend/src/main/java/com/bifrost/ops/workspace/kafka/KafkaPrincipalService.java:133-136`, `services/operations-backend/src/main/java/com/bifrost/ops/workspace/kafka/KafkaPrincipalService.java:228-231`).
- Secret 이름은 `TenantProvisioner`/`ConnectorNaming.kafkaUserName(workspace.namespace)` 규칙과 principal username이 일치해야 하며, 불일치 또는 Secret/key 없음은 404로 처리한다(`services/operations-backend/src/main/java/com/bifrost/ops/workspace/kafka/KafkaPrincipalService.java:137-164`).
- **플랫폼 정책(server.md: secret 원문 read deny)에 따라 raw password는 절대 반환하지 않는다.** 응답(`KafkaPrincipalSecretResponse`) 필드는 `principalId`, `username`, `status`, `namespace`, `secretName`, `availableKeys`(존재 key 목록), `passwordMasked="********"`, `retrievedAt`, `exposurePolicy="MASKED_REFERENCE_ONLY"`이며, **secret material(자격증명 원문)은 어떤 필드로도 제공하지 않는다.** 실제 자격증명은 워크로드가 K8s Secret을 직접 마운트해 사용한다.
- 무결성 가드: principal이 ACTIVE가 아니면 `WORKSPACE_FORBIDDEN`, TenantProvisioner 네이밍(secretName==principal username) 불일치·Secret/password key 부재·Secret 내부 username alias 불일치 시 `KAFKA_PRINCIPAL_NOT_FOUND`(fail-closed).
- 감사: 조회 성공 시 `AuditService.record(..., "KAFKA_PRINCIPAL_SECRET_VIEW", ...)`와 `OpsLog`를 남기되 detail/log에는 username·secretName만 포함하고 raw password는 어디에도 기록하지 않는다.

## Monitoring

정본 근거: `MonitoringController` base path/status/인가 호출은 `services/operations-backend/src/main/java/com/bifrost/ops/monitoring/controller/MonitoringController.java:24-76`(4개 handler 모두 `accessGuard.requireAccess`: `services/operations-backend/src/main/java/com/bifrost/ops/monitoring/controller/MonitoringController.java:45`, `services/operations-backend/src/main/java/com/bifrost/ops/monitoring/controller/MonitoringController.java:54`, `services/operations-backend/src/main/java/com/bifrost/ops/monitoring/controller/MonitoringController.java:64`, `services/operations-backend/src/main/java/com/bifrost/ops/monitoring/controller/MonitoringController.java:74`), `OverviewResponse` field는 `services/operations-backend/src/main/java/com/bifrost/ops/monitoring/dto/OverviewResponse.java:4-13`, `ResourceEventResponse` field는 `services/operations-backend/src/main/java/com/bifrost/ops/monitoring/dto/ResourceEventResponse.java:6-11`, `IncidentResponse` field는 `services/operations-backend/src/main/java/com/bifrost/ops/incident/dto/IncidentResponse.java:8-20`, 권한 검사는 `WorkspaceAccessGuard.requireAccess`(`services/operations-backend/src/main/java/com/bifrost/ops/workspace/WorkspaceAccessGuard.java:39-54`)다.

| Method | Path | Auth | 권한 | Status | Request | Response | 설명 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `GET` | `/api/v1/workspaces/{wsId}/monitoring/overview` | yes | `requireAccess` | `200` | 없음 | `OverviewResponse` | 워크스페이스 전체 health/운영 집계 |
| `GET` | `/api/v1/workspaces/{wsId}/monitoring/resource-events` | yes | `requireAccess` | `200` | 없음 | `ResourceEventResponse[]` | Kafka AdminClient 기반 partition reassignment 등 리소스 이벤트 목록 |
| `GET` | `/api/v1/workspaces/{wsId}/monitoring/incidents` | yes | `requireAccess` | `200` | query `status`(optional) | `IncidentResponse[]` | incident 목록. `status`가 있으면 repository가 해당 문자열로 필터링 |
| `GET` | `/api/v1/workspaces/{wsId}/monitoring/incidents/{incidentId}` | yes | `requireAccess` | `200` | path `incidentId` UUID | `IncidentResponse` | incident 상세 |

권한/실패 상태:

- `requireAccess`는 관리 권한을 요구하지 않고, `wsId == principal.tenantId()` home 워크스페이스 fast-path, `project_member` 소속, 또는 `tenants.owner_user_id` 소유자 중 하나면 허용한다(`services/operations-backend/src/main/java/com/bifrost/ops/workspace/WorkspaceAccessGuard.java:39-54`).
- principal이 없으면 `UNAUTHENTICATED` -> `401`, 위 접근 허용 조건에 모두 맞지 않으면 `WORKSPACE_FORBIDDEN` -> `403`이다.
- incident 상세가 없거나 `{wsId}`와 incident tenant가 다르면 `RESOURCE_NOT_FOUND` -> `404`다.
- `resource-events`는 Kafka AdminClient 조회 실패를 debug log로 무시하고 빈 배열을 반환할 수 있다(`MonitoringReadService.resourceEvents`).

`OverviewResponse` field:

| 필드 | 설명 |
| --- | --- |
| `totalPipelines`, `runningPipelines`, `errorPipelines` | 워크스페이스 파이프라인 전체/ACTIVE/ERROR 개수 |
| `healthyDatabases`, `unreachableDatabases` | DB health probe 결과가 `HEALTHY`/`UNREACHABLE`인 datasource 개수 |
| `openIncidents` | `status=OPEN` incident 개수 |
| `totalConnectors`, `failedConnectors` | connector 전체 개수와 `FAILED`/`PARTIALLY_FAILED` 상태 개수 |

`IncidentResponse` field:

| 필드 | 설명 |
| --- | --- |
| `id`, `tenantId` | incident UUID, 워크스페이스 UUID |
| `groupingKey` | event 임계 위반을 묶는 key(source DB, worker, consumer group 등) |
| `severity`, `status`, `title` | 심각도 문자열, 상태 문자열(`OPEN`/`RESOLVED`), 제목 |
| `rca` | RCA 요약(nullable) |
| `sourceType`, `sourceId` | 원인 리소스 유형/UUID(nullable) |
| `openedAt`, `resolvedAt` | open/resolve 시각. `resolvedAt`은 open 상태에서 nullable |

`ResourceEventResponse` field:

| 필드 | 설명 |
| --- | --- |
| `eventType` | 리소스 이벤트 유형(현재 partition reassignment 등) |
| `resource` | topic-partition 등 이벤트 대상 |
| `detail` | replicas/addingReplicas 같은 이벤트 상세 문자열 |
| `occurredAt` | 이벤트 관측 시각 |

## Controller Coverage

이 파일은 account/workspace/members/settings/monitoring/runtime metadata schema를 상세 관리하고, 아래 18개 `@RestController`는 endpoint family 수준으로 커버한다. `@RestControllerAdvice`인 `GlobalExceptionHandler`는 controller coverage 카운트에서 제외한다.

| Controller | Base path | 상세 수준 | 근거 |
| --- | --- | --- | --- |
| `AuthController` | `/api/v1/auth` | 상세 schema/status | `services/operations-backend/src/main/java/com/bifrost/ops/auth/controller/AuthController.java:25-67` |
| `WorkspaceController` | `/api/v1/workspaces` | 상세 schema/status | `services/operations-backend/src/main/java/com/bifrost/ops/workspace/controller/WorkspaceController.java:36-119` |
| `WorkspaceSettingsController` | `/api/v1/workspaces/{wsId}/settings` | 상세 schema/status | `services/operations-backend/src/main/java/com/bifrost/ops/workspace/controller/WorkspaceSettingsController.java:27-93` |
| `EventController` | `/api/v1/workspaces/{wsId}/events` | family catalog | `services/operations-backend/src/main/java/com/bifrost/ops/event/controller/EventController.java:23-41` |
| `SseController` | `/api/v1/workspaces/{wsId}/events/stream` | family catalog | `services/operations-backend/src/main/java/com/bifrost/ops/streaming/SseController.java:22-39` |
| `DatabaseController` | `/api/v1/workspaces/{wsId}/databases` | family catalog | `services/operations-backend/src/main/java/com/bifrost/ops/database/controller/DatabaseController.java:41-152` |
| `PipelineController` | `/api/v1/workspaces/{wsId}/pipelines` | family catalog + runtime metadata 상세 | `services/operations-backend/src/main/java/com/bifrost/ops/pipeline/controller/PipelineController.java:43-218` |
| `KafkaPrincipalController` | `/api/v1/workspaces/{wsId}/kafka/principals` | family catalog + secret 상세 | `services/operations-backend/src/main/java/com/bifrost/ops/workspace/kafka/KafkaPrincipalController.java:21-70` |
| `MonitoringController` | `/api/v1/workspaces/{wsId}/monitoring` | 상세 schema/status | `services/operations-backend/src/main/java/com/bifrost/ops/monitoring/controller/MonitoringController.java:24-76` |
| `ClusterController` | `/api/v1/clusters` | family catalog | `services/operations-backend/src/main/java/com/bifrost/ops/cluster/ClusterController.java:17-44` |
| `InternalController` | `/internal` | family catalog | `services/operations-backend/src/main/java/com/bifrost/ops/internalops/controller/InternalController.java:26-88` |
| `InternalOpsController` | `/internal/ops` | family catalog | `services/operations-backend/src/main/java/com/bifrost/ops/internalops/controller/InternalOpsController.java:24-72` |
| `InternalOpsObservabilityController` | `/internal/ops` | family catalog | `services/operations-backend/src/main/java/com/bifrost/ops/internalops/controller/InternalOpsObservabilityController.java:52-212` |
| `InternalOpsPipelineController` | `/internal/ops/projects/{projectId}/pipelines` | family catalog | `services/operations-backend/src/main/java/com/bifrost/ops/internalops/controller/InternalOpsPipelineController.java:30-90` |
| `InternalOpsMutationController` | `/internal/ops/projects/{projectId}` | mutation 상세 | `services/operations-backend/src/main/java/com/bifrost/ops/internalops/controller/InternalOpsMutationController.java:52-454` |
| `ApprovalController` | `/internal/ops/approvals` | governance 상세 | `services/operations-backend/src/main/java/com/bifrost/ops/governance/approval/controller/ApprovalController.java:52-331` |
| `ChangeTicketController` | `/internal/ops/change-tickets` | governance 상세 | `services/operations-backend/src/main/java/com/bifrost/ops/governance/changemanagement/controller/ChangeTicketController.java:40-168` |
| `KafkaConnectorPocController` | `/internal/poc/kafka-connectors` | WIP/PoC family catalog | `services/operations-backend/src/main/java/com/bifrost/ops/internalops/poc/KafkaConnectorPocController.java:23-55` |

Family catalog 요약:

| Family | Endpoint/status 요약 | 권한/인증 요약 |
| --- | --- | --- |
| Events/SSE | `GET /events` -> 200, `GET /events/stream` -> SSE 200 | workspace access guard |
| Database | connection-test 200, register 201, list/get/schema/readiness/metrics/pipelines 200, delete 204 | workspace access guard |
| Pipeline | list/get/read tabs/connection-guide/table-mapping/pause/resume 200, create 201, delete 204 | workspace access guard |
| Kafka principals | list/secret 200, create 201, deactivate/revoke/rotate 200 | list은 workspace access, mutation/secret은 OWNER/ADMIN 또는 workspace owner |
| Monitoring | overview/resource-events/incidents list/detail 200 | `WorkspaceAccessGuard.requireAccess` |
| Cluster | Kafka/connect/throughput 조회 200 | 인증 사용자, workspace scope 없음 |
| Internal `/internal` | tenant provision 200, tenant delete 202, pipeline provision 202 또는 422, status 200, delete 202 | internal control-plane surface |
| Internal `/internal/ops` read | health/version/tool-catalog 200, ready 200 또는 503, connector status/list/topology/lag/log search/list_alerts/traces/incident summary 200 | `SecurityConfig`상 `/internal/ops/**` permitAll, caller identity 보강은 별도 코드 범위 |
| Internal `/internal/ops` mutation | connector restart/pause/resume, Kafka Connect consumer group restart: 성공 200, gate 실패 400/403/409, Connect 실패 502/504 | controller가 `X-Agent-Run-Id`·`X-Agent-Step-Id`·`X-Idempotency-Key`·`X-Approval-Id`를 검증하고 `OpsEnvelope`로 응답 |
| Internal governance | approvals create 201/list/get/decision/validate 200, change-ticket create 201/get/validate 200 | approval decision은 Spring Security principal이 필요하고, create/list/get/validate는 `/internal/ops/**` permitAll 경로에 있다 |
| PoC | connector list/get/sample 200, get/delete not found 404, delete accepted 202 | 임시 PoC surface, 제거 또는 내부망 제한 필요 |

## 권한 매트릭스

| API family | Security layer | 서비스/도메인 권한 |
| --- | --- | --- |
| `/api/v1/auth/register`, `/api/v1/auth/login` | permitAll | 없음 |
| `/api/v1/auth/refresh`, `/api/v1/auth/me` | authenticated | 현재 principal |
| `/api/v1/workspaces` list/create | authenticated | list는 사용자 소속 목록, create는 인증 사용자 |
| `/api/v1/workspaces/{wsId}` read/list 계열 | authenticated | `WorkspaceAccessGuard.requireAccess`: home tenant, `project_member`, 또는 workspace owner |
| `/api/v1/workspaces/{wsId}` settings/member mutation | authenticated | OWNER/ADMIN. 일부 owner fast-path는 각 service가 별도 확인 |
| `/api/v1/workspaces/{wsId}/databases/**` | authenticated | controller가 모든 handler에서 `requireAccess` 호출. DB 등록/삭제도 현재 OWNER/ADMIN이 아니라 workspace access 기준 |
| `/api/v1/workspaces/{wsId}/pipelines/**` | authenticated | controller/service가 workspace access와 pipeline 소속을 확인 |
| `/api/v1/workspaces/{wsId}/monitoring/**` | authenticated | `MonitoringController` 4개 handler 모두 `requireAccess` |
| `/api/v1/workspaces/{wsId}/kafka/principals` list | authenticated | workspace access |
| `/api/v1/workspaces/{wsId}/kafka/principals` create/deactivate/revoke/rotate/secret | authenticated | OWNER/ADMIN 또는 workspace owner. Secret 조회는 principal `ACTIVE`와 Secret 네이밍/키 무결성까지 확인 |
| `/api/v1/clusters/**` | authenticated | workspace scope 없음 |
| `/internal/ops/**` | permitAll | 대부분 controller-level contract만 적용. mutation은 agent headers/idempotency/approval/ownership을 코드에서 검증 |
| `/internal/ops/approvals/{id}/decision` | permitAll path + method-level principal check | `SecurityContext`의 `AuthenticatedUser`가 `tenantId`/`decidedBy`와 일치하고 approval actor와 같아야 함 |

## Alias 제거

`/api/auth/register`, `/api/auth/login`, `/api/auth/me`, `/api/auth/refresh`는 v1 controller 계약이 아니다. 클라이언트는 반드시 `/api/v1/auth/**`를 사용한다. 현재 security matcher상 legacy login만 permitAll 404 경로이고, legacy refresh/me/register는 인증 matcher를 먼저 거친다.

## 3. Common Headers

내부 운영 API(`/internal/ops/**`)는 agent 호출 추적을 위해 다음 헤더를 사용한다. `AgentHeaders`가 request id로 인정하는 이름은 `X-Agent-Request-Id`와 `X-Request-Id`이고, 둘 다 없거나 unsafe하면 서버가 UUID를 생성한다.

- request id: `X-Agent-Request-Id` 또는 `X-Request-Id`
- `X-Agent-Run-Id`
- `X-Agent-Step-Id`
- `X-Agent-Name`
- `X-Agent-Id`
- `X-Actor-Type`
- `X-Actor-Id`
- mutation 계열: `X-Idempotency-Key`, `X-Approval-Id`

현재 `InternalOpsMutationController`가 필수로 검사하는 헤더는 `X-Agent-Run-Id`, `X-Agent-Step-Id`, `X-Idempotency-Key`다. `X-Approval-Id`가 없으면 mutation은 403 `APPROVAL_REQUIRED`로 차단된다. `X-Agent-Name`, `X-Actor-Type`, `X-Actor-Id`는 FastAPI `ToolContext`가 전송하지만 Spring mutation controller의 필수 헤더 검증 대상은 아니다.

## 4. Internal Ops Success Envelope

`/internal/ops/**` 성공 응답은 다음 envelope을 기준으로 한다.

```json
{
  "ok": true,
  "request_id": "req-1",
  "operation": "list_project_pipelines",
  "result": {},
  "evidence": []
}
```

## 5. Internal Ops Error Envelope

내부 운영 실패 응답은 `ok=false`와 error object를 사용한다. 문자열 error code catalog는 [error-codes.md](./error-codes.md#internal-ops-문자열-코드)를 따른다.

```json
{
  "ok": false,
  "request_id": "req-1",
  "operation": "get_connector_status",
  "error": {
    "code": "RESOURCE_NOT_OWNED_BY_PROJECT",
    "message": "resource is not owned by project",
    "retryable": false,
    "required_action": "check_project_scope"
  }
}
```

필드명 정본은 `OpsEnvelope`/`OpsError`의 Jackson 직렬화다. `request_id`, `audit_event_id`, `required_action`은 snake_case로 내려간다. `OpsEnvelope`는 `@JsonInclude(NON_NULL)`이므로 `audit_event_id`, `result`, `error` 같은 null field는 JSON에서 생략되고, `evidence`는 현재 `List<String>` 기본값 `[]`다.

## 6. Internal Ops Read / Governance / Mutation API

### 6.1 Read tool catalog

`GET /internal/ops/admin/tool-catalog`는 구현된 read tool allowlist를 반환한다. 현재 catalog에는 mutation이 포함되지 않는다(`InternalOpsToolCatalogTest`도 `restart_connector`가 catalog에 없음을 검증한다).

| Operation | Method | Path | Result |
| --- | --- | --- | --- |
| `get_consumer_lag` | `GET` | `/internal/ops/projects/{projectId}/kafka/consumer-groups/{consumerGroup}/lag` | `consumerGroup`, `totalLag`, `source` |
| `search_logs` | `POST` | `/internal/ops/projects/{projectId}/observability/logs/search` | `logs`, `total`, `note` |
| `query_traces` | `GET` | `/internal/ops/projects/{projectId}/connectors/{connectorName}/traces` | `connector`, `traces`, optional `note` |
| `list_alerts` | `GET` | `/internal/ops/projects/{projectId}/observability/alerts` | `alerts`, `summary` |
| `get_incident_summary` | `GET` | `/internal/ops/incidents/{incidentId}/summary` | `incidentId`, `status`, `note` |
| `list_project_pipelines` | `GET` | `/internal/ops/projects/{projectId}/pipelines` | `PipelineResponse[]` |
| `get_pipeline_topology` | `GET` | `/internal/ops/projects/{projectId}/pipelines/{pipelineId}/topology` | `PipelineTopologyResult` |
| `get_connector_status` | `GET` | `/internal/ops/projects/{projectId}/kafka/connectors/{connectorName}/status` | `PipelineProvisionStatus` |

`/internal/ops/projects/{projectId}`의 `{projectId}`는 대부분 controller에서 workspace `namespace` slug로 해석한다. `list_alerts`만 UUID 문자열이면 workspace id로 먼저 조회하고, 실패하거나 UUID가 아니면 namespace slug로 조회한다. `list_alerts`는 별도 alert 테이블이 아니라 Spring `incidents` row를 agent alert view로 투영한다. Query는 `status`, `severity`, `limit`; `limit` 기본 50, 내부 최대 200이며 200 초과 값은 400이 아니라 200으로 cap된다. 프로젝트 없음은 HTTP 404 + `OpsEnvelope.error(code=RESOURCE_NOT_FOUND)`, non-integer 또는 `<= 0` limit은 HTTP 400 + `VALIDATION_FAILED`다.

### 6.2 Approval facade

| Method | Path | Status | Request | Result |
| --- | --- | --- | --- | --- |
| `POST` | `/internal/ops/approvals` | `201` | `tenantId`, `toolName`, `paramsHash`(64 hex), `requiredApprover`, `expiresInMinutes`(1..1440) | `approvalId`, `tenantId`, `actor`, `operation`, `status`, `expiresAt`, `createdAt`, `paramsHash` |
| `POST` | `/internal/ops/approvals/{approvalId}/decision` | `200` | `decision`(`approved`/`rejected`), `tenantId`, `decidedBy`, `comment` | `approvalId`, `tenantId`, `actor`, `operation`, `status`, `expiresAt`, `usedAt`, `createdAt` |
| `POST` | `/internal/ops/approvals/{approvalId}/validate` | `200` | `tenantId`, `paramsHash` | `approvalId`, `status="validated"`, `usedAt` |
| `GET` | `/internal/ops/approvals/{approvalId}?tenantId=` | `200` | query `tenantId` | `ApprovalResult` |
| `GET` | `/internal/ops/approvals?tenantId=&status=&actorId=&limit=` | `200` | `tenantId` required, `status` default `PENDING`, `limit` 1..500 | `ApprovalResult[]` |

Decision endpoint만 `SecurityContext`의 `AuthenticatedUser`를 요구한다. `tenantId`가 principal tenant와 다르거나 `decidedBy`가 principal user id와 다르거나 approval actor와 다르면 `APPROVAL_SCOPE_MISMATCH`다. Facade `validate` endpoint는 controller에서 `tenantId` 소속을 조회한 뒤 `ApprovalValidator.validateAndConsume(approvalId, paramsHash)`를 호출하므로 operation scope 검증은 생략하고 params hash·만료·single-use를 검증해 `usedAt`을 기록한다. Mutation controller는 별도 overload로 tenant·operation·params hash를 모두 검증한다.

### 6.3 Change ticket facade

| Method | Path | Status | Request | Result |
| --- | --- | --- | --- | --- |
| `POST` | `/internal/ops/change-tickets` | `201` | `tenantId`, `title` 또는 alias `toolName` | `changeTicketId`, `tenantId`, `title`, `status`, `createdAt` |
| `POST` | `/internal/ops/change-tickets/{changeTicketId}/validate` | `200` | `tenantId` | `changeTicketId`, `status="validated"`, `valid=true` |
| `GET` | `/internal/ops/change-tickets/{changeTicketId}?tenantId=` | `200` | query `tenantId` | `ChangeTicketResult` |

현재 Spring change-ticket 구현은 자체 `change_ticket` row를 만들고 status `OPEN`을 외부 응답 `pending`으로 노출한다. 실행 window, rollback plan, impact analysis, scope 검증은 현재 controller/entity 표면에 없다. `ChangeTicketValidator`는 `tenantId` 소속과 status `OPEN`만 검증한다.

### 6.4 Mutation endpoints

| Operation | Method | Path | Result |
| --- | --- | --- | --- |
| `restart_connector` | `POST` | `/internal/ops/projects/{projectId}/connectors/{connectorName}/restart` | `connector_name`, `action`, `status`, `message` |
| `pause_connector` | `POST` | `/internal/ops/projects/{projectId}/connectors/{connectorName}/pause` | `connector_name`, `action`, `status`, `message` |
| `resume_connector` | `POST` | `/internal/ops/projects/{projectId}/connectors/{connectorName}/resume` | `connector_name`, `action`, `status`, `message` |
| `restart_consumer_group` | `POST` | `/internal/ops/projects/{projectId}/kafka/consumer-groups/{consumerGroup}/restart` | `consumer_group`, `action`, `status`, `message` |

Mutation 처리 순서:

1. `X-Agent-Run-Id`, `X-Agent-Step-Id`, `X-Idempotency-Key` 누락 검사. 누락 시 HTTP 400 + `VALIDATION_FAILED`.
2. `{projectId}` workspace namespace 조회와 connector/pipeline ownership 확인. workspace 없음은 `RESOURCE_NOT_FOUND`, 소유 불일치는 `RESOURCE_NOT_OWNED_BY_PROJECT`. `restart_consumer_group`은 이 단계에서 지원 consumer group(`connect-` prefix)·존재 여부도 먼저 검증한다.
3. `X-Approval-Id` 검사. 없으면 HTTP 403 + `APPROVAL_REQUIRED`.
4. `IdempotencyGuard.check(idempotencyKey, tenantId, operation, paramsHash)`.
5. `ApprovalValidator.validateAndConsume(approvalId, tenantId, operation, paramsHash)`.
6. Kafka Connect REST mutation 실행.
7. 성공/실패 response snapshot을 idempotency row에 저장.

Idempotency 결과:

| 상황 | HTTP / code | 비고 |
| --- | --- | --- |
| 새 key | 계속 진행 | status `PROCESSING` row 생성 |
| 같은 key + 같은 operation/params + 완료 | 이전 result/error replay | cached JSON parse가 성공하면 원래 result status를 그대로 반환한다. `IDEMPOTENCY_REPLAY`는 fallback result를 구성할 때만 사용된다 |
| 같은 key 실행 중 | `409 CONFLICT` | `idempotency key is already processing` |
| 같은 key + 다른 operation/params | `409 CONFLICT` | approval 검증 전 차단 |
| replay approval id가 요청 `X-Approval-Id`와 다름 | `403 APPROVAL_SCOPE_MISMATCH` | cached result 재사용 차단 |

Kafka Connect REST timeout은 HTTP 504 + `TIMEOUT`, 그 외 Connect REST 실패는 HTTP 502 + `UPSTREAM_UNAVAILABLE`로 `OpsEnvelope.error`에 저장되고 replay된다. `restart_consumer_group`은 `connect-` prefix의 Kafka Connect-managed sink connector consumer group만 지원하며, 미지원/미존재 group은 `CONSUMER_GROUP_NOT_FOUND` 또는 `UPSTREAM_UNAVAILABLE`로 반환된다.

## Workspace Event Stream

`GET /api/v1/workspaces/{wsId}/events/stream`은 workspace SSE 채널이다. Browser `EventSource` 제약 때문에 Bearer header 대신 `access_token` query parameter를 사용할 수 있다. 이 query token 허용은 Spring JWT filter의 workspace SSE 경로 판정(`services/operations-backend/src/main/java/com/bifrost/ops/auth/jwt/JwtAuthenticationFilter.java:65-85`)에 의해 적용된다. FastAPI Agent run SSE route 자체의 auth 상태는 [FastAPI Event Streaming API](./fastapi.md#7-event-streaming-api)를 따른다.

## 18. Schema Registry API

Schema Registry 연동은 v1 필수 경로가 아니다. 도입 시 Spring Boot가 compatibility 상태와 schema 변화 조회를 노출하고, FastAPI RCA catalog가 이를 evidence로 참조한다.

## 19. Approval API

Spring Boot에는 [§6.2](#62-approval-facade)의 approval facade와 mutation 실행 전 `ApprovalValidator` 검증 표면이 구현되어 있다. 다만 현재 FastAPI approval decision route는 local approval-link repository만 갱신하고 executor가 Spring mutation 호출에 `X-Approval-Id`를 전달하지 않으므로, FastAPI 실행 경로와 Spring single-use 검증은 연결되어 있지 않다.

## 24. Report Support API

현재 Spring Boot에는 report support controller route가 없다. `MonitoringController`는 incident list/detail GET만 제공하고 `IncidentResponse` field는 `rca`다. `IncidentService.updateRca()` 내부 메서드는 `rca`만 저장하며, controller로 매핑된 RCA write route, severity 보정, report reference 기록 route는 없다.

## 25. Admin API

`GET /internal/ops/admin/tool-catalog`는 구현된 read tool catalog를 반환한다. 현재 반환 항목은 [§6.1](#61-read-tool-catalog)의 8개 read operation이며, mutation endpoint는 존재하지만 이 catalog에는 포함되지 않는다.
