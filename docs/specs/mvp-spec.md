# MVP Specification

> Canonical source: [docs/spec.md](../spec.md)
> 목적: root README의 MVP 링크를 보존하면서 account/workspace/settings의 MVP acceptance, non-goals, API 계약을 한 페이지로 빠르게 확인하게 한다.

이 문서는 `docs/spec.md`의 요약/보강본이다. 상태값·임계값·FR 번호의 단일 출처는 계속 [docs/spec.md](../spec.md)이다.

## Account / Auth

- 지원 경로는 `/api/v1/auth/**`만이다.
- `/api/auth/**` alias는 v1 controller가 없어 404 `RESOURCE_NOT_FOUND` envelope으로 거부된다. 클라이언트는 사용하면 안 된다.
- `GET /api/v1/auth/me`는 계정 정보뿐 아니라 현재 workspace context를 함께 반환한다.

`GET /api/v1/auth/me` 필수 응답:

- `userId`, `email`, `name`
- `role`
- `joinedAt`, `lastLoginAt`
- `workspaceId`, `workspaceName`, `namespace`, `workspaceStatus`

`namespace`는 workspace API의 `projectKey`와 같은 슬러그다. 코드 정본은 `MeResponse`다(`services/operations-backend/src/main/java/com/bifrost/ops/auth/dto/MeResponse.java:8-19`).

## Workspace / Members

MVP에 포함되는 workspace endpoint:

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/v1/workspaces` | 내 워크스페이스 목록 |
| `POST` | `/api/v1/workspaces` | 워크스페이스 생성 |
| `GET` | `/api/v1/workspaces/{wsId}` | 워크스페이스 상세 |
| `PATCH` | `/api/v1/workspaces/{wsId}` | 이름/timezone 수정 |
| `GET` | `/api/v1/workspaces/{wsId}/members` | 멤버 목록 |
| `POST` | `/api/v1/workspaces/{wsId}/members` | 멤버 추가 |
| `PATCH` | `/api/v1/workspaces/{wsId}/members/{userId}` | 멤버 역할 변경 |
| `DELETE` | `/api/v1/workspaces/{wsId}/members/{userId}` | 멤버 제거 |

권한:

- 멤버 목록 조회는 `OWNER`/`ADMIN`/`MEMBER` 모두 가능하다.
- 멤버 추가/역할 변경/삭제는 `OWNER`/`ADMIN`만 가능하다.
- 비멤버 workspace 접근은 `WORKSPACE_FORBIDDEN`이다.

## Workspace Settings

MVP Settings 화면은 세 개 도메인을 사용한다.

| 도메인 | Endpoint | 설명 |
| --- | --- | --- |
| notifications | `GET/PUT /api/v1/workspaces/{wsId}/settings/notifications` | Slack/email 알림 |
| thresholds | `GET/PUT /api/v1/workspaces/{wsId}/settings/thresholds` | warning/critical lag 임계값 |
| ai-policy | `GET/PUT /api/v1/workspaces/{wsId}/settings/ai-policy` | AI 자동복구, 승인 대기, prod lock |

조회는 멤버에게 허용하고, 수정은 `OWNER`/`ADMIN`에게만 허용한다.

## Acceptance Criteria

- 회원가입은 사용자와 최초 워크스페이스를 생성하고 `201`과 token response를 반환한다.
- 로그인/refresh/me는 `/api/v1/auth/**`에서만 동작하고, `/api/auth/**` alias는 404 envelope으로 거부된다.
- `/api/v1/auth/me`는 `userId, email, name, role, joinedAt, lastLoginAt, workspaceId, workspaceName, namespace, workspaceStatus`를 반환한다.
- 워크스페이스 목록/상세는 `WorkspaceResponse(id, name, projectKey, timezone, status, createdAt, pipelineCount, activePipelineCount)` 계약을 지킨다.
- 멤버 추가는 `201`, 삭제는 `204`를 반환한다. 멤버 목록은 모든 멤버가 조회할 수 있고, 멤버/워크스페이스/settings 관리는 `OWNER`/`ADMIN`만 가능하다.
- settings `PUT`은 nullable partial update로 동작한다. 단, `slackWebhookUrl`은 `null`/blank를 `null`로 저장하고 Slack enabled 상태에서는 valid Slack webhook 형식이어야 한다. JSON alias(`severityPolicy`, `slackWebhook`, `lagWarningThreshold`, `lagCriticalThreshold`, `aiAutonomous`, `aiApprovalWaitMinutes`, `aiProdLock`)를 받는다.

## Non-goals

- OWNER 이관 차단/승인 흐름을 새로 구현하지 않는다. 현재 코드의 non-owner -> OWNER 승격 허용 동작은 docs-only로 기록한다.
- 모든 Spring controller의 full schema를 이 파일에 중복하지 않는다. 상세 schema/status는 [Spring Boot API](../api/springboot.md)와 `/v3/api-docs`를 따른다.
- 내부 `/internal/ops/**` service identity 강화와 FastAPI strict schema mismatch 수정은 별도 코드 작업이다.

## Success Criteria

- account/workspace/members/settings 문서가 실제 controller/DTO/status와 일치한다.
- critical controller coverage는 [Spring Boot API Controller Coverage](../api/springboot.md#controller-coverage)에 적힌 14개 controller와 일치한다.
- repo-local markdown link 검증에서 broken link가 0건이다.

## 상세 문서

- API 카탈로그: [docs/api/springboot.md](../api/springboot.md)
- 에러 코드: [docs/api/error-codes.md](../api/error-codes.md)
- Spring 설계: [docs/design/backend-springboot/](../design/backend-springboot/overview.md)
