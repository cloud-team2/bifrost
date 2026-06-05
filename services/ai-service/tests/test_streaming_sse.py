from __future__ import annotations

import asyncio
from datetime import UTC, datetime

import pytest

from app.persistence.event_repository import InMemoryEventRepository
from app.schemas.events import StreamingEvent, StreamingEventType
from app.streaming.event_bus import EventBus
from app.streaming.sse import format_sse, stream_events


def _make_event(event_id: str, run_id: str = "run_001") -> StreamingEvent:
    return StreamingEvent(
        event_id=event_id,
        run_id=run_id,
        timestamp=datetime.now(UTC),
        type=StreamingEventType.TOOL_CALL_STARTED,
        message="tool call started",
    )


def test_format_sse_wire_format():
    event = _make_event("evt_001")
    result = format_sse(event)

    lines = result.split("\n")
    assert lines[0] == "id: evt_001"
    assert lines[1] == "event: tool_call_started"
    assert lines[2].startswith("data: ")
    assert result.endswith("\n\n")


@pytest.mark.asyncio
async def test_stream_events_receives_published():
    bus = EventBus()
    repo = InMemoryEventRepository()
    event = _make_event("evt_001")

    collected: list[str] = []

    async def collect():
        async for chunk in stream_events("run_001", bus, repo, None):
            collected.append(chunk)
            return

    task = asyncio.create_task(collect())
    await asyncio.sleep(0)
    await bus.publish("run_001", event)
    await task

    assert len(collected) == 1
    assert "evt_001" in collected[0]
    assert "tool_call_started" in collected[0]


@pytest.mark.asyncio
async def test_replay_from_last_event_id():
    bus = EventBus()
    repo = InMemoryEventRepository()

    evt1 = _make_event("evt_001")
    evt2 = _make_event("evt_002")
    evt3 = _make_event("evt_003")
    repo.append("run_001", evt1)
    repo.append("run_001", evt2)
    repo.append("run_001", evt3)

    replayed: list[str] = []

    async def collect():
        async for chunk in stream_events("run_001", bus, repo, "evt_001"):
            replayed.append(chunk)

    # create_task so collect() runs concurrently; it replays evt_002/evt_003 then
    # blocks on bus.subscribe(). After sleep(0) transfers control there, we
    # close the run to unblock it.
    task = asyncio.create_task(collect())
    await asyncio.sleep(0)
    await bus.close_run("run_001")
    await asyncio.wait_for(task, timeout=1.0)

    assert len(replayed) == 2
    assert any("evt_002" in c for c in replayed)
    assert any("evt_003" in c for c in replayed)
    assert not any("evt_001" in c for c in replayed)


@pytest.mark.asyncio
async def test_replay_empty_when_no_missed():
    bus = EventBus()
    repo = InMemoryEventRepository()

    evt1 = _make_event("evt_001")
    repo.append("run_001", evt1)

    result = repo.get_after("run_001", "evt_001")
    assert result == []


@pytest.mark.asyncio
async def test_close_run_terminates_subscriber():
    bus = EventBus()
    repo = InMemoryEventRepository()

    received: list[StreamingEvent] = []

    async def consume():
        async for event in bus.subscribe("run_001"):
            received.append(event)

    task = asyncio.create_task(consume())
    await asyncio.sleep(0)
    await bus.publish("run_001", _make_event("evt_001"))
    await asyncio.sleep(0)
    await bus.close_run("run_001")
    await asyncio.wait_for(task, timeout=1.0)

    assert len(received) == 1
    assert received[0].event_id == "evt_001"
