# Spring Boot Operations Backend — Auth (로그인·JWT)

> 요약은 [overview.md](./overview.md). 이 파일은 인증(FR-001)과 토큰을 다룬다. **Spring이 JWT를 발급하고, Spring·FastAPI 두 서비스가 같은 JWT를 검증**한다([frontend §11](../frontend.md#11-확정-사항)). 스코프 검증의 데이터 모델은 [data-model §3.1.1 project_member](./data-model.md#311-project_member-워크스페이스-멤버십-fr-002).

## 9. Auth

### 1. 목적·범위

이메일·비밀번호 로그인으로 콘솔에 진입(FR-001)하고, 발급한 JWT로 **플랫폼 API·Agent API·플랫폼 SSE**를 모두 인증한다. v1은 단일 콘솔이라 화면/액터 분기는 없지만, workspace 범위 authorization은 `project_member.role`(`OWNER`/`ADMIN`/`MEMBER`)로 판정한다.

### 2. 로그인·토큰·내 계정

| Method | Path | 설명 |
| --- | --- | --- |
| `POST` | `/api/v1/auth/register` | 사용자와 최초 워크스페이스 생성, access token 발급 |
| `POST` | `/api/v1/auth/login` | `{email, password}` → access token. `password_hash`(bcrypt) 검증 |
| `POST` | `/api/v1/auth/refresh` | 유효한 Bearer token 기반 새 access token |
| `GET` | `/api/v1/auth/me` | `userId`, `email`, `name`, `role`, `joinedAt`, `lastLoginAt`, `workspaceId`, `workspaceName`, `namespace`, `workspaceStatus` |

- access token은 API 응답의 `accessToken`으로 발급한다. SSE용으로는 더 짧은 토큰을 쿼리로 전달할 수 있다(§5).
- `/api/auth/**` legacy alias는 v1 controller 계약이 아니며 404 `RESOURCE_NOT_FOUND` envelope으로 거부된다. 클라이언트는 `/api/v1/auth/**`만 호출한다.
- `/api/v1/auth/me`의 `namespace`는 workspace API의 `projectKey`와 같은 슬러그다. 코드 정본은 `MeResponse`다(`services/operations-backend/src/main/java/com/bifrost/ops/auth/dto/MeResponse.java:8-19`).

### 3. JWT 구조·공유 검증

- **claims**: `sub`(app_user.id)·`email`·`tid`(home workspace)·`iat`·`exp`. 경로의 워크스페이스 권한은 요청마다 `project_member`/소유권으로 검증한다(워크스페이스 가입/탈퇴가 토큰 재발급 없이 반영되도록).
- **공유 검증**: Spring이 발급(서명), Spring·FastAPI가 **같은 키로 검증**한다 — 대칭(공유 secret) 또는 비대칭(Spring 서명 + JWKS 공개키 노출). FastAPI는 검증만(발급 안 함). 별도 로그인·세션 동기화 없음([frontend §11 확정](../frontend.md#11-확정-사항)).

### 4. 스코프·인가

```text
요청 → JWT 검증(sub) → currentUser
  → workspace 범위 호출이면 project_member(workspace_id, user_id) 또는 owner_user_id 확인
     없으면 WORKSPACE_FORBIDDEN / 403
```

- `project_member.role`은 `OWNER`/`ADMIN`/`MEMBER`다. 멤버 목록 조회는 세 역할 모두 가능하고, workspace 수정·멤버 추가/수정/삭제·settings 수정은 `OWNER`/`ADMIN`만 가능하다.
- 내부 운영 API(`/internal/ops`)는 사용자 JWT가 아니라 **FastAPI service identity**로 인증하고, 사용자 권한은 FastAPI 전달값을 믿지 않고 재확인한다([governance §8](./governance.md#8-internalops-요청-표면-internalops)).

### 5. 시드·보안

- **데모 계정 seed**: `ta@bifrost.io / ta1234`(시드명일 뿐 역할 고정 아님 — [FR-001](../../spec.md#fr-001--로그인-및-콘솔-진입)). 워크스페이스 생성 시 생성자를 `project_member`로 자동 등록.
- `password_hash`는 bcrypt 등 단방향. 평문 저장·로그 금지.
- **SSE 인증**: 브라우저 `EventSource`는 헤더 불가 → JWT를 `?access_token=` 쿼리로 전달하고 백엔드가 검증(단명 토큰 + workspace scope 확인, 재연결 시 갱신 — [frontend §11](../frontend.md#11-확정-사항)).
- JWT 서명키는 Secret으로 관리, 키 로테이션 시 JWKS로 무중단 교체.

### 6. 구현 메모

- 패키지 `auth`(controller·service) + `auth.security` SecurityConfig(필터 체인). JWT 필터가 `/api/v1/**`에 적용되고 `/api/v1/auth/register`·`/login`은 permitAll이다.
- 테스트: 자격증명 불일치→실패, 만료 토큰→401, 비멤버 워크스페이스 접근→403, Spring 발급 JWT를 FastAPI가 검증, SSE 쿼리 토큰 검증.
