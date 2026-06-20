"""#883 agent 실행 관측성(run telemetry) — 회귀 테스트.

stage별 timing, tool/agent 호출 수 집계, handoff 기록, 저장소 persist,
runner 통합(telemetry state_patch 생성), API 엔드포인트를 검증한다.
"""
from __future__ import annotations

import time
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import pytest
from fastapi.testclient import TestClient

from app.api import routes_agent
from app.main import app
from app.persistence.event_repository import InMemoryEventRepository
from app.persistence.run_repository import InMemoryRunRepository
from app.persistence.state_repository import InMemoryStateRepository
from app.schemas.outputs import (
    PlannerOutput,
    RetrievalOutput,
    RetrievalPlanStep,
    RouteDecision,
    RouterOutput,
)
from app.schemas.state import (
    AgentMode,
    EvidenceItem,
    EvidenceType,
    RedactionStatus,
)
from app.streaming.event_bus import EventBus
from app.supervisor.graph import Supervisor
from app.supervisor.retry_policy import RetryPolicy
from app.supervisor.state_store import InMemoryStateStore
from app.workflow.runner import run_workflow
from app.workflow.telemetry import RunTelemetryCollector

client = TestClient(app)


# ── 단위 테스트: RunTelemetryCollector ────────────────────────────────────────
def test_collector_records_stages() -> None:
    tc = RunTelemetryCollector("run_1")
    tc.start_stage("router", "router")
    tc.start_stage("planner", "planner")
    tc.start_stage("retrieval", "retrieval")
    tc.finish()
    s = tc.summary()
    assert s["total_stages"] == 3
    assert set(s["called_agents"]) == {"router", "planner", "retrieval"}


def test_collector_records_tool_calls() -> None:
    tc = RunTelemetryCollector("run_2")
    tc.start_stage("retrieval", "retrieval")
    tc.record_tool_call("get_metrics")
    tc.record_tool_call("get_metrics")
    tc.record_tool_call("get_connector_status")
    tc.finish()
    s = tc.summary()
    assert s["total_tool_calls"] == 3
    assert s["called_tools"]["get_metrics"] == 2
    assert s["called_tools"]["get_connector_status"] == 1


def test_collector_records_llm_calls() -> None:
    tc = RunTelemetryCollector("run_3")
    tc.start_stage("rca", "rca")
    tc.record_llm_call(estimated_tokens=500)
    tc.record_llm_call(estimated_tokens=300)
    tc.finish()
    s = tc.summary()
    assert s["total_llm_calls"] == 2
    assert s["total_estimated_tokens"] == 800


def test_collector_records_handoffs() -> None:
    tc = RunTelemetryCollector("run_4")
    tc.record_handoff("classifier", "planner", "scope_unclear")
    tc.finish()
    s = tc.summary()
    assert len(s["handoff_reasons"]) == 1
    assert s["handoff_reasons"][0]["from"] == "classifier"


def test_collector_records_errors() -> None:
    tc = RunTelemetryCollector("run_5")
    tc.start_stage("executor", "executor")
    tc.record_error("connection_timeout")
    tc.finish()
    s = tc.summary()
    assert s["stages"][0]["error"] == "connection_timeout"


def test_collector_latency_is_positive() -> None:
    tc = RunTelemetryCollector("run_6")
    tc.start_stage("router", "router")
    time.sleep(0.01)
    tc.finish()
    s = tc.summary()
    assert s["total_latency_ms"] > 0
    assert s["stages"][0]["latency_ms"] > 0


def test_collector_empty_run() -> None:
    tc = RunTelemetryCollector("run_empty")
    tc.finish()
    s = tc.summary()
    assert s["total_stages"] == 0
    assert s["total_tool_calls"] == 0
    assert s["called_agents"] == []


def test_summary_has_all_required_fields() -> None:
    tc = RunTelemetryCollector("run_fields")
    tc.finish()
    s = tc.summary()
    required = {
        "run_id", "total_latency_ms", "total_stages", "called_agents",
        "called_tools", "total_tool_calls", "total_llm_calls",
        "total_estimated_tokens", "stages", "handoff_reasons",
    }
    assert required.issubset(set(s.keys()))


# ── InMemory 저장소 ──────────────────────────────────────────────────────────
@pytest.mark.asyncio
async def test_in_memory_repo_telemetry_save_get() -> None:
    repo = InMemoryRunRepository()
    await repo.create("run_t1", "simple_query")
    telemetry = {"total_latency_ms": 123.4, "total_stages": 3, "called_agents": ["router"]}
    await repo.save_telemetry("run_t1", telemetry)
    got = await repo.get_telemetry("run_t1")
    assert got is not None
    assert got["total_latency_ms"] == 123.4


@pytest.mark.asyncio
async def test_in_memory_repo_telemetry_missing() -> None:
    repo = InMemoryRunRepository()
    assert await repo.get_telemetry("nonexistent") is None


# ── runner 통합: telemetry state_patch 생성 ──────────────────────────────────
def _router_out() -> RouterOutput:
    return RouterOutput(
        route_decision=RouteDecision(
            mode=AgentMode.SIMPLE_QUERY,
            remediation_requested=False,
            reason="test",
            required_flow=[],
        )
    )


def _planner_out() -> PlannerOutput:
    return PlannerOutput(
        retrieval_plan=[
            RetrievalPlanStep(
                step_id="s1", tool_name="get_metrics", params={},
                purpose="test", depends_on=[], plan_hash="h1",
            )
        ]
    )


def _retrieval_out() -> RetrievalOutput:
    from datetime import datetime, timezone
    return RetrievalOutput(
        evidence_items=[
            EvidenceItem(
                evidence_id="ev1",
                type=EvidenceType.TOOL_RESULT,
                store_ref="evidence://run/s1",
                summary="test metric",
                redaction_status=RedactionStatus.REDACTED,
                collected_by="retrieval",
                collected_at=datetime.now(timezone.utc),
            )
        ]
    )


@pytest.mark.asyncio
async def test_runner_creates_telemetry_patch() -> None:
    state_repo = InMemoryStateRepository()
    bus = EventBus()
    bus.publish = AsyncMock()
    run_repo = AsyncMock()

    with (
        patch("app.workflow.runner.router_agent.run_router", new_callable=AsyncMock) as mock_router,
        patch("app.workflow.runner.planner_agent.run_planner", new_callable=AsyncMock) as mock_planner,
        patch("app.workflow.runner.retrieval_agent.run_retrieval", new_callable=AsyncMock) as mock_retrieval,
        patch("app.workflow.runner.verifier_agent.run_verifier", new_callable=AsyncMock) as mock_verifier,
        patch("app.workflow.runner.report_agent.run_report", new_callable=AsyncMock) as mock_report,
        patch("app.workflow.runner.get_supervisor") as mock_get_sup,
        patch("app.workflow.runner.get_event_repo") as mock_get_event_repo,
        patch("app.workflow.runner.get_state_repo") as mock_get_state_repo,
        patch("app.workflow.runner.get_llm_provider"),
    ):
        mock_get_sup.return_value = Supervisor(store=InMemoryStateStore(), policy=RetryPolicy())
        mock_get_event_repo.return_value = InMemoryEventRepository()
        mock_get_state_repo.return_value = state_repo
        mock_router.return_value = _router_out()
        mock_planner.return_value = _planner_out()
        mock_retrieval.return_value = _retrieval_out()
        from app.schemas.outputs import VerificationResultOutput, VerifierOutput
        from app.schemas.state import VerificationStatus
        mock_verifier.return_value = VerifierOutput(
            verification_results=[
                VerificationResultOutput(
                    verification_id="v1", target="report",
                    status=VerificationStatus.PASS,
                    approved_for_final_response=True, reason="ok",
                )
            ]
        )
        mock_report.return_value = "분석 완료"

        await run_workflow(
            run_id="run_telem",
            user_message="현재 상태 확인",
            project_id="proj_001",
            bus=bus,
            run_repo=run_repo,
            registry=AsyncMock(),
        )

    patches = await state_repo.get_patches("run_telem")
    telem_patches = [p for p in patches if p.path == "/run/telemetry"]
    assert len(telem_patches) == 1
    telem = telem_patches[0].patch
    assert telem["run_id"] == "run_telem"
    assert telem["total_stages"] >= 1
    assert isinstance(telem["called_agents"], list)
    assert isinstance(telem["stages"], list)


# ── API 엔드포인트 ───────────────────────────────────────────────────────────
def test_telemetry_api_returns_data(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.persistence import state_repository as state_mod

    repo = InMemoryRunRepository()

    async def _seed():
        await repo.create("run_api_t", "simple_query")
        await repo.save_telemetry("run_api_t", {
            "total_latency_ms": 456.7,
            "total_stages": 4,
            "called_agents": ["router", "planner", "retrieval", "report"],
            "called_tools": {"get_metrics": 2},
            "total_tool_calls": 2,
        })

    import asyncio
    asyncio.run(_seed())
    monkeypatch.setattr(routes_agent, "get_run_repo", lambda: repo)
    monkeypatch.setattr(state_mod, "get_state_repo", lambda: InMemoryStateRepository())

    resp = client.get("/api/v1/agent/runs/run_api_t/telemetry")
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["run_id"] == "run_api_t"
    assert data["telemetry"]["total_latency_ms"] == 456.7


def test_telemetry_api_404(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(routes_agent, "get_run_repo", lambda: InMemoryRunRepository())
    resp = client.get("/api/v1/agent/runs/nope/telemetry")
    assert resp.status_code == 200
    assert resp.json()["ok"] is False
