from __future__ import annotations

import pytest
from unittest.mock import AsyncMock

from app.agents.retrieval import run_retrieval
from app.persistence.event_repository import InMemoryEventRepository
from app.schemas.events import StreamingEventType
from app.schemas.outputs import PlannerOutput, RetrievalPlanStep
from app.schemas.tools import ToolContext, ToolError, ToolResult, ToolStatus, SpringErrorCode
from app.schemas.state import RiskLevel
from app.streaming.event_bus import EventBus


def _context() -> ToolContext:
    return ToolContext(
        run_id="r1",
        step_id="s1",
        agent_name="retrieval",
        project_id="p1",
        request_id="req1",
    )


def _plan(tool_name: str = "get_metrics") -> PlannerOutput:
    return PlannerOutput(
        retrieval_plan=[
            RetrievalPlanStep(
                step_id="s1",
                tool_name=tool_name,
                params={},
                purpose="test",
                depends_on=[],
                plan_hash="abc123",
            )
        ]
    )


@pytest.mark.asyncio
async def test_retrieval_success_emits_completed_not_failed() -> None:
    registry = AsyncMock()
    registry.call_tool.return_value = ToolResult(
        tool_name="get_metrics",
        status=ToolStatus.SUCCESS,
        risk=RiskLevel.READ_ONLY,
        summary="metric data",
        evidence_ids=[],
    )
    bus = EventBus()
    published = []
    bus.publish = AsyncMock(side_effect=lambda run_id, evt: published.append(evt))
    event_repo = InMemoryEventRepository()

    out = await run_retrieval("r1", _plan(), _context(), registry, bus, event_repo)

    types = [e.type for e in published]
    assert StreamingEventType.TOOL_CALL_COMPLETED in types
    assert StreamingEventType.TOOL_CALL_FAILED not in types
    assert "[mock]" not in out.evidence_items[0].summary
    assert "failed" not in out.evidence_items[0].store_ref


@pytest.mark.asyncio
async def test_retrieval_failure_emits_tool_call_failed() -> None:
    registry = AsyncMock()
    registry.call_tool.return_value = ToolResult(
        tool_name="get_metrics",
        status=ToolStatus.FAILED,
        risk=RiskLevel.READ_ONLY,
        summary="",
        error=ToolError(
            code=SpringErrorCode.TRANSIENT_ERROR,
            message="connection refused",
            retryable=True,
        ),
    )
    bus = EventBus()
    published = []
    bus.publish = AsyncMock(side_effect=lambda run_id, evt: published.append(evt))
    event_repo = InMemoryEventRepository()

    out = await run_retrieval("r1", _plan(), _context(), registry, bus, event_repo)

    types = [e.type for e in published]
    assert StreamingEventType.TOOL_CALL_FAILED in types
    assert StreamingEventType.TOOL_CALL_COMPLETED not in types
    assert "/failed" in out.evidence_items[0].store_ref
    assert "[mock]" not in out.evidence_items[0].summary
    assert "connection refused" in out.evidence_items[0].summary


@pytest.mark.asyncio
async def test_retrieval_timeout_emits_tool_call_failed() -> None:
    registry = AsyncMock()
    registry.call_tool.return_value = ToolResult(
        tool_name="get_metrics",
        status=ToolStatus.TIMEOUT,
        risk=RiskLevel.READ_ONLY,
        summary="",
        error=ToolError(
            code=SpringErrorCode.TIMEOUT,
            message="Spring operation timed out",
            retryable=True,
        ),
    )
    bus = EventBus()
    published = []
    bus.publish = AsyncMock(side_effect=lambda run_id, evt: published.append(evt))
    event_repo = InMemoryEventRepository()

    out = await run_retrieval("r1", _plan(), _context(), registry, bus, event_repo)

    types = [e.type for e in published]
    assert StreamingEventType.TOOL_CALL_FAILED in types
    assert StreamingEventType.TOOL_CALL_COMPLETED not in types
    assert "/failed" in out.evidence_items[0].store_ref
