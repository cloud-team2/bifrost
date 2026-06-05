"""In-memory event store for SSE replay on reconnect.

Replaced by a PostgreSQL-backed implementation in the persistence issue (⑤).
"""
from __future__ import annotations

from collections import defaultdict

from app.schemas.events import StreamingEvent


class InMemoryEventRepository:
    def __init__(self) -> None:
        self._store: dict[str, list[StreamingEvent]] = defaultdict(list)

    def append(self, run_id: str, event: StreamingEvent) -> None:
        self._store[run_id].append(event)

    def get_after(self, run_id: str, last_event_id: str | None) -> list[StreamingEvent]:
        events = self._store[run_id]
        if last_event_id is None:
            return list(events)
        for i, event in enumerate(events):
            if event.event_id == last_event_id:
                return list(events[i + 1:])
        return []


_repo = InMemoryEventRepository()


def get_event_repo() -> InMemoryEventRepository:
    return _repo
