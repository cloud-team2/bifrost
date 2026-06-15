"""HTTP client for Spring Boot `/internal/ops` operations."""
from __future__ import annotations

from typing import Any

import httpx
from pydantic import ValidationError

from app.core.config import settings
from app.schemas.tools import SpringErrorCode, SpringOpsResponse, ToolContext, ToolError
from app.tools.context import DEFAULT_ACTOR_ID, spring_headers

# (#646) /internal/ops service-to-service 인증 헤더. ops-backend SecurityConfig 게이트와 짝.
INTERNAL_OPS_TOKEN_HEADER = "X-Internal-Token"


# Spring 가 raw list 를 result 로 반환하는 operation → ai-service 모델이 기대하는 wrapper key.
# (#390) 예: list_project_pipelines 는 List<PipelineResponse> 반환 → {pipelines: [...]} 로 wrap.
_LIST_RESULT_WRAPPER: dict[str, str] = {
    "list_project_pipelines": "pipelines",
}


# (#643) Spring 레거시 GlobalException body 의 숫자 code → SpringErrorCode 매핑.
# 표준 OpsEnvelope({ok, operation, error{code,message}}) 가 아니라 GlobalException
# 형식 {"code":"20003","message":"...","details":[]} 으로 내려오는 경로를 보정한다.
# 테이블은 작게 시작 — 확인된 코드만 추가하고, 미지의 코드는 HTTP status 폴백 처리.
# (관측되면 추가 후보: 2xxxx=도메인 not-found/conflict, 1xxxx=validation/auth 계열.)
_LEGACY_SPRING_CODE_MAP: dict[str, SpringErrorCode] = {
    "20003": SpringErrorCode.RESOURCE_NOT_FOUND,  # 프로젝트를 찾을 수 없습니다
}


def _legacy_error_code(status_code: int) -> tuple[SpringErrorCode, bool]:
    """알 수 없는 레거시 code 에 대한 HTTP status 기반 폴백 (code, retryable)."""
    if status_code == 404:
        return SpringErrorCode.RESOURCE_NOT_FOUND, False
    if status_code == 400:
        return SpringErrorCode.VALIDATION_FAILED, False
    if status_code in (401, 403):
        return SpringErrorCode.PERMISSION_DENIED, False
    if status_code >= 500:
        return SpringErrorCode.UPSTREAM_UNAVAILABLE, True
    return SpringErrorCode.INTERNAL_ERROR, False


def _is_legacy_spring_error(payload: Any) -> bool:
    """표준 envelope 가 아닌 레거시 GlobalException 에러 dict 인지 감지.

    레거시 body 는 `code`·`message` 키를 갖고, envelope 의 `ok`/`operation`/`result` 가 없다.
    """
    return (
        isinstance(payload, dict)
        and "code" in payload
        and "message" in payload
        and "ok" not in payload
        and "operation" not in payload
        and "result" not in payload
    )


class SpringOpsClient:
    def __init__(
        self,
        *,
        base_url: str | None = None,
        timeout: float | None = None,
        transport: httpx.AsyncBaseTransport | None = None,
        actor_id: str = DEFAULT_ACTOR_ID,
    ) -> None:
        self._base_url = base_url or settings.spring_ops_base_url
        self._timeout = timeout if timeout is not None else settings.spring_ops_timeout_seconds
        self._transport = transport
        self._actor_id = actor_id

    def _client(self) -> httpx.AsyncClient:
        # (#646) /internal/ops service-identity 토큰을 클라이언트 기본 헤더로 동봉 → health·preapproved·
        # 일반 operation 등 모든 호출이 게이트를 통과한다. 토큰이 비면 헤더 미동봉(게이트 비활성 호환).
        default_headers = (
            {INTERNAL_OPS_TOKEN_HEADER: settings.internal_ops_token}
            if settings.internal_ops_token
            else None
        )
        return httpx.AsyncClient(
            base_url=self._base_url,
            timeout=self._timeout,
            transport=self._transport,
            headers=default_headers,
        )

    async def health(self) -> bool:
        try:
            async with self._client() as client:
                response = await client.get("/internal/ops/health")
                return response.status_code == 200
        except httpx.HTTPError:
            return False

    async def create_preapproved(
        self,
        *,
        tenant_id: str,
        tool_name: str,
        params_hash: str,
    ) -> str | None:
        """ai-service HITL 승인 후 Spring MutationGate용 pre-approved approval 레코드를 생성.

        Returns Spring-generated approval UUID string, or None on failure (best-effort).
        """
        try:
            async with self._client() as client:
                response = await client.post(
                    "/internal/ops/approvals/preapproved",
                    json={
                        "tenantId": tenant_id,
                        "toolName": tool_name,
                        "paramsHash": params_hash,
                        "requiredApprover": "00000000-0000-0000-0000-000000000000",
                        "expiresInMinutes": 30,
                    },
                )
            if response.status_code in (200, 201):
                data = response.json()
                result = data.get("result", {})
                return result.get("approvalId")
        except Exception:
            pass
        return None

    async def request(
        self,
        *,
        method: str,
        path: str,
        operation: str,
        context: ToolContext,
        params: dict[str, Any] | None = None,
        json_body: dict[str, Any] | None = None,
    ) -> SpringOpsResponse:
        headers = spring_headers(context, actor_id=self._actor_id)
        async with self._client() as client:
            response = await client.request(
                method,
                path,
                headers=headers,
                params=params,
                json=json_body,
            )
        return self._parse_response(response, operation=operation, request_id=context.request_id)

    def _parse_response(self, response: httpx.Response, *, operation: str, request_id: str) -> SpringOpsResponse:
        try:
            payload = response.json()
        except ValueError:
            return SpringOpsResponse(
                ok=False,
                request_id=request_id,
                operation=operation,
                error=ToolError(
                    code=SpringErrorCode.INTERNAL_ERROR,
                    message=f"Spring returned non-JSON response with status {response.status_code}",
                    retryable=response.status_code >= 500,
                ),
            )

        # (#643) Spring 레거시 GlobalException 형식 ({"code":"20003","message":"...","details":[]}) 은
        # envelope 가 아니므로 model_validate 가 ValidationError → VALIDATION_FAILED 로 오변환된다.
        # 표준 envelope 검증 전에 감지해 적절한 SpringErrorCode 로 매핑.
        if _is_legacy_spring_error(payload):
            legacy_code = str(payload.get("code"))
            mapped = _LEGACY_SPRING_CODE_MAP.get(legacy_code)
            if mapped is not None:
                error_code, retryable = mapped, False
            else:
                error_code, retryable = _legacy_error_code(response.status_code)
            return SpringOpsResponse(
                ok=False,
                request_id=request_id,
                operation=operation,
                error=ToolError(
                    code=error_code,
                    message=str(payload.get("message") or f"Spring error {legacy_code}"),
                    retryable=retryable,
                ),
            )

        if isinstance(payload, dict) and "result" not in payload and "data" in payload:
            payload = {**payload, "result": payload["data"]}
        if isinstance(payload, dict):
            payload.setdefault("operation", operation)
            payload.setdefault("request_id", request_id)

            # Spring 가 result 로 raw list 를 반환하는 operation 은 ai-service 모델이 기대하는 wrapper 로 normalize.
            wrapper_key = _LIST_RESULT_WRAPPER.get(operation)
            if wrapper_key and isinstance(payload.get("result"), list):
                payload = {**payload, "result": {wrapper_key: payload["result"]}}

        try:
            return SpringOpsResponse.model_validate(payload)
        except ValidationError as exc:
            return SpringOpsResponse(
                ok=False,
                request_id=request_id,
                operation=operation,
                error=ToolError(
                    code=SpringErrorCode.VALIDATION_FAILED,
                    message=f"Invalid Spring response envelope: {exc.errors()[0]['msg']}",
                ),
            )
