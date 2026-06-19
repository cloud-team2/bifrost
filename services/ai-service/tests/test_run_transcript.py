"""#874 런 응답(구조화 tool 패널 + 최종 답변) thread 저장 테스트."""
from __future__ import annotations

import json
from datetime import datetime, timezone

import pytest

from app.persistence.event_repository import InMemoryEventRepository
from app.persistence.message_repository import InMemoryMessageRepository
from app.schemas.events import StreamingEvent, StreamingEventType
from app.workflow import runner


def _event(run_id, type_, message="", payload=None):
    return StreamingEvent(
        event_id=f"ev_{type_.value}_{len(message)}",
        run_id=run_id,
        timestamp=datetime.now(timezone.utc),
        type=type_,
        agent="retrieval",
        message=message,
        payload=payload or {},
    )


@pytest.mark.asyncio
async def test_persist_run_transcript_saves_structured_tools_and_terminal_answer(monkeypatch):
    run_id = "run_874"
    thread_id = "chat-room-874"
    event_repo = InMemoryEventRepository()
    # result가 있는 구조화 tool → 패널로 저장.
    event_repo.append(run_id, _event(
        run_id, StreamingEventType.TOOL_CALL_COMPLETED, "커넥터 상태",
        {"tool": "get_connector_status", "params": {"connector_name": "orders-sink"}, "result": {"state": "RUNNING"}},
    ))
    # result 없는 tool 이벤트(지식 검색 등)는 패널로 저장하지 않는다.
    event_repo.append(run_id, _event(
        run_id, StreamingEventType.TOOL_CALL_COMPLETED, "지식 근거 3건", {"tool": "kb_search", "count": 3},
    ))
    event_repo.append(run_id, _event(
        run_id, StreamingEventType.RUN_COMPLETED, "조치 승인이 거절되어 실행을 중단했습니다",
    ))

    msg_repo = InMemoryMessageRepository()
    monkeypatch.setattr(runner, "get_event_repo", lambda: event_repo)

    class _NoReport:
        async def get_latest(self, *args, **kwargs):
            return None

    monkeypatch.setattr(runner, "get_report_repo", lambda: _NoReport())

    await runner._persist_run_transcript(
        msg_repo, thread_id, run_id=run_id, project_id="11111111-1111-1111-1111-111111111111",
    )

    msgs = await msg_repo.list_by_thread(thread_id)
    # 구조화 tool 1개(role=tool) + 최종 답변(role=assistant). result 없는 tool은 제외.
    assert [m.role for m in msgs] == ["tool", "assistant"]
    panel = json.loads(msgs[0].content)
    assert panel["tool_name"] == "get_connector_status"
    assert panel["params"]["connector_name"] == "orders-sink"
    assert panel["result"]["state"] == "RUNNING"
    # report snapshot이 없으면 RUN_COMPLETED 메시지로 폴백 저장.
    assert msgs[1].content == "조치 승인이 거절되어 실행을 중단했습니다"


@pytest.mark.asyncio
async def test_persist_run_transcript_prefers_report_answer(monkeypatch):
    run_id = "run_874b"
    thread_id = "chat-room-874b"
    event_repo = InMemoryEventRepository()
    event_repo.append(run_id, _event(
        run_id, StreamingEventType.RUN_COMPLETED, "터미널 메시지",
    ))

    msg_repo = InMemoryMessageRepository()
    monkeypatch.setattr(runner, "get_event_repo", lambda: event_repo)

    class _Snapshot:
        body = {"answer": "RCA 분석 결과입니다."}

    class _Report:
        async def get_latest(self, *args, **kwargs):
            return _Snapshot()

    monkeypatch.setattr(runner, "get_report_repo", lambda: _Report())

    await runner._persist_run_transcript(
        msg_repo, thread_id, run_id=run_id, project_id="11111111-1111-1111-1111-111111111111",
    )

    msgs = await msg_repo.list_by_thread(thread_id)
    # report answer가 있으면 그걸 우선 저장(터미널 메시지 아님).
    assert [m.role for m in msgs] == ["assistant"]
    assert msgs[0].content == "RCA 분석 결과입니다."
