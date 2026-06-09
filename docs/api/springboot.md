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
- 레거시 alias `/api/auth/**`는 v1 controller가 없다. `SecurityConfig`는 해당 경로를 permitAll로 통과시키지만 handler가 없어 404 `RESOURCE_NOT_FOUND` envelope으로 거부된다(`services/operations-backend/src/main/java/com/bifrost/ops/auth/security/SecurityConfig.java:37-49`, `services/operations-backend/src/test/java/com/bifrost/ops/auth/controller/AuthControllerTest.java:46-63`).
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

정본 근거: route는 `services/operations-backend/src/main/java/com/bifrost/ops/pipeline/controller/PipelineController.java:130-144`, `services/operations-backend/src/main/java/com/bifrost/ops/workspace/kafka/KafkaPrincipalController.java:65-70`; DTO field는 `services/operations-backend/src/main/java/com/bifrost/ops/pipeline/dto/ConnectionGuideResponse.java:12-41`, `services/operations-backend/src/main/java/com/bifrost/ops/pipeline/dto/TableMappingResponse.java:7-17`, `services/operations-backend/src/main/java/com/bifrost/ops/workspace/kafka/dto/KafkaPrincipalSecretResponse.java:12-30`.

| Method | Path | Auth | 권한 | Status | Query | Response | 설명 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `GET` | `/api/v1/workspaces/{wsId}/pipelines/{id}/connection-guide` | yes | `WorkspaceAccessGuard.requireAccess` | `200` | 없음 | `ConnectionGuideResponse` | Kafka consumer 연결용 bootstrap, group id, 인증 템플릿, topic 목록 |
| `GET` | `/api/v1/workspaces/{wsId}/pipelines/{id}/table-mapping` | yes | `WorkspaceAccessGuard.requireAccess` | `200` | 없음 | `TableMappingResponse` | KafkaConnector config 기준 source table → topic → sink table 매핑 |
| `GET` | `/api/v1/workspaces/{wsId}/kafka/principals/{id}/secret` | yes | OWNER/ADMIN·owner | `200` | 없음 | `KafkaPrincipalSecretResponse` | Strimzi KafkaUser Secret **레퍼런스/마스킹 조회** (requireManager, 원문 미반환) |

`ConnectionGuideResponse` field:

| 필드 | 설명 |
| --- | --- |
| `pipelineId`, `pipelineName` | 대상 파이프라인 |
| `bootstrapServers` | Strimzi Kafka CR `status.listeners[].bootstrapServers` 우선, 없으면 Kafka CR listener service 이름으로 계산하고 최종 fallback은 `spring.kafka.bootstrap-servers`(`services/operations-backend/src/main/java/com/bifrost/ops/pipeline/runtime/PipelineRuntimeMetadataService.java:208-230`) |
| `recommendedGroupId` | `bifrost.{workspace.namespace}.{pipelineId}` 형식(`services/operations-backend/src/main/java/com/bifrost/ops/pipeline/runtime/PipelineRuntimeMetadataService.java:133-135`) |
| `authenticationMethod`, `authenticationTemplates` | SCRAM-SHA-512/mTLS client property 템플릿. template은 placeholder와 Secret key ref만 포함하며 raw secret은 포함하지 않는다(`services/operations-backend/src/main/java/com/bifrost/ops/pipeline/runtime/PipelineRuntimeMetadataService.java:187-205`) |
| `credentialReference` | `ConnectorNaming.kafkaUserName(workspace.namespace)`로 계산한 KafkaUser Secret 이름과 key 목록/참조(`services/operations-backend/src/main/java/com/bifrost/ops/pipeline/runtime/PipelineRuntimeMetadataService.java:96-99`, `services/operations-backend/src/main/java/com/bifrost/ops/pipeline/runtime/PipelineRuntimeMetadataService.java:158-179`) |
| `topics` | pipeline topic과 KafkaConnector config에서 추출한 topic 목록(`services/operations-backend/src/main/java/com/bifrost/ops/pipeline/runtime/PipelineRuntimeMetadataService.java:137-149`) |

`TableMappingResponse` 정책:

- 인가와 pipeline 소속 검증은 `loadPipeline()`에서 `WorkspaceAccessGuard.requireAccess` 후 `pipelineRepository.findByIdAndTenantId`로 수행한다(`services/operations-backend/src/main/java/com/bifrost/ops/pipeline/runtime/PipelineRuntimeMetadataService.java:122-125`).
- source/sink connector 이름은 pipeline row의 `sourceConnectorName`/`sinkConnectorName`을 우선 사용하고, sink 이름이 없으면 connector 메타 행 또는 결정적 이름(`{pipelineId}-sink`)으로 fallback한다(`services/operations-backend/src/main/java/com/bifrost/ops/pipeline/runtime/PipelineRuntimeMetadataService.java:331-349`).
- config source는 Kubernetes `KafkaConnector` CR `spec.config` 우선, 없으면 Kafka Connect REST `/connectors/{name}/config` fallback이다(`services/operations-backend/src/main/java/com/bifrost/ops/pipeline/runtime/PipelineRuntimeMetadataService.java:351-390`).
- mapping은 Debezium `table.include.list`, `topic.prefix`, sink `topics`, `table.name.format`/route transform에서 추출한다. config가 없거나 table 정보가 없으면 `mappings: []`로 반환하고 에러 처리하지 않는다(`services/operations-backend/src/main/java/com/bifrost/ops/pipeline/runtime/PipelineRuntimeMetadataService.java:112-120`, `services/operations-backend/src/main/java/com/bifrost/ops/pipeline/runtime/PipelineRuntimeMetadataService.java:300-328`).
- connector config에는 DB password가 있을 수 있으므로 API는 config 원문을 반환하지 않는다(`services/operations-backend/src/main/java/com/bifrost/ops/pipeline/runtime/PipelineRuntimeMetadataService.java:44-49`).

`KafkaPrincipalSecretResponse` 보안 정책:

- 인가: 기존 `KafkaPrincipalService.requireManager` 패턴과 동일하게 OWNER/ADMIN 또는 workspace owner만 허용한다. 실패는 `WORKSPACE_FORBIDDEN`이다(`services/operations-backend/src/main/java/com/bifrost/ops/workspace/kafka/KafkaPrincipalService.java:130-131`, `services/operations-backend/src/main/java/com/bifrost/ops/workspace/kafka/KafkaPrincipalService.java:194-207`).
- principal은 workspace에 속해야 하며 없으면 `KAFKA_PRINCIPAL_NOT_FOUND` 404다(`services/operations-backend/src/main/java/com/bifrost/ops/workspace/kafka/KafkaPrincipalService.java:181-185`).
- revoked/inactive principal은 Secret 조회를 거부한다. 현재 정책은 `ACTIVE`만 허용하고 그 외는 `WORKSPACE_FORBIDDEN`이다(`services/operations-backend/src/main/java/com/bifrost/ops/workspace/kafka/KafkaPrincipalService.java:132-133`, `services/operations-backend/src/main/java/com/bifrost/ops/workspace/kafka/KafkaPrincipalService.java:225-230`).
- Secret 이름은 `TenantProvisioner`/`ConnectorNaming.kafkaUserName(workspace.namespace)` 규칙과 principal username이 일치해야 하며, 불일치 또는 Secret/key 없음은 404로 처리한다(`services/operations-backend/src/main/java/com/bifrost/ops/workspace/kafka/KafkaPrincipalService.java:134-156`).
- **플랫폼 정책(server.md: secret 원문 read deny)에 따라 raw password는 절대 반환하지 않는다.** 응답(`KafkaPrincipalSecretResponse`) 필드는 `principalId`, `username`, `status`, `namespace`, `secretName`, `availableKeys`(존재 key 목록), `passwordMasked="********"`, `retrievedAt`, `exposurePolicy="MASKED_REFERENCE_ONLY"`이며, **secret material(자격증명 원문)은 어떤 필드로도 제공하지 않는다.** 실제 자격증명은 워크로드가 K8s Secret을 직접 마운트해 사용한다.
- 무결성 가드: principal이 ACTIVE가 아니면 `WORKSPACE_FORBIDDEN`, TenantProvisioner 네이밍(secretName==principal username) 불일치·Secret/password key 부재·Secret 내부 username alias 불일치 시 `KAFKA_PRINCIPAL_NOT_FOUND`(fail-closed).
- 감사: 조회 성공 시 `AuditService.record(..., "KAFKA_PRINCIPAL_SECRET_VIEW", ...)`와 `OpsLog`를 남기되 detail/log에는 username·secretName만 포함하고 raw password는 어디에도 기록하지 않는다.

## Monitoring

정본 근거: `MonitoringController` base path/status/인가 호출은 `services/operations-backend/src/main/java/com/bifrost/ops/monitoring/controller/MonitoringController.java:24-76`, `OverviewResponse` field는 `services/operations-backend/src/main/java/com/bifrost/ops/monitoring/dto/OverviewResponse.java:4-13`, `ResourceEventResponse` field는 `services/operations-backend/src/main/java/com/bifrost/ops/monitoring/dto/ResourceEventResponse.java:6-11`, `IncidentResponse` field는 `services/operations-backend/src/main/java/com/bifrost/ops/incident/dto/IncidentResponse.java:8-20`, 권한 검사는 `WorkspaceAccessGuard.requireAccess`(`services/operations-backend/src/main/java/com/bifrost/ops/workspace/WorkspaceAccessGuard.java:56-65`)다.

| Method | Path | Auth | 권한 | Status | Request | Response | 설명 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `GET` | `/api/v1/workspaces/{wsId}/monitoring/overview` | yes | `requireAccess` | `200` | 없음 | `OverviewResponse` | 워크스페이스 전체 health/운영 집계 |
| `GET` | `/api/v1/workspaces/{wsId}/monitoring/resource-events` | yes | `requireAccess` | `200` | 없음 | `ResourceEventResponse[]` | Kafka AdminClient 기반 partition reassignment 등 리소스 이벤트 목록 |
| `GET` | `/api/v1/workspaces/{wsId}/monitoring/incidents` | yes | `requireAccess` | `200` | query `status`(optional) | `IncidentResponse[]` | incident 목록. `status`가 있으면 repository가 해당 문자열로 필터링 |
| `GET` | `/api/v1/workspaces/{wsId}/monitoring/incidents/{incidentId}` | yes | `requireAccess` | `200` | path `incidentId` UUID | `IncidentResponse` | incident 상세 |

권한/실패 상태:

- `requireAccess`는 관리 권한을 요구하지 않고 `project_member` 소속만 확인한다. `requireAccess`의 owner fast-path가 아니라 member repository 존재 여부를 사용한다.
- principal이 없으면 `UNAUTHENTICATED` -> `401`, 멤버가 아니면 `WORKSPACE_FORBIDDEN` -> `403`이다.
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

이 파일은 account/workspace/members/settings/monitoring schema를 상세 관리하고, 아래 15개 `@RestController`는 endpoint family 수준으로 커버한다. `@RestControllerAdvice`인 `GlobalExceptionHandler`는 controller coverage 카운트에서 제외한다.

| Controller | Base path | 상세 수준 | 근거 |
| --- | --- | --- | --- |
| `AuthController` | `/api/v1/auth` | 상세 schema/status | `services/operations-backend/src/main/java/com/bifrost/ops/auth/controller/AuthController.java:25-67` |
| `WorkspaceController` | `/api/v1/workspaces` | 상세 schema/status | `services/operations-backend/src/main/java/com/bifrost/ops/workspace/controller/WorkspaceController.java:36-119` |
| `WorkspaceSettingsController` | `/api/v1/workspaces/{wsId}/settings` | 상세 schema/status | `services/operations-backend/src/main/java/com/bifrost/ops/workspace/controller/WorkspaceSettingsController.java:27-93` |
| `EventController` | `/api/v1/workspaces/{wsId}/events` | family catalog | `services/operations-backend/src/main/java/com/bifrost/ops/event/controller/EventController.java:23-41` |
| `SseController` | `/api/v1/workspaces/{wsId}/events/stream` | family catalog | `services/operations-backend/src/main/java/com/bifrost/ops/streaming/SseController.java:22-39` |
| `DatabaseController` | `/api/v1/workspaces/{wsId}/databases` | family catalog | `services/operations-backend/src/main/java/com/bifrost/ops/database/controller/DatabaseController.java:41-152` |
| `PipelineController` | `/api/v1/workspaces/{wsId}/pipelines` | family catalog + runtime metadata 상세 | `services/operations-backend/src/main/java/com/bifrost/ops/pipeline/controller/PipelineController.java:43-218` |
| `KafkaPrincipalController` | `/api/v1/workspaces/{wsId}/kafka/principals` | family catalog + secret 상세 | `services/operations-backend/src/main/java/com/bifrost/ops/workspace/kafka/KafkaPrincipalController.java:21-72` |
| `MonitoringController` | `/api/v1/workspaces/{wsId}/monitoring` | 상세 schema/status | `services/operations-backend/src/main/java/com/bifrost/ops/monitoring/controller/MonitoringController.java:24-76` |
| `ClusterController` | `/api/v1/clusters` | family catalog | `services/operations-backend/src/main/java/com/bifrost/ops/cluster/ClusterController.java:17-44` |
| `InternalController` | `/internal` | family catalog | `services/operations-backend/src/main/java/com/bifrost/ops/internalops/controller/InternalController.java:26-88` |
| `InternalOpsController` | `/internal/ops` | family catalog | `services/operations-backend/src/main/java/com/bifrost/ops/internalops/controller/InternalOpsController.java:24-72` |
| `InternalOpsObservabilityController` | `/internal/ops` | family catalog | `services/operations-backend/src/main/java/com/bifrost/ops/internalops/controller/InternalOpsObservabilityController.java:34-108` |
| `InternalOpsPipelineController` | `/internal/ops/projects/{projectId}/pipelines` | family catalog | `services/operations-backend/src/main/java/com/bifrost/ops/internalops/controller/InternalOpsPipelineController.java:30-90` |
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
| Internal `/internal/ops` | health/version 200, ready 200 또는 503, connector status/list/topology/lag/log search/incident summary 200 | `SecurityConfig`상 `/internal/ops/**` permitAll, service identity 보강은 별도 정책 문서 대상 |
| PoC | connector list/get/sample 200, get/delete not found 404, delete accepted 202 | 임시 PoC surface, 제거 또는 내부망 제한 필요 |

## Alias 제거

`/api/auth/register`, `/api/auth/login`, `/api/auth/me`, `/api/auth/refresh`는 v1 controller 계약이 아니다. 클라이언트는 반드시 `/api/v1/auth/**`를 사용한다. 레거시 alias 호출은 404 `RESOURCE_NOT_FOUND` envelope으로 처리한다(`services/operations-backend/src/test/java/com/bifrost/ops/auth/controller/AuthControllerTest.java:46-63`).

## 3. Common Headers

내부 운영 API(`/internal/ops/**`)는 agent 호출 추적을 위해 다음 헤더를 사용한다.

- `X-Agent-Run-Id`
- `X-Agent-Step-Id`
- `X-Agent-Name`
- `X-Request-Id`
- `X-Actor-Type`
- `X-Actor-Id`
- mutation 계열: `X-Idempotency-Key`

## 4. Internal Ops Success Envelope

`/internal/ops/**` 성공 응답은 다음 envelope을 기준으로 한다.

```json
{
  "ok": true,
  "requestId": "req-1",
  "operation": "list_project_pipelines",
  "result": {},
  "evidence": [],
  "auditEventId": null
}
```

## 5. Internal Ops Error Envelope

내부 운영 실패 응답은 `ok=false`와 error object를 사용한다. 상세 error catalog는 후속 internal-ops 계약 문서에서 확장한다.

```json
{
  "ok": false,
  "requestId": "req-1",
  "operation": "get_connector_status",
  "error": {
    "code": "WORKSPACE_FORBIDDEN",
    "retryable": false,
    "requiredAction": "check_workspace_scope"
  }
}
```

## Workspace Event Stream

`GET /api/v1/workspaces/{wsId}/events/stream`은 workspace SSE 채널이다. Browser `EventSource` 제약 때문에 Bearer header 대신 단명 `access_token` query parameter를 사용할 수 있다.

## 18. Schema Registry API

Schema Registry 연동은 v1 필수 경로가 아니다. 도입 시 Spring Boot가 compatibility 상태와 schema 변화 조회를 노출하고, FastAPI RCA catalog가 이를 evidence로 참조한다.

## 19. Approval API

Approval facade의 Source of Truth는 Spring Boot다. FastAPI는 approval link와 decision context를 Spring에 위임한다.

## 24. Report Support API

RCA 분석 run이 verifier를 통과하면 Spring Boot incident에 `root_cause_summary`, severity 보정, report reference를 기록한다.

## 25. Admin API

`GET /internal/ops/admin/tool-catalog`는 Spring이 집행 가능한 operation allowlist를 노출하는 런타임 확인 API로 설계되어 있으나, 현재 controller mapping은 없다(WIP).
