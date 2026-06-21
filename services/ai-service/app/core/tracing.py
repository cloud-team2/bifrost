"""OpenTelemetry 분산 추적 부트스트랩 (#372).

ai-service(FastAPI) 자체를 계측해 에이전트 처리를 trace 에 포함하고, Spring `/internal/ops`
호출 시 `traceparent` 를 전파해 한 trace 로 잇는다(#366 ops-backend ↔ #370 Collector).

설계 메모:
- 들어오는 요청은 `FastAPIInstrumentor` 가 server span 생성 + `traceparent` 추출.
- 나가는 Spring 호출(`httpx`)은 `HTTPXClientInstrumentor`(전역) 가 client span 생성 + `traceparent` 주입.
- 에이전트 run 은 FastAPI `BackgroundTasks` 로 요청 핸들러 span **밖에서** 실행되므로,
  자동 계측만으로는 run 전체가 한 trace 로 묶이지 않는다. → runner 가 `run_span()` 으로
  루트 span 을 직접 열어 그 안의 Spring 호출들을 자식으로 묶는다.
- 엔드포인트가 비면 계측을 건너뛴다(로컬/CI 는 collector 불필요). provider 미설정 시
  `get_tracer()` 는 no-op tracer 를 돌려주므로 `run_span()` 호출은 항상 안전하다.
"""
from __future__ import annotations

import logging
from typing import Any

from opentelemetry import trace
from opentelemetry.sdk.resources import SERVICE_NAME, SERVICE_VERSION, Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.sdk.trace.sampling import ParentBased, TraceIdRatioBased

from app.core.config import settings

logger = logging.getLogger(__name__)

TRACER_NAME = "bifrost-ai-service"
RUN_SPAN_NAME = "agent.run"

# 중복 계측 방지(부트스트랩/테스트에서 setup_tracing 이 여러 번 호출돼도 안전).
_instrumented = False


def get_tracer() -> trace.Tracer:
    """전역 tracer. provider 미설정이면 no-op tracer 를 돌려준다(안전)."""
    return trace.get_tracer(TRACER_NAME)


def run_span(
    tracer: trace.Tracer | None = None,
    *,
    run_id: str,
    project_id: str,
    mode: str | None = None,
    incident_id: str | None = None,
):
    """에이전트 run 루트 span. BackgroundTask 컨텍스트에서 호출해 run 전체를 한 trace 로 묶는다.

    `with run_span(...):` 형태로 사용. provider 미설정 시 no-op 으로 동작한다.
    """
    attributes: dict[str, Any] = {
        "bifrost.run_id": run_id,
        "bifrost.project_id": project_id,
    }
    if mode:
        attributes["bifrost.mode"] = mode
    if incident_id:
        attributes["bifrost.incident_id"] = incident_id
    active = tracer or get_tracer()
    return active.start_as_current_span(RUN_SPAN_NAME, attributes=attributes)


def set_current_run_mode(mode: str) -> None:
    """현재 run span 에 최종 mode 를 기록한다(router 결정 이후). 비활성 시 no-op."""
    trace.get_current_span().set_attribute("bifrost.mode", mode)


def _patch_otel_route_details() -> bool:
    """OTel instrumentation(0.63b1) ↔ FastAPI ``_IncludedRouter`` 비호환 우회.

    OTel ``_get_route_details`` 는 ``app.routes`` 를 순회하며 매칭된 라우트의 ``.path`` 로
    span 이름을 만든다. FULL 매치 분기는 ``.path`` 가 없을 때 AttributeError 를 방어하지만,
    **PARTIAL 매치 분기는 방어하지 않는다**. FastAPI 신버전이 ``.path`` 가 없는
    ``_IncludedRouter(BaseRoute)`` 를 ``app.routes`` 에 넣으면서, 이게 PARTIAL 로 매치되는
    요청(예: bare collection ``GET /api/v1/agent/runs``)이 핸들러 실행 전에 AttributeError →
    500 으로 죽는다. PARTIAL 분기도 FULL 처럼 방어하도록 교체한다.
    (upstream 이 고치면 무해하게 중복될 뿐이며, 계측이 켜질 때만 적용한다.)
    """
    try:
        from opentelemetry.instrumentation import fastapi as _otel_fastapi
        from starlette.routing import Match, Route
    except Exception:  # pragma: no cover - instrumentation 미설치 환경
        return False

    def _safe_get_route_details(scope: Any):
        app = scope.get("app")
        if app is None:
            return scope.get("path")
        route = None
        for starlette_route in app.routes:
            try:
                match, _ = (
                    Route.matches(starlette_route, scope)
                    if isinstance(starlette_route, Route)
                    else starlette_route.matches(scope)
                )
            except Exception:  # pragma: no cover - 방어적
                continue
            if match == Match.FULL:
                route = getattr(starlette_route, "path", None) or scope.get("path")
                break
            if match == Match.PARTIAL:
                route = getattr(starlette_route, "path", None) or scope.get("path")
        return route

    _otel_fastapi._get_route_details = _safe_get_route_details
    return True


def setup_tracing(
    app: Any,
    *,
    endpoint: str | None = None,
    sample_rate: float | None = None,
) -> bool:
    """OTel SDK + FastAPI/httpx 자동 계측을 설정한다.

    엔드포인트가 비면 계측을 건너뛰고 False 를 반환한다. 성공 시 True.
    여러 번 호출돼도 한 번만 계측한다(idempotent).
    """
    global _instrumented

    resolved_endpoint = settings.otlp_tracing_endpoint if endpoint is None else endpoint
    if not resolved_endpoint:
        logger.info("tracing disabled (no OTLP endpoint) — set AI_OTLP_TRACING_ENDPOINT to enable")
        return False

    if _instrumented:
        return True

    resolved_rate = settings.tracing_sample_rate if sample_rate is None else sample_rate

    # 지연 import: 계측이 켜질 때만 instrumentation 패키지를 로드한다.
    from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
    from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
    from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor

    # FastAPI _IncludedRouter ↔ OTel PARTIAL-매치 .path 크래시 우회(정상 엔드포인트 500 방지).
    _patch_otel_route_details()

    resource = Resource.create(
        {SERVICE_NAME: settings.app_name, SERVICE_VERSION: settings.version}
    )
    provider = TracerProvider(
        resource=resource,
        sampler=ParentBased(TraceIdRatioBased(resolved_rate)),
    )
    provider.add_span_processor(
        BatchSpanProcessor(OTLPSpanExporter(endpoint=resolved_endpoint))
    )
    trace.set_tracer_provider(provider)

    # 들어오는 요청: server span + traceparent 추출.
    FastAPIInstrumentor.instrument_app(app)
    # 나가는 Spring 호출(httpx): client span + traceparent 주입.
    HTTPXClientInstrumentor().instrument()

    _instrumented = True
    logger.info(
        "tracing enabled → %s (service=%s, sample_rate=%s)",
        resolved_endpoint,
        settings.app_name,
        resolved_rate,
    )
    return True
