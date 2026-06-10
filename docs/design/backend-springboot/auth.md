# Spring Boot Operations Backend — Auth (로그인·JWT)

> 요약은 [overview.md](./overview.md). 이 파일은 인증(FR-001)과 토큰을 다룬다. **Spring이 JWT를 발급·검증한다. FastAPI JWT 검증은 현재 구현되어 있지 않다.** 스코프 검증의 데이터 모델은 [data-model §3.1.1 project_member](./data-model.md#311-project_member-워크스페이스-멤버십-fr-002).

## 9. Auth

### 1. 목적·범위

이메일·비밀번호 로그인으로 콘솔에 진입(FR-001)하고, 발급한 JWT로 **Spring 플랫폼 API·플랫폼 SSE**를 인증한다. 현재 FastAPI Agent API에는 JWT 검증 dependency가 연결되어 있지 않다. v1은 단일 콘솔이라 화면/액터 분기는 없지만, Spring workspace 범위 authorization은 `project_member.role`(`OWNER`/`ADMIN`/`MEMBER`)로 판정한다.

### 2. 로그인·토큰·내 계정

| Method | Path | 설명 |
| --- | --- | --- |
| `POST` | `/api/v1/auth/register` | 사용자와 최초 워크스페이스 생성, access token 발급 |
| `POST` | `/api/v1/auth/login` | `{email, password}` → access token. `password_hash`(bcrypt) 검증 |
| `POST` | `/api/v1/auth/refresh` | 유효한 Bearer token 기반 새 access token |
| `GET` | `/api/v1/auth/me` | `userId`, `email`, `name`, `role`, `joinedAt`, `lastLoginAt`, `workspaceId`, `workspaceName`, `namespace`, `workspaceStatus` |

- access token은 API 응답의 `accessToken`으로 발급한다. SSE용으로는 더 짧은 토큰을 쿼리로 전달할 수 있다(§5).
- `/api/auth/**` legacy alias는 v1 controller 계약이 아니다. 현재 security matcher는 `/api/auth/login`만 permitAll로 두어 handler 부재 404에 도달하고, `/api/auth/refresh`·`/api/auth/me`는 인증 필요, `/api/auth/register`는 Bearer 없이는 401이다. 클라이언트는 `/api/v1/auth/**`만 호출한다.
- `/api/v1/auth/me`의 `namespace`는 workspace API의 `projectKey`와 같은 슬러그다. 코드 정본은 `MeResponse`다(`services/operations-backend/src/main/java/com/bifrost/ops/auth/dto/MeResponse.java:8-19`).

### 3. JWT 구조·공유 검증

- **claims**: `sub`(app_user.id)·`email`·`tid`(home workspace)·`iat`·`exp`. 경로의 워크스페이스 권한은 요청마다 `project_member`/소유권으로 검증한다(워크스페이스 가입/탈퇴가 토큰 재발급 없이 반영되도록).
- **공유 검증 목표와 현재 상태**: Spring이 JWT를 발급·검증한다. FastAPI는 현재 route dependency/auth middleware가 비어 있어 같은 키 검증을 수행하지 않는다. 공유 검증(JWKS 또는 shared secret)은 보안 보강 대상이다.

### 4. 스코프·인가

```text
요청 → JWT 검증(sub) → currentUser
  → workspace 범위 호출이면 project_member(workspace_id, user_id) 또는 owner_user_id 확인
     없으면 WORKSPACE_FORBIDDEN / 403
```

- `project_member.role`은 `OWNER`/`ADMIN`/`MEMBER`다. 멤버 목록 조회는 세 역할 모두 가능하고, workspace 수정·멤버 추가/수정/삭제·settings 수정은 `OWNER`/`ADMIN`만 가능하다.
- 내부 운영 API(`/internal/ops`)의 service identity 인증은 설계 목표다. 현재 `SecurityConfig`는 `/internal/ops/**`를 permitAll로 두며, mutation controller가 agent headers, approval, idempotency, project/resource ownership을 자체 검증한다([governance](./governance.md)).

### 5. 시드·보안

- **데모 계정 seed**: `ta@bifrost.io / ta1234`(시드명일 뿐 역할 고정 아님 — [FR-001](../../spec.md#fr-001--로그인-및-콘솔-진입)). 워크스페이스 생성 시 생성자를 `project_member`로 자동 등록.
- `password_hash`는 bcrypt 등 단방향. 평문 저장·로그 금지.
- **SSE 인증**: Spring workspace SSE는 브라우저 `EventSource` 제약 때문에 JWT를 `?access_token=` 쿼리로 받을 수 있고 Spring JWT filter가 검증한다. FastAPI Agent SSE는 현재 query token을 검증하지 않는다.
- JWT 서명키는 Secret으로 관리, 키 로테이션 시 JWKS로 무중단 교체.

### 6. 구현 메모

- 패키지 `auth`(controller·service) + `auth.security` SecurityConfig(필터 체인). JWT 필터가 `/api/v1/**`에 적용되고 `/api/v1/auth/register`·`/login`은 permitAll이다.
- 테스트: 자격증명 불일치→실패, 만료 토큰→401, 비멤버 워크스페이스 접근→403, Spring workspace SSE 쿼리 토큰 검증. FastAPI JWT 검증은 현재 테스트 대상이 아니다.
