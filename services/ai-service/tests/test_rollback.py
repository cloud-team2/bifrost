"""#886 조치 실패 시 자동 롤백 — 회귀 테스트.

실패한 mutation 이 risk-tier 정책에 따라 자동 원복되는지, high-risk 원조치는 롤백
실행이 승인 대상으로 보류되는지, inverse 가 없으면 건너뛰는지, 그리고 Executor 가
조치 전 상태 스냅샷을 남기는지 검증한다.
"""
from __future__ import annotations

import httpx
import pytest

from app.schemas.outputs import ExecutionResultOutput
from app.schemas.state import (
    ActionCandidate,
    ActionStatus,
    ActionType,
    RiskLevel,
    RollbackStatus,
)
from app.schemas.tools import ToolContext
from app.tools.registry import ToolClientRegistry
from app.workflow.stages.executor import run_executor
from app.workflow.stages.rollback import run_rollback


def _context() -> ToolContext:
    return ToolContext(
        run_id="run_rbk",
        step_id="s",
        agent_name="executor",
        project_id="proj_test",
        request_id="req",
    )


def _ok(operation: str, result: dict) -> httpx.Response:
    return httpx.Response(
        200, json={"ok": True, "request_id": "r", "operation": operation, "result": result}
    )


def _candidate(action_id: str, tool_name: str, *, connector="orders-src") -> ActionCandidate:
    return ActionCandidate(
        action_id=action_id,
        action_type=ActionType.RUNTIME_TOOL,
        action_name=connector,
        risk=RiskLevel.MEDIUM,
        reason="test",
        status=ActionStatus.READY,
        tool_name=tool_name,
        tool_params={"connector_name": connector},
    )


def _failed_execution(action_id: str, tool_name: str, *, snapshot: str | None = None):
    return ExecutionResultOutput(
        action_id=action_id,
        tool_name=tool_name,
        status=ActionStatus.FAILED,
        summary="조치 실패",
        pre_change_snapshot=snapshot,
    )


# ── medium 원조치 실패 → 자동 롤백(inverse) 실행 ────────────────────────────────
@pytest.mark.asyncio
async def test_medium_action_failure_auto_rolls_back() -> None:
    calls: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request.url.path)
        return _ok("resume_connector", {"connector_name": "orders-src", "action": "resume", "status": "ok"})

    registry = ToolClientRegistry(transport=httpx.MockTransport(handler))
    out = await run_rollback(
        [_failed_execution("act1", "pause_connector")],
        [_candidate("act1", "pause_connector")],
        run_id="run_rbk",
        context=_context(),
        registry=registry,
    )

    assert len(out.rollback_results) == 1
    rbk = out.rollback_results[0]
    assert rbk.rollback_status == RollbackStatus.COMPLETED
    assert rbk.tool_name == "resume_connector"
    assert rbk.original_action_id == "act1"
    assert rbk.rollback_audit_event_id is not None
    assert any("resume" in path for path in calls)


@pytest.mark.asyncio
async def test_resume_failure_rolls_back_with_pause() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return _ok("pause_connector", {"connector_name": "orders-src", "action": "pause", "status": "ok"})

    registry = ToolClientRegistry(transport=httpx.MockTransport(handler))
    out = await run_rollback(
        [_failed_execution("act2", "resume_connector")],
        [_candidate("act2", "resume_connector")],
        run_id="run_rbk",
        context=_context(),
        registry=registry,
    )
    assert out.rollback_results[0].tool_name == "pause_connector"
    assert out.rollback_results[0].rollback_status == RollbackStatus.COMPLETED


# ── high 원조치 실패 → 롤백 실행도 승인 대상(자동 실행 안 함) ─────────────────────
@pytest.mark.asyncio
async def test_high_risk_action_rollback_requires_approval() -> None:
    calls: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request.url.path)
        return _ok("noop", {})

    registry = ToolClientRegistry(transport=httpx.MockTransport(handler))
    out = await run_rollback(
        [_failed_execution("act3", "restart_connector", snapshot="snapshot://before/act3/RUNNING")],
        [_candidate("act3", "restart_connector")],
        run_id="run_rbk",
        context=_context(),
        registry=registry,
    )
    rbk = out.rollback_results[0]
    assert rbk.rollback_status == RollbackStatus.PENDING_APPROVAL
    assert rbk.tool_name == "resume_connector"  # 복원 대상 inverse 는 제안되지만 실행은 보류
    assert calls == []  # high-risk 롤백은 자동 실행하지 않는다


# ── inverse 가 없는 조치 → NOT_APPLICABLE ──────────────────────────────────────
@pytest.mark.asyncio
async def test_no_inverse_action_is_not_applicable() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return _ok("noop", {})

    registry = ToolClientRegistry(transport=httpx.MockTransport(handler))
    candidate = ActionCandidate(
        action_id="act4",
        action_type=ActionType.RUNTIME_TOOL,
        action_name="connect-orders",
        risk=RiskLevel.HIGH,
        reason="test",
        status=ActionStatus.READY,
        tool_name="restart_consumer_group",
        tool_params={"consumer_group": "connect-orders"},
    )
    out = await run_rollback(
        [_failed_execution("act4", "restart_consumer_group")],
        [candidate],
        run_id="run_rbk",
        context=_context(),
        registry=registry,
    )
    assert out.rollback_results[0].rollback_status == RollbackStatus.NOT_APPLICABLE


# ── 성공한 조치는 롤백하지 않는다 ───────────────────────────────────────────────
@pytest.mark.asyncio
async def test_completed_action_is_not_rolled_back() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        pytest.fail("성공한 조치에는 롤백을 호출하면 안 된다")

    registry = ToolClientRegistry(transport=httpx.MockTransport(handler))
    completed = ExecutionResultOutput(
        action_id="act5", tool_name="pause_connector", status=ActionStatus.COMPLETED, summary="ok"
    )
    out = await run_rollback(
        [completed],
        [_candidate("act5", "pause_connector")],
        run_id="run_rbk",
        context=_context(),
        registry=registry,
    )
    assert out.rollback_results == []


# ── Executor 가 조치 전 상태 스냅샷을 남긴다 ──────────────────────────────────────
@pytest.mark.asyncio
async def test_executor_captures_pre_change_snapshot() -> None:
    calls: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request.url.path)
        if "status" in request.url.path or request.method == "GET":
            return _ok(
                "get_connector_status",
                {"connectorName": "orders-src", "connectorState": "RUNNING", "tasks": []},
            )
        return _ok("pause_connector", {"connector_name": "orders-src", "action": "pause", "status": "ok"})

    registry = ToolClientRegistry(transport=httpx.MockTransport(handler))
    candidate = ActionCandidate(
        action_id="act6",
        action_type=ActionType.RUNTIME_TOOL,
        action_name="orders-src",
        risk=RiskLevel.MEDIUM,
        reason="test",
        status=ActionStatus.READY,
        tool_name="pause_connector",
        tool_params={"connector_name": "orders-src"},
    )
    out = await run_executor(
        [candidate],
        run_id="run_snap",
        context=_context(),
        registry=registry,
        capture_pre_change_snapshot=True,
    )
    result = out.execution_results[0]
    assert result.pre_change_snapshot is not None
    assert result.pre_change_snapshot.startswith("snapshot://before/act6/")
    assert "RUNNING" in result.pre_change_snapshot
    # 스냅샷 read + mutation 두 번 호출된다.
    assert len(calls) == 2
