"""OTel instrumentation ↔ FastAPI ``_IncludedRouter`` 비호환 회귀 방지.

OTel ``_get_route_details`` 의 PARTIAL 매치 분기가 ``.path`` 없는 라우트
(예: FastAPI 신버전의 ``_IncludedRouter``)에서 AttributeError 로 죽으면, 정상
엔드포인트(bare collection ``GET /api/v1/agent/runs`` 등)가 핸들러 실행 전 500 이 된다.
``_patch_otel_route_details`` 가 PARTIAL 분기도 방어해 scope path 로 폴백하는지 검증한다.
"""
from __future__ import annotations

import pytest

from app.core.tracing import _patch_otel_route_details


def test_partial_match_without_path_does_not_crash() -> None:
    pytest.importorskip("opentelemetry.instrumentation.fastapi")
    from starlette.routing import Match

    assert _patch_otel_route_details() is True
    from opentelemetry.instrumentation import fastapi as otel

    class FakeIncludedRouter:  # .path 가 없는 _IncludedRouter 흉내
        def matches(self, scope):
            return (Match.PARTIAL, {})

    class FakeApp:
        routes = [FakeIncludedRouter()]

    scope = {
        "app": FakeApp(),
        "path": "/api/v1/agent/runs",
        "type": "http",
        "method": "GET",
    }

    # 패치 전 구현이라면 AttributeError 로 죽는다. 패치 후엔 scope path 로 폴백.
    assert otel._get_route_details(scope) == "/api/v1/agent/runs"


def test_full_match_with_path_is_preserved() -> None:
    pytest.importorskip("opentelemetry.instrumentation.fastapi")
    from starlette.routing import Match

    assert _patch_otel_route_details() is True
    from opentelemetry.instrumentation import fastapi as otel

    class FakeRoute:
        path = "/api/v1/agent/runs/{run_id}/approvals"

        def matches(self, scope):
            return (Match.FULL, {})

    class FakeApp:
        routes = [FakeRoute()]

    scope = {"app": FakeApp(), "path": "/x", "type": "http", "method": "GET"}
    assert otel._get_route_details(scope) == "/api/v1/agent/runs/{run_id}/approvals"
