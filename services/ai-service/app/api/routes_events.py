"""SSE endpoint: GET /runs/{run_id}/events"""
from __future__ import annotations

from fastapi import APIRouter, Depends, Request
from fastapi.responses import StreamingResponse

from app.persistence.event_repository import get_event_repo
from app.streaming.event_bus import EventBus, get_event_bus
from app.streaming.sse import AnyEventRepo, stream_events

router = APIRouter()


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
