"""Agent Run API — POST /runs 생성, GET /runs/{id} 조회."""
from __future__ import annotations

import uuid

from fastapi import APIRouter, BackgroundTasks
from pydantic import BaseModel

from app.persistence.message_repository import get_message_repo
from app.persistence.run_repository import get_run_repo
from app.schemas import ApiResponse, ErrorCode
from app.schemas.outputs import ActionCandidateOutput
from app.streaming.event_bus import get_event_bus
from app.tools.registry import get_tool_registry
from app.workflow.runner import run_workflow

router = APIRouter()


def _request_id() -> str:
    return f"req_{uuid.uuid4().hex[:12]}"


def _run_id() -> str:
    return f"run_{uuid.uuid4().hex[:16]}"


class CreateRunRequest(BaseModel):
    project_id: str
    mode: str | None = None
    message: str | None = None
    incident_id: str | None = None
    thread_id: str | None = None
    remediation_requested: bool = False
    stream: bool = True
    action_candidate: ActionCandidateOutput | None = None


@router.post("/runs")
async def create_run(req: CreateRunRequest, background_tasks: BackgroundTasks) -> ApiResponse:
    run_id = _run_id()
    request_id = _request_id()
    user_message = req.message or ""

    # #592: agent_run.project_id는 uuid 컬럼이라 비 UUID 문자열(예: "demo-team")이
    # INSERT 시점에 asyncpg 예외 → 500이 됐다. 검증 실패 envelope으로 거른다.
    try:
        uuid.UUID(req.project_id)
    except ValueError:
        return ApiResponse.failure(
            request_id,
            ErrorCode.VALIDATION_FAILED,
            f"project_id must be a UUID: {req.project_id}",
        )

    run_repo = get_run_repo()
    await run_repo.create(
        run_id,
        req.mode or "simple_query",
        project_id=req.project_id,
        incident_id=req.incident_id,
        remediation_requested=req.remediation_requested,
        user_message=user_message,
    )

    # #712 대화 메모리: 인시던트 채팅은 incident_id가 thread, 그 외는 명시 thread_id.
    # 멀티턴 컨텍스트 유지를 위해 workflow에 thread를 넘긴다(저장·주입은 workflow가 담당).
    thread_id = req.thread_id or req.incident_id

    background_tasks.add_task(
        run_workflow,
        run_id=run_id,
        user_message=user_message,
        project_id=req.project_id,
        bus=get_event_bus(),
        run_repo=run_repo,
        registry=get_tool_registry(),
        requested_mode=req.mode,
        requested_incident_id=req.incident_id,
        requested_remediation_requested=req.remediation_requested,
        requested_action_candidate=req.action_candidate,
        thread_id=thread_id,
        message_repo=get_message_repo(),
    )

    return ApiResponse.success(request_id, {
        "run_id": run_id,
        "event_stream_url": f"/api/v1/agent/runs/{run_id}/events",
        "status": "running",
    })


@router.get("/runs/{run_id}")
async def get_run(run_id: str) -> ApiResponse:
    request_id = _request_id()
    rec = await get_run_repo().get(run_id)
    if rec is None:
        return ApiResponse.failure(request_id, ErrorCode.RUN_NOT_FOUND, f"run not found: {run_id}")
    return ApiResponse.success(request_id, rec.model_dump())


@router.get("/threads/{thread_id}/messages")
async def list_thread_messages(thread_id: str, limit: int = 50) -> ApiResponse:
    """#712 대화 메모리: 한 thread(인시던트 채팅은 incident_id)의 대화 turn을 시간순 반환.

    프론트가 인시던트 상세 진입 시 이전 대화를 복원하는 데 쓴다.
    """
    request_id = _request_id()
    messages = await get_message_repo().list_by_thread(thread_id, limit=limit)
    return ApiResponse.success(request_id, {
        "thread_id": thread_id,
        "messages": [m.model_dump(mode="json") for m in messages],
    })
