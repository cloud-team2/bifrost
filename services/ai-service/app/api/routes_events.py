"""SSE endpoint: GET /runs/{run_id}/events + history catchup: GET /runs/{run_id}/events/history."""
from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends, Request
from fastapi.responses import StreamingResponse

from app.persistence.event_repository import InMemoryEventRepository, get_event_repo
from app.schemas import ApiResponse
from app.schemas.events import EventHistory
from app.streaming.event_bus import EventBus, get_event_bus
from app.streaming.sse import AnyEventRepo, stream_events

router = APIRouter()


def _request_id() -> str:
    return f"req_{uuid.uuid4().hex[:12]}"


@router.get("/runs/{run_id}/events")
async def stream_run_events(
    run_id: str,
    request: Request,
    bus: EventBus = Depends(get_event_bus),
    repo: AnyEventRepo = Depends(get_event_repo),
) -> StreamingResponse:
    last_event_id = request.headers.get("Last-Event-ID")
    return StreamingResponse(
        stream_events(run_id, bus, repo, last_event_id),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@router.get("/runs/{run_id}/events/history")
async def get_run_events_history(
    run_id: str,
    last_event_id: str | None = None,
    repo: AnyEventRepo = Depends(get_event_repo),
) -> ApiResponse:
    """SSE 끊김 시 missed event catchup 용 plain JSON endpoint (#393).

    - `?last_event_id=<id>` query 지정 시 그 event 이후만 반환. 없으면 전체.
    - SSE 가 아닌 plain JSON 응답이라 client 가 reload 후 fetch 하기 적합.
    - InMemory + Postgres event repo 양쪽 호환.
    """
    request_id = _request_id()
    if isinstance(repo, InMemoryEventRepository):
        events = repo.get_after(run_id, last_event_id)
    else:
        events = await repo.get_after(run_id, last_event_id)

    history = EventHistory(run_id=run_id, events=events, next_cursor=None)
    return ApiResponse.success(request_id, history.model_dump(mode="json"))
