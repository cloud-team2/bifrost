# Spring Boot Operations Backend — Auth (로그인·JWT)

> 요약은 [overview.md](./overview.md). 이 파일은 인증(FR-001)과 토큰을 다룬다. **Spring이 JWT를 발급하고, Spring·FastAPI 두 서비스가 같은 JWT를 검증**한다([frontend §11](../frontend.md)). 스코프 검증의 데이터 모델은 [data-model §3.1.1 project_member](./data-model.md#4-data-model).

## 9. Auth

### 1. 목적·범위

이메일·비밀번호 로그인으로 콘솔에 진입(FR-001)하고, 발급한 JWT로 **플랫폼 API·Agent API·플랫폼 SSE**를 모두 인증한다. v1은 단일 콘솔이라 **권한 분기는 없고**, "어떤 사용자가 어떤 워크스페이스에 접근 가능한가"만 `project_member`로 판정한다.

### 2. 로그인·토큰

| Method | Path | 설명 |
| --- | --- | --- |
| `POST` | `/api/v1/auth/login` | `{email, password}` → `{token, user}`. `password_hash`(bcrypt) 검증 |
| `POST` | `/api/v1/auth/refresh` | refresh → 새 access token |

- access token은 **단명**(예: 15분) + refresh token(장수명, httpOnly). SSE용으로는 더 짧은 토큰을 쿼리로 전달(§5).

### 3. JWT 구조·공유 검증

- **claims**: `sub`(app_user.id)·`email`·`iat`·`exp`. 워크스페이스는 토큰에 넣지 않고 요청마다 `project_member`로 검증(워크스페이스 가입/탈퇴가 토큰 재발급 없이 반영되도록).
- **공유 검증**: Spring이 발급(서명), Spring·FastAPI가 **같은 키로 검증**한다 — 대칭(공유 secret) 또는 비대칭(Spring 서명 + JWKS 공개키 노출). FastAPI는 검증만(발급 안 함). 별도 로그인·세션 동기화 없음([frontend §11 확정](../frontend.md)).

### 4. 스코프·인가

```text
요청 → JWT 검증(sub) → currentUser
  → workspace 범위 호출이면 project_member(workspace_id, app_user_id) 존재 확인
     없으면 RESOURCE_NOT_OWNED_BY_PROJECT / 403
```

- `role_hint`(`ta`/`aa`/`developer`/`operator`)는 **화면 동선 강조용 라벨**일 뿐 인가 근거가 아니다([data-model §3.2](./data-model.md#4-data-model)).
- 내부 운영 API(`/internal/ops`)는 사용자 JWT가 아니라 **FastAPI service identity**로 인증하고, 사용자 권한은 FastAPI 전달값을 믿지 않고 재확인한다([governance §8](./governance.md#7-governance-engine)).

### 5. 시드·보안

- **데모 계정 seed**: `ta@bifrost.io / ta1234`(시드명일 뿐 역할 고정 아님 — [FR-001](../../spec.md#fr-001--로그인-및-콘솔-진입)). 워크스페이스 생성 시 생성자를 `project_member`로 자동 등록.
- `password_hash`는 bcrypt 등 단방향. 평문 저장·로그 금지.
- **SSE 인증**: 브라우저 `EventSource`는 헤더 불가 → JWT를 `?access_token=` 쿼리로 전달하고 백엔드가 검증(단명 토큰 + workspace scope 확인, 재연결 시 갱신 — [frontend §11](../frontend.md)).
- JWT 서명키는 Secret으로 관리, 키 로테이션 시 JWKS로 무중단 교체.

### 6. 구현 메모

- 패키지 `auth`(controller·service) + `global.config` SecurityConfig(필터 체인). JWT 필터가 `/api/v1/**`에 적용되고 `/api/v1/auth/login`·`/refresh`는 예외.
- 테스트: 자격증명 불일치→실패, 만료 토큰→401, 비멤버 워크스페이스 접근→403, FastAPI 발급 JWT 검증 성공(공유키), SSE 쿼리 토큰 검증.
