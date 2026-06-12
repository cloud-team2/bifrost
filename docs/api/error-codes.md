# API 에러 코드 카탈로그

Bifrost API가 반환하는 에러 코드의 단일 출처. 코드 추가/변경 시 본 문서도 같은 PR로 갱신한다.

## 응답 형식

성공 응답은 핸들러별 DTO를 그대로 반환한다. 실패 응답은 아래 봉투를 따른다.

```json
{
  "code": "10001",
  "message": "이미 가입된 이메일",
  "details": [
    { "field": "email", "reason": "must be a well-formed email address" }
  ]
}
```

- `code` (string): 본 문서에 정의된 비즈니스 에러 코드 번호 (문자열로 직렬화)
- `message` (string): 한국어 사용자 메시지 (그대로 노출 가능)
- `details` (array): 필드별 validation 오류. 오류 상세가 없으면 빈 배열이다.

## Internal Ops 문자열 코드

`/internal/ops/**`의 agent-facing API는 플랫폼 숫자형 `ErrorResponse`가 아니라 `OpsEnvelope`를 사용한다. 이 표면의 실패 코드는 문자열이며 `error.code`, `error.message`, `error.retryable`, `error.required_action`에 담긴다.

```json
{
  "ok": false,
  "request_id": "req-1",
  "operation": "restart_connector",
  "evidence": [],
  "error": {
    "code": "APPROVAL_REQUIRED",
    "message": "approval is required for mutation",
    "retryable": false,
    "required_action": "request_approval"
  }
}
```

현재 Spring 구현 기준 문자열 코드는 다음과 같다.

| Code | HTTP | 주 사용처 |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | mutation 필수 header 누락, `list_alerts` limit 형식/범위 오류 |
| `APPROVAL_REQUIRED` | 403 | `X-Approval-Id` 누락, approval status가 승인 상태가 아님 |
| `APPROVAL_EXPIRED` | 403 | mutation approval 만료. 내부 mutation mapper는 expired approval도 forbidden response로 반환 |
| `APPROVAL_SCOPE_MISMATCH` | 403 | approval tenant/operation/params hash/single-use replay scope 불일치 |
| `CONFLICT` | 409 | idempotency key 처리 중, 같은 key의 다른 operation/params, approval already used |
| `RESOURCE_NOT_FOUND` | 404 | internal project/workspace 또는 alert projection 대상 없음 |
| `RESOURCE_NOT_OWNED_BY_PROJECT` | 403 | connector/resource가 `{projectId}` workspace 소유가 아님 |
| `CONNECTOR_NOT_FOUND` | 404 | mutation 대상 Kafka connector 없음 |
| `CONSUMER_GROUP_NOT_FOUND` | 404 | mutation 대상 Kafka Connect-managed consumer group 없음 |
| `TIMEOUT` | 504 | Kafka Connect REST timeout |
| `UPSTREAM_UNAVAILABLE` | 502 | Kafka Connect REST/상류 시스템 호출 실패 |
| `INTERNAL_ERROR` | 500 | 내부 운영 API fallback 오류 |

Approval/Change Ticket controller가 명시적으로 `OpsEnvelope`를 반환하는 경로는 위 문자열 envelope을 따른다. Bean Validation, JSON parse, 일부 `ApiException` fallback은 전역 `GlobalExceptionHandler`를 거쳐 숫자형 `ErrorResponse`로 내려갈 수 있으므로, 클라이언트는 `/internal/ops/**`에서 두 envelope을 모두 허용해야 한다.

### Tool-layer `SpringErrorCode` enum (ai-service)

ai-service tool client가 인식하는 `error.code` 전체 집합은 `services/ai-service/app/schemas/tools.py`의 `SpringErrorCode` enum이다. 이 enum은 위 "현재 Spring 구현 기준 문자열 코드" 표의 superset으로, Spring이 아직 emit하지 않더라도 client가 매핑·처리할 수 있도록 정의된 코드를 포함한다. enum이 알지 못하는 문자열 코드는 `ToolError.code`가 `str`로 그대로 보존한다(`code: SpringErrorCode | str`). HTTP 상태는 client mirror이므로 고정되지 않고 Spring 응답을 따른다.

위 표에 이미 있는 코드(`VALIDATION_FAILED`, `APPROVAL_REQUIRED`, `APPROVAL_EXPIRED`, `APPROVAL_SCOPE_MISMATCH`, `CONFLICT`, `RESOURCE_NOT_FOUND`, `RESOURCE_NOT_OWNED_BY_PROJECT`, `CONNECTOR_NOT_FOUND`, `CONSUMER_GROUP_NOT_FOUND`, `TIMEOUT`, `UPSTREAM_UNAVAILABLE`, `INTERNAL_ERROR`) 외에 enum이 추가로 정의하는 코드는 다음과 같다.

| Code | 의미 |
| --- | --- |
| `POLICY_DENIED` | 정책상 실행 차단 |
| `CHANGE_TICKET_REQUIRED` | mutation 실행에 change ticket이 필요하거나 실행 가능한 상태가 아님 |
| `CHANGE_WINDOW_CLOSED` | change ticket의 실행 window가 열려 있지 않음 |
| `CHANGE_SCOPE_MISMATCH` | change ticket scope(operation/params 등) 불일치 |
| `PIPELINE_NOT_FOUND` | 대상 pipeline 없음 |
| `INCIDENT_NOT_FOUND` | 대상 incident 없음 |
| `IDEMPOTENCY_REPLAY` | 같은 idempotency key의 replay 감지 |
| `TRANSIENT_ERROR` | 재시도 가능한 일시 오류 |
| `PERMISSION_DENIED` | actor 권한 부족 |
| `UNAUTHENTICATED` | 인증 컨텍스트 없음 |

## 코드 번호 규칙

| 범위 | 도메인 |
|---|---|
| `10000~10999` | 인증 / 사용자 |
| `20000~20999` | 워크스페이스(프로젝트) / 멤버 |
| `30000~30999` | 데이터베이스 |
| `40000~40999` | 파이프라인 / 커넥터 |
| `50000~50999` | 서버 / 인프라 / 내부 오류 |
| `60000~61999` | 거버넌스 / 승인 / 변경관리 |
| `90000~99999` | 기타 (검증 등 도메인-횡단) |

- 각 도메인 안에서 사건별로 +1씩 배정한다.
- 한 번 부여된 번호는 **재사용 금지**. 코드를 삭제하면 본 문서에서도 아카이브 섹션으로 이동.

## 코드 목록

### 인증 / 사용자 (10000~10999)

| 코드 | HTTP | 이름 | 트리거 | 클라이언트 권장 처리 |
|---|---|---|---|---|
| 10001 | 409 | `EMAIL_ALREADY_USED` | 회원가입 시 같은 email이 이미 존재 | "이미 가입된 이메일입니다" 안내, 로그인 화면 유도 |
| 10002 | 401 | `INVALID_CREDENTIALS` | 로그인 시 사용자 없음 또는 비밀번호 불일치 | "이메일 또는 비밀번호를 확인해주세요" 안내 |
| 10003 | 401 | `UNAUTHENTICATED` | 인증이 필요한 API에 토큰 없이 접근 | 로그인 화면으로 리다이렉트 |
| 10004 | 401 | `AUTH_TOKEN_INVALID` | JWT 서명 위변조·형식 오류·없는 issuer 등 | 토큰 폐기 후 재로그인 유도 |
| 10005 | 401 | `AUTH_TOKEN_EXPIRED` | JWT 만료 | refresh 또는 재로그인 유도 |
| 10006 | 404 | `USER_NOT_FOUND_BY_EMAIL` | 멤버 초대/추가 시 email에 해당하는 사용자가 없음 | 가입된 이메일인지 확인하도록 안내 |

### 워크스페이스(프로젝트) / 멤버 (20000~20999)

| 코드 | HTTP | 이름 | 트리거 | 클라이언트 권장 처리 |
|---|---|---|---|---|
| 20001 | 409 | `WORKSPACE_NAME_CONFLICT` | 회원가입 시 같은 워크스페이스 이름 존재 | "이미 사용 중인 이름입니다" 안내 |
| 20002 | 409 | `WORKSPACE_NAMESPACE_CONFLICT` | 회원가입 시 같은 namespace 존재 | "이미 사용 중인 namespace입니다" 안내 |
| 20003 | 404 | `WORKSPACE_NOT_FOUND` | 현재 사용자의 워크스페이스를 조회할 수 없음 | "워크스페이스를 찾을 수 없습니다" 안내, 로그인 재시도 권장 |
| 20004 | 403 | `WORKSPACE_FORBIDDEN` | 요청 경로의 `wsId`가 사용자 소속 워크스페이스가 아니거나 관리 권한이 없음 | "접근 권한이 없습니다" 안내, 워크스페이스 선택 화면 유도 |
| 20005 | 404 | `MEMBER_NOT_FOUND` | 멤버 수정/삭제 대상이 워크스페이스 멤버가 아님 | 멤버 목록 새로고침 |
| 20006 | 409 | `MEMBER_ALREADY_EXISTS` | 이미 워크스페이스에 속한 사용자를 다시 추가 | 기존 멤버임을 안내 |
| 20007 | 409 | `OWNER_DEMOTION_FORBIDDEN` | OWNER 역할 변경 또는 삭제 시도 | OWNER 이관 정책이 생기기 전까지 차단 안내 |

### 데이터베이스 (30000~30999)

| 코드 | HTTP | 이름 | 트리거 | 클라이언트 권장 처리 |
|---|---|---|---|---|
| 30001 | 409 | `DATABASE_NAME_CONFLICT` | 같은 워크스페이스에 동일 이름(alias) DB가 이미 존재 | "이미 사용 중인 이름입니다" 안내 |
| 30002 | 404 | `DATABASE_NOT_FOUND` | `dbId`가 없거나 해당 워크스페이스 소유가 아님 | "데이터베이스를 찾을 수 없습니다" 안내, 목록으로 복귀 |
| 30003 | 422 | `DATABASE_CONNECTION_FAILED` | 등록 시 연결 검증 실패(연결 테스트 분류 사유 동반) | 연결 테스트 분류(`CONNECTION_REFUSED` 등)를 안내, 입력 수정 유도 |

### 파이프라인 / 커넥터 (40000~40999)

| 코드 | HTTP | 이름 | 트리거 | 클라이언트 권장 처리 |
|---|---|---|---|---|
| 40001 | 404 | `PIPELINE_NOT_FOUND` | `id`가 없거나 해당 워크스페이스 소유가 아님(상세·pause·resume·delete) | "파이프라인을 찾을 수 없습니다" 안내, 목록으로 복귀 |
| 40002 | 404 | `KAFKA_PRINCIPAL_NOT_FOUND` | `id`가 없거나 해당 워크스페이스 소유 Kafka principal이 아님 | principal 목록 새로고침 |
| 40003 | 400 | `KAFKA_PRINCIPAL_USERNAME_INVALID` | username이 1~255자 `[A-Za-z0-9_-]+` 형식이 아님 | username 입력값 수정 유도 |
| 40004 | 409 | `KAFKA_PRINCIPAL_CONFLICT` | 같은 워크스페이스에 동일 username principal이 이미 존재 | "이미 사용 중인 username입니다" 안내 |
| 40005 | 409 | `KAFKA_PRINCIPAL_ALREADY_REVOKED` | revoked principal에 deactivate/revoke/rotate 요청 | revoked 상태 표시 후 변경 버튼 비활성화 |

> 생성 마법사 검증 실패(pattern↔sink 정합성·source/sink ownership·CDC readiness BLOCKED·중복 이름·동일 source+테이블+패턴)는 도메인 코드를 따로 두지 않고 `VALIDATION_FAILED`(90001)로 응답한다([pipeline.md §2](../design/backend-springboot/pipeline.md)). provisioner 부분 실패는 예외가 아니라 pipeline 상태를 `error`로 두고 `status_message`에 stage/errorCode를 남긴다.

### 서버 / 인프라 / 내부 오류 (50000~50999)

| 코드 | HTTP | 이름 | 트리거 | 클라이언트 권장 처리 |
|---|---|---|---|---|
| 50001 | 500 | `INTERNAL_ERROR` | 알 수 없는 서버 오류 (전역 핸들러 fallback) | "잠시 후 다시 시도해주세요" 안내, request_id를 운영팀에 전달 |

### 거버넌스 / 승인 / 변경관리 (60000~61999)

| 코드 | HTTP | 이름 | 트리거 | 클라이언트 권장 처리 |
|---|---|---|---|---|
| 60001 | 404 | `APPROVAL_NOT_FOUND` | approval id가 없거나 요청 tenant 범위에 속하지 않음 | approval 목록 새로고침 |
| 60002 | 410 | `APPROVAL_EXPIRED` | 승인 대기 또는 실행 검증 시 approval 만료 | 새 approval 요청 생성 |
| 60003 | 409 | `APPROVAL_ALREADY_DECIDED` | 이미 승인/거절된 approval에 decision 재시도 | 현재 approval 상태 새로고침 |
| 60004 | 409 | `APPROVAL_ALREADY_USED` | single-use approval을 다시 실행 검증에 사용 | 실행 상태 확인 후 새 approval 요청 |
| 60005 | 403 | `APPROVAL_SCOPE_MISMATCH` | tenant 또는 params_hash 등 approval scope 불일치 | action parameters와 approval 연결 재확인 |
| 61001 | 404 | `CHANGE_TICKET_NOT_FOUND` | change ticket id가 없거나 요청 tenant 범위에 속하지 않음 | change ticket 목록 새로고침 |
| 61002 | 403 | `CHANGE_TICKET_REQUIRED` | change ticket이 없거나 실행 가능한 상태가 아님 | 변경관리 ticket 생성/상태 확인 |

### 기타 / 도메인-횡단 (90000~99999)

| 코드 | HTTP | 이름 | 트리거 | 클라이언트 권장 처리 |
|---|---|---|---|---|
| 90001 | 400 | `VALIDATION_FAILED` | 요청 본문이 Bean Validation 검증 실패 | `details` 배열의 `field`/`reason`을 폼 위에 표시 |
| 90006 | 404 | `RESOURCE_NOT_FOUND` | 매핑되지 않은 경로 호출 | 요청 경로 확인 |
| 90007 | 405 | `METHOD_NOT_ALLOWED` | 매핑된 경로에 지원하지 않는 HTTP 메서드 호출 | API 문서의 메서드 확인 |
| 90008 | 415 | `UNSUPPORTED_MEDIA_TYPE` | 지원하지 않는 요청 Content-Type 사용 | `Content-Type` 헤더 확인 |

## 아카이브 (사용 금지)

| 코드 | 이전 이름 | 폐기 시점 | 사유 |
|---|---|---|---|

(현재 없음)

## 운영 규칙

- 새 `ErrorCode` enum 값 추가 시 **본 문서도 같은 PR로 갱신** 필수.
- 코드를 폐기할 때는 enum에서 제거 + 본 문서의 아카이브 섹션으로 이동. **번호 재사용 금지**.
- 같은 도메인에서 새 코드 추가 시 가장 큰 번호 + 1.
- HTTP 상태가 enum과 본 문서 사이에 불일치하면 enum이 정답이다(코드 우선).
