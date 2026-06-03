"""Tool Client Registry — Spring Boot `/internal/ops` 위임 클라이언트 (스캐폴드).

Agent는 K8s/Kafka/Prometheus에 직접 접근하지 않는다. 모든 운영 조회/조치는 논리 tool 이름으로
요청하고, 이 레지스트리가 Spring Boot operation(allowlist·schema·risk)으로 매핑해 호출한다.
read는 자동, mutation은 승인된 action만 Executor가 실행한다 (design fastapi/DETAILS.md Tool Catalog).
"""
from __future__ import annotations

import httpx

from app.core.config import settings


class ToolClientRegistry:
    """Spring `/internal/ops` 호출용 httpx 래퍼 (스캐폴드 — operation 매핑은 후속 구현)."""

    def __init__(self, base_url: str | None = None) -> None:
        self._base_url = base_url or settings.spring_ops_base_url
        self._timeout = settings.spring_ops_timeout_seconds

    def _client(self) -> httpx.AsyncClient:
        # Agent run 식별 헤더(X-Agent-*)는 호출 시점에 주입한다.
        return httpx.AsyncClient(base_url=self._base_url, timeout=self._timeout)

    async def health(self) -> bool:
        """Spring Operations Backend 연결 확인 (/internal/ops/health)."""
        try:
            async with self._client() as client:
                resp = await client.get("/internal/ops/health")
                return resp.status_code == 200
        except httpx.HTTPError:
            return False
