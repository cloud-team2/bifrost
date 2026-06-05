"""In-process pub/sub bus for SSE streaming events."""
from __future__ import annotations

import asyncio
from collections import defaultdict
from typing import AsyncGenerator

from app.schemas.events import StreamingEvent


class EventBus:
    def __init__(self) -> None:
        self._subscribers: dict[str, list[asyncio.Queue[StreamingEvent | None]]] = defaultdict(list)

    async def publish(self, run_id: str, event: StreamingEvent) -> None:
        for queue in list(self._subscribers[run_id]):
            await queue.put(event)

    async def subscribe(self, run_id: str) -> AsyncGenerator[StreamingEvent, None]:
        queue: asyncio.Queue[StreamingEvent | None] = asyncio.Queue()
        self._subscribers[run_id].append(queue)
        try:
            while True:
                event = await queue.get()
                if event is None:
                    break
                yield event
        finally:
            try:
                self._subscribers[run_id].remove(queue)
            except ValueError:
                pass

    async def close_run(self, run_id: str) -> None:
        for queue in list(self._subscribers[run_id]):
            await queue.put(None)


event_bus = EventBus()


def get_event_bus() -> EventBus:
    return event_bus
