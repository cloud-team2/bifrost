# MVP Specification

> Canonical source: [docs/spec.md](../spec.md)
> 목적: root README의 MVP 링크를 보존하면서, 2026-06-08 기준 account/workspace/settings API 갭을 한 페이지로 빠르게 확인하게 한다.

이 문서는 `docs/spec.md`의 요약/보강본이다. 상태값·임계값·FR 번호의 단일 출처는 계속 [docs/spec.md](../spec.md)이다.

## Account / Auth

- 지원 경로는 `/api/v1/auth/**`만이다.
- `/api/auth/**` alias는 v1에서 제거되었고, 클라이언트는 사용하면 안 된다.
- `GET /api/v1/auth/me`는 계정 정보뿐 아니라 현재 workspace context를 함께 반환한다.

`GET /api/v1/auth/me` 필수 응답:

- `userId`, `email`, `name`
- `role`
- `joinedAt`, `lastLoginAt`
- `workspaceId`, `workspaceName`, `namespace`, `workspaceStatus`

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

## 상세 문서

- API 카탈로그: [docs/api/springboot.md](../api/springboot.md)
- 에러 코드: [docs/api/error-codes.md](../api/error-codes.md)
- Spring 설계: [docs/design/backend-springboot/](../design/backend-springboot/overview.md)
