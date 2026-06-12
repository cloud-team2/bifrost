"""Action Execution API — action 조회·Run 트리거 엔드포인트."""
from __future__ import annotations

import uuid

from fastapi import APIRouter, BackgroundTasks
from pydantic import BaseModel

from app.persistence.run_repository import get_run_repo
from app.schemas import ApiResponse
from app.schemas.state import AgentMode
from app.streaming.event_bus import get_event_bus
from app.tools.registry import get_tool_registry
from app.workflow.runner import run_workflow

router = APIRouter()


def _req_id() -> str:
    return f"req_{uuid.uuid4().hex[:12]}"


def _run_id() -> str:
    return f"run_{uuid.uuid4().hex[:16]}"


class ActionRunRequest(BaseModel):
    project_id: str
    incident_id: str | None = None
    message: str | None = None
    action_candidates: list[dict] | None = None


class ApprovalDecisionRequest(BaseModel):
    project_id: str
    run_id: str
    message: str | None = None


@router.post("/actions/run")
async def trigger_action_execution(req: ActionRunRequest, background_tasks: BackgroundTasks) -> ApiResponse:
    """action_execution 모드로 새 run을 시작한다."""
    run_id = _run_id()
    request_id = _req_id()

    run_repo = get_run_repo()
    run_repo.create(
        run_id,
        AgentMode.ACTION_EXECUTION.value,
        project_id=req.project_id,
        user_message=req.message or "",
    )

    background_tasks.add_task(
        run_workflow,
        run_id=run_id,
        user_message=req.message or "",
        project_id=req.project_id,
        bus=get_event_bus(),
        run_repo=run_repo,
        registry=get_tool_registry(),
    )

    return ApiResponse.success(request_id, {
        "run_id": run_id,
        "mode": AgentMode.ACTION_EXECUTION.value,
        "event_stream_url": f"/api/v1/agent/runs/{run_id}/events",
        "status": "running",
    })


@router.post("/actions/approval-decision")
async def trigger_approval_decision(req: ApprovalDecisionRequest, background_tasks: BackgroundTasks) -> ApiResponse:
    """approval_decision 모드로 승인 후 실행을 재개한다."""
    run_id = _run_id()
    request_id = _req_id()

    run_repo = get_run_repo()
    run_repo.create(
        run_id,
        AgentMode.APPROVAL_DECISION.value,
        project_id=req.project_id,
        user_message=req.message or "",
    )

    background_tasks.add_task(
        run_workflow,
        run_id=run_id,
        user_message=req.message or "",
        project_id=req.project_id,
        bus=get_event_bus(),
        run_repo=run_repo,
        registry=get_tool_registry(),
    )

    return ApiResponse.success(request_id, {
        "run_id": run_id,
        "mode": AgentMode.APPROVAL_DECISION.value,
        "event_stream_url": f"/api/v1/agent/runs/{run_id}/events",
        "status": "running",
    })
