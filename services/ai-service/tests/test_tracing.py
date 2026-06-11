"""OTel 계측 부트스트랩 (#372) 단위 테스트.

전역 상태(TracerProvider·httpx 계측)를 건드리지 않는 안전한 단위만 검증한다.
실제 export·전파(Spring↔FastAPI 한 trace)는 라이브 클러스터에서 검증한다(#370과 동일 방식).
"""
from __future__ import annotations

from fastapi import FastAPI
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from app.core.config import Settings
from app.core.tracing import RUN_SPAN_NAME, get_tracer, run_span, setup_tracing


def test_tracing_settings_defaults():
    # 기본은 비활성(엔드포인트 미설정) → 로컬/CI는 collector 없이 동작한다.
    s = Settings()
    assert s.otlp_tracing_endpoint == ""
    assert s.tracing_sample_rate == 1.0


def test_setup_tracing_returns_false_when_disabled():
    # 엔드포인트가 없으면 계측을 건너뛰고 False 반환(예외/전역 변경 없음).
    app = FastAPI()
    assert setup_tracing(app, endpoint="", sample_rate=1.0) is False


def test_get_tracer_noop_span_without_setup():
    # provider 미설정이어도 no-op span 으로 안전하게 동작해야 한다.
    tracer = get_tracer()
    with tracer.start_as_current_span("unit.test") as span:
        assert span is not None


def test_run_span_records_name_and_attributes():
    # run_span 이 약속한 span 이름/속성 컨벤션을 만든다(로컬 provider 로 격리 검증).
    exporter = InMemorySpanExporter()
    provider = TracerProvider()
    provider.add_span_processor(SimpleSpanProcessor(exporter))
    tracer = provider.get_tracer("test")

    with run_span(
        tracer,
        run_id="run_abc",
        project_id="proj_1",
        mode="incident_analysis",
        incident_id="inc_9",
    ):
        pass

    spans = exporter.get_finished_spans()
    assert len(spans) == 1
    span = spans[0]
    assert span.name == RUN_SPAN_NAME
    assert span.attributes["bifrost.run_id"] == "run_abc"
    assert span.attributes["bifrost.project_id"] == "proj_1"
    assert span.attributes["bifrost.mode"] == "incident_analysis"
    assert span.attributes["bifrost.incident_id"] == "inc_9"


def test_run_span_omits_optional_attributes_when_absent():
    # mode/incident_id 가 없으면 속성에 넣지 않는다(빈 값 노이즈 방지).
    exporter = InMemorySpanExporter()
    provider = TracerProvider()
    provider.add_span_processor(SimpleSpanProcessor(exporter))
    tracer = provider.get_tracer("test")

    with run_span(tracer, run_id="run_x", project_id="proj_x"):
        pass

    span = exporter.get_finished_spans()[0]
    assert "bifrost.mode" not in span.attributes
    assert "bifrost.incident_id" not in span.attributes
