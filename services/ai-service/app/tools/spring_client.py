"""HTTP client for Spring Boot `/internal/ops` operations."""
from __future__ import annotations

from typing import Any

import httpx
from pydantic import ValidationError

from app.core.config import settings
from app.schemas.tools import SpringErrorCode, SpringOpsResponse, ToolContext, ToolError
from app.tools.context import DEFAULT_ACTOR_ID, spring_headers


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
        return httpx.AsyncClient(
            base_url=self._base_url,
            timeout=self._timeout,
            transport=self._transport,
        )

    async def health(self) -> bool:
        try:
            async with self._client() as client:
                response = await client.get("/internal/ops/health")
                return response.status_code == 200
        except httpx.HTTPError:
            return False

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

        if isinstance(payload, dict) and "result" not in payload and "data" in payload:
            payload = {**payload, "result": payload["data"]}
        if isinstance(payload, dict):
            payload.setdefault("operation", operation)
            payload.setdefault("request_id", request_id)

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
