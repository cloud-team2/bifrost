# Spring Boot API Reference

> 대상 서비스: `services/operations-backend`
> 기본 prefix: `/api/v1`
> Swagger UI: `/swagger-ui.html`, OpenAPI JSON: `/v3/api-docs`

이 문서는 프론트엔드가 호출하는 플랫폼 API 중 account/workspace/settings 표면을 정리한다. 런타임 계약의 정본은 Spring controller와 OpenAPI 산출물이며, 이 문서는 사람이 읽는 카탈로그다.

## 공통 규칙

- 인증: `Authorization: Bearer <accessToken>`
- 성공 응답: controller DTO를 그대로 반환한다.
- 실패 응답: [error-codes.md](./error-codes.md)의 `ErrorResponse` envelope을 따른다.
- 레거시 alias `/api/auth/**`는 지원하지 않는다. 인증 API는 `/api/v1/auth/**`만 사용한다.
- 워크스페이스 범위 API의 `{wsId}`는 `workspaceId` UUID다.

## Auth / Account

| Method | Path | Auth | 설명 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/auth/register` | no | 사용자와 최초 워크스페이스 생성, access token 발급 |
| `POST` | `/api/v1/auth/login` | no | 이메일/비밀번호 로그인 |
| `POST` | `/api/v1/auth/refresh` | yes | 현재 Bearer token 주체에게 새 token 발급 |
| `GET` | `/api/v1/auth/me` | yes | 내 계정과 현재 워크스페이스 컨텍스트 조회 |

`GET /api/v1/auth/me` 응답 필드:

| 필드 | 설명 |
| --- | --- |
| `userId`, `email`, `name` | 사용자 식별자와 표시 정보 |
| `role` | 현재 워크스페이스에서의 `OWNER`/`ADMIN`/`MEMBER` |
| `joinedAt`, `lastLoginAt` | 멤버 가입 시각, 최근 로그인 시각 |
| `workspaceId`, `workspaceName`, `namespace`, `workspaceStatus` | 현재 워크스페이스 정보 |

## Workspace

| Method | Path | Auth | 권한 | 설명 |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/workspaces` | yes | 소속 사용자 | 내 워크스페이스 목록 |
| `POST` | `/api/v1/workspaces` | yes | 인증 사용자 | 워크스페이스 생성, 생성자를 OWNER로 등록 |
| `GET` | `/api/v1/workspaces/{wsId}` | yes | 소속 사용자 | 워크스페이스 상세 |
| `PATCH` | `/api/v1/workspaces/{wsId}` | yes | OWNER/ADMIN | `name`, `timezone` 수정 |

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

| Method | Path | Auth | 권한 | 설명 |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/workspaces/{wsId}/members` | yes | 소속 사용자 | 멤버 목록 조회 |
| `POST` | `/api/v1/workspaces/{wsId}/members` | yes | OWNER/ADMIN | 이메일로 멤버 추가 |
| `PATCH` | `/api/v1/workspaces/{wsId}/members/{userId}` | yes | OWNER/ADMIN | 멤버 역할 변경 |
| `DELETE` | `/api/v1/workspaces/{wsId}/members/{userId}` | yes | OWNER/ADMIN | 멤버 제거 |

역할 규칙:

| 역할 | 멤버 목록 GET | 멤버 추가/수정/삭제 | 설정 수정 |
| --- | --- | --- | --- |
| `OWNER` | 가능 | 가능 | 가능 |
| `ADMIN` | 가능 | 가능 | 가능 |
| `MEMBER` | 가능 | 불가 (`WORKSPACE_FORBIDDEN`) | 불가 (`WORKSPACE_FORBIDDEN`) |

`POST`에서 `role=OWNER` 요청은 `ADMIN`으로 저장된다. OWNER 역할 이관 정책은 별도 작업 전까지 막는다.

## Workspace Settings

| Method | Path | Auth | 권한 | 설명 |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/workspaces/{wsId}/settings/notifications` | yes | 소속 사용자 | Slack/email 알림 설정 조회 |
| `PUT` | `/api/v1/workspaces/{wsId}/settings/notifications` | yes | OWNER/ADMIN | 알림 설정 수정 |
| `GET` | `/api/v1/workspaces/{wsId}/settings/thresholds` | yes | 소속 사용자 | lag warning/critical 임계값 조회 |
| `PUT` | `/api/v1/workspaces/{wsId}/settings/thresholds` | yes | OWNER/ADMIN | 임계값 수정 |
| `GET` | `/api/v1/workspaces/{wsId}/settings/ai-policy` | yes | 소속 사용자 | AI 자동복구 정책 조회 |
| `PUT` | `/api/v1/workspaces/{wsId}/settings/ai-policy` | yes | OWNER/ADMIN | AI 자동복구 정책 수정 |

설정 도메인:

- notifications: `slackEnabled`, `slackWebhookUrl`, `emailRecipients`, `severity`
- thresholds: `warning`, `critical`
- ai-policy: `autonomous`, `approvalWaitMinutes`, `prodLock`

## Alias 제거

`/api/auth/register`, `/api/auth/login`, `/api/auth/me`, `/api/auth/refresh`는 v1 계약이 아니다. 클라이언트는 반드시 `/api/v1/auth/**`를 사용한다. 레거시 alias 호출은 404 계열 envelope으로 처리한다.

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

## A.8 Workspace Event Stream

`GET /api/v1/workspaces/{wsId}/events/stream`은 workspace SSE 채널이다. Browser `EventSource` 제약 때문에 Bearer header 대신 단명 `access_token` query parameter를 사용할 수 있다.

## 18. Schema Registry API

Schema Registry 연동은 v1 필수 경로가 아니다. 도입 시 Spring Boot가 compatibility 상태와 schema 변화 조회를 노출하고, FastAPI RCA catalog가 이를 evidence로 참조한다.

## 19. Approval API

Approval facade의 Source of Truth는 Spring Boot다. FastAPI는 approval link와 decision context를 Spring에 위임한다.

## 24. Report Support API

RCA 분석 run이 verifier를 통과하면 Spring Boot incident에 `root_cause_summary`, severity 보정, report reference를 기록한다.

## 25. Admin API

`GET /internal/ops/admin/tool-catalog`는 Spring이 집행 가능한 operation allowlist를 노출하는 런타임 확인 API다.
