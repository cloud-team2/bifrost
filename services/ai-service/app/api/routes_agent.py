"""Agent Run API — POST /runs 생성, GET /runs/{id} 조회."""
from __future__ import annotations

import json
import uuid
from typing import Any

from fastapi import APIRouter, BackgroundTasks
from pydantic import BaseModel

from app.persistence.message_repository import get_message_repo
from app.persistence.run_repository import get_run_repo
from app.persistence.state_repository import get_state_repo
from app.persistence.thread_repository import get_thread_repo
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
    owner: str | None = None  # #821 멀티 채팅방 소유자(자유 채팅 스레드 lazy 생성용)
    remediation_requested: bool = False
    stream: bool = True
    action_candidate: ActionCandidateOutput | None = None
    display_message: str | None = None  # #870 채팅에 표시·저장할 친근한 텍스트(LLM 입력은 message)


@router.post("/runs")
async def create_run(req: CreateRunRequest, background_tasks: BackgroundTasks) -> ApiResponse:
    run_id = _run_id()
    request_id = _request_id()
    user_message = req.message or ""
    # #870 사용자에게 보이고 thread에 저장될 텍스트(없으면 message 그대로).
    display_message = (req.display_message or "").strip() or user_message

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

    # #821 멀티 채팅방: 자유 채팅(chat-*) 스레드면 방을 보장(lazy 생성)·활동시각 갱신하고,
    # 아직 제목이 없으면 첫 사용자 메시지로 임시 제목을 단다. (인시던트 thread는 제외.)
    if thread_id and thread_id.startswith("chat-"):
        thread_repo = get_thread_repo()
        if req.owner:
            await thread_repo.ensure(id=thread_id, project_id=req.project_id, owner=req.owner)
        else:
            await thread_repo.touch(thread_id)
        if display_message.strip():
            await thread_repo.set_title_if_empty(thread_id, _preview(display_message, 40) or "새 대화")

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
        display_message=display_message,
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


@router.get("/runs/{run_id}/reproducibility")
async def get_run_reproducibility(run_id: str) -> ApiResponse:
    """#885 과거 run 의 재현성 manifest + 당시 입력·후보 랭킹을 재구성해 반환한다.

    run 시작 시 고정한 모델 스냅샷·프롬프트·카탈로그·코드 버전(manifest)과, RCA 가 낸
    root cause 후보 랭킹을 함께 돌려줘 "그때 그 판단"을 그대로 추적할 수 있게 한다.
    """
    request_id = _request_id()
    run_repo = get_run_repo()
    rec = await run_repo.get(run_id)
    if rec is None:
        return ApiResponse.failure(request_id, ErrorCode.RUN_NOT_FOUND, f"run not found: {run_id}")

    patches = await get_state_repo().get_patches(run_id)
    manifest = await run_repo.get_reproducibility(run_id)
    if manifest is None:
        # run record 테이블이 없거나 비었으면 append-only state patch 에서 복원한다.
        repro = [p.patch for p in patches if p.path == "/run/reproducibility"]
        manifest = repro[-1] if repro else None

    rankings = [p.patch for p in patches if p.path == "/analysis/root_cause_candidates"]
    root_cause_candidates = (
        rankings[-1].get("root_cause_candidates", []) if rankings else []
    )

    return ApiResponse.success(request_id, {
        "run_id": run_id,
        "user_message": getattr(rec, "user_message", None),
        "mode": rec.mode,
        "incident_id": getattr(rec, "incident_id", None),
        "reproducibility": manifest,
        "root_cause_candidates": root_cause_candidates,
    })


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


# ---------------------------------------------------------------- #821 멀티 채팅방(세션)

class CreateThreadRequest(BaseModel):
    project_id: str
    owner: str
    title: str | None = None


class RenameThreadRequest(BaseModel):
    title: str


def _preview(text: str | None, cap: int = 80) -> str | None:
    if not text:
        return None
    text = text.strip().replace("\n", " ")
    return f"{text[:cap]}…" if len(text) > cap else text


@router.get("/threads")
async def list_threads(project_id: str, owner: str, limit: int = 100) -> ApiResponse:
    """#821 멀티 채팅방: 내(owner) 채팅방을 최근 수정순으로 반환(제목·시간·마지막 답변 미리보기)."""
    request_id = _request_id()
    threads = await get_thread_repo().list(project_id=project_id, owner=owner, limit=limit)
    msg_repo = get_message_repo()
    items = []
    for thread in threads:
        last = await msg_repo.list_by_thread(thread.id, limit=1)
        data = thread.model_dump(mode="json")
        data["preview"] = _preview(last[-1].content if last else None)
        items.append(data)
    return ApiResponse.success(request_id, {"threads": items})


@router.post("/threads")
async def create_thread(req: CreateThreadRequest) -> ApiResponse:
    """#821 새 채팅방 생성. id는 백엔드가 부여한다(chat-<uuid>)."""
    request_id = _request_id()
    try:
        uuid.UUID(req.project_id)
    except ValueError:
        return ApiResponse.failure(
            request_id, ErrorCode.VALIDATION_FAILED, f"project_id must be a UUID: {req.project_id}"
        )
    thread_id = f"chat-{uuid.uuid4().hex}"
    thread = await get_thread_repo().create(
        id=thread_id, project_id=req.project_id, owner=req.owner, title=(req.title or None)
    )
    return ApiResponse.success(request_id, thread.model_dump(mode="json"))


@router.patch("/threads/{thread_id}")
async def rename_thread(thread_id: str, req: RenameThreadRequest) -> ApiResponse:
    """#821 채팅방 제목 변경."""
    request_id = _request_id()
    title = (req.title or "").strip()
    if not title:
        return ApiResponse.failure(request_id, ErrorCode.VALIDATION_FAILED, "title must not be empty")
    thread = await get_thread_repo().rename(thread_id, title[:200])
    if not thread:
        return ApiResponse.failure(request_id, ErrorCode.THREAD_NOT_FOUND, "thread not found")
    return ApiResponse.success(request_id, thread.model_dump(mode="json"))


@router.delete("/threads/{thread_id}")
async def delete_thread(thread_id: str) -> ApiResponse:
    """#821 채팅방 삭제(그 방의 대화 turn 포함)."""
    request_id = _request_id()
    await get_thread_repo().delete(thread_id)
    return ApiResponse.success(request_id, {"id": thread_id, "deleted": True})


class SaveToolTurnRequest(BaseModel):
    project_id: str
    owner: str | None = None
    request_text: str
    tool_name: str
    params: dict[str, Any] = {}
    result: Any = None


@router.post("/threads/{thread_id}/tool-turn")
async def save_tool_turn(thread_id: str, req: SaveToolTurnRequest) -> ApiResponse:
    """#860 명령 버튼(슬래시 커맨드) 결과를 thread에 저장(복원 전용).

    슬래시 커맨드는 agent run을 거치지 않아 대화가 저장되지 않았다. 여기서 사용자 요청(role=user)과
    툴 결과(role=tool, JSON)를 직접 append한다. role='tool'은 `_format_history`에서 제외되어 LLM
    컨텍스트를 오염시키지 않고 복원에만 쓰인다. 자유 채팅(chat-*) 스레드만 대상.
    """
    request_id = _request_id()
    if not thread_id.startswith("chat-"):
        return ApiResponse.success(request_id, {"saved": False})

    thread_repo = get_thread_repo()
    msg_repo = get_message_repo()
    if req.owner:
        await thread_repo.ensure(id=thread_id, project_id=req.project_id, owner=req.owner)

    request_text = (req.request_text or "").strip()
    if request_text:
        # 1) 사용자 요청(자연어) — LLM 히스토리에 포함된다.
        await msg_repo.append(thread_id, "user", request_text, project_id=req.project_id, run_id=None)
    # 2) 툴 결과 — role='tool'(복원 전용, _format_history에서 제외)로 구조 보존 저장.
    payload = json.dumps(
        {"tool_name": req.tool_name, "params": req.params, "result": req.result},
        ensure_ascii=False,
        default=str,
    )
    await msg_repo.append(thread_id, "tool", payload, project_id=req.project_id, run_id=None)
    await thread_repo.touch(thread_id)
    if request_text:
        await thread_repo.set_title_if_empty(thread_id, _preview(request_text, 40) or "새 대화")
    return ApiResponse.success(request_id, {"saved": True})
