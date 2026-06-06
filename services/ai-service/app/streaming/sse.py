"""SSE wire-format helpers and event stream generator."""
from __future__ import annotations

from typing import AsyncGenerator

from app.persistence.event_repository import AnyEventRepo, InMemoryEventRepository
from app.schemas.events import StreamingEvent
from app.streaming.event_bus import EventBus


def format_sse(event: StreamingEvent) -> str:
    data = event.model_dump_json()
    return f"id: {event.event_id}\nevent: {event.type.value}\ndata: {data}\n\n"


async def stream_events(
    run_id: str,
    bus: EventBus,
    repo: AnyEventRepo,
    last_event_id: str | None,
) -> AsyncGenerator[str, None]:
    if isinstance(repo, InMemoryEventRepository):
        missed = repo.get_after(run_id, last_event_id)
    else:
        missed = await repo.get_after(run_id, last_event_id)

    for event in missed:
        yield format_sse(event)

    async for event in bus.subscribe(run_id):
        if isinstance(repo, InMemoryEventRepository):
            repo.append(run_id, event)
        else:
            await repo.append(run_id, event)
        yield format_sse(event)
