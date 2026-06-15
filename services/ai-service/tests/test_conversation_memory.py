"""#712 대화 메모리: thread history 주입 + turn 저장 검증.

run_workflow 래퍼가 (1) 직전 대화를 현재 질문 앞에 주입하고, (2) 현재 질문과 run이 만든
최종 answer를 thread에 저장하는지, 무거운 _run_workflow_impl은 가짜로 대체해 단위 수준에서 본다.
"""
from __future__ import annotations

from unittest.mock import patch

import pytest

from app.persistence.message_repository import InMemoryMessageRepository
from app.persistence.report_repository import InMemoryReportRepository
from app.workflow import runner
from app.workflow.runner import _contextualize, _format_history, run_workflow


def test_format_history_filters_and_caps():
    class M:
        def __init__(self, role, content):
            self.role = role
            self.content = content

    block = _format_history([
        M("user", "원인이 뭐야?"),
        M("assistant", "싱크 DB 연결 불가"),
        M("system", "무시됨"),         # user/assistant 외 제외
        M("user", "   "),              # 공백 제외
    ])
    assert "사용자: 원인이 뭐야?" in block
    assert "어시스턴트: 싱크 DB 연결 불가" in block
    assert "무시됨" not in block


def test_contextualize_no_history_returns_original():
    assert _contextualize("현재 질문", "") == "현재 질문"


def test_contextualize_prepends_history_block():
    out = _contextualize("아까 그거 어떻게 조치해?", "사용자: 원인이 뭐야?\n어시스턴트: 싱크 DB")
    assert "[이전 대화 맥락" in out
    assert "[현재 질문]\n아까 그거 어떻게 조치해?" in out
    assert out.index("이전 대화 맥락") < out.index("현재 질문")


@pytest.mark.asyncio
async def test_run_workflow_injects_history_and_persists_turns():
    message_repo = InMemoryMessageRepository()
    report_repo = InMemoryReportRepository()
    thread_id = "incident-xyz"

    # 1턴: 직전 대화가 thread에 이미 있다고 가정
    await message_repo.append(thread_id, "user", "원인이 뭐야?", run_id="run-1")
    await message_repo.append(thread_id, "assistant", "싱크 DB 연결 불가입니다", run_id="run-1")

    captured = {}

    async def fake_impl(*, run_id, user_message, **kwargs):
        # impl이 받은 user_message에 직전 대화가 주입돼 있어야 한다
        captured["user_message"] = user_message
        # impl이 최종 answer를 report로 남긴다(실제 워크플로 동작 모사)
        await report_repo.create(run_id, {"answer": "DB를 복구하고 커넥터를 재시작하세요"}, verified=False)

    with patch.object(runner, "_run_workflow_impl", fake_impl), \
            patch.object(runner, "get_report_repo", lambda: report_repo):
        await run_workflow(
            run_id="run-2",
            user_message="아까 그거 어떻게 조치해?",
            project_id="00000000-0000-0000-0000-000000000001",
            bus=None,
            run_repo=None,
            registry=None,
            requested_incident_id=thread_id,
            thread_id=thread_id,
            message_repo=message_repo,
        )

    # (1) 주입: impl이 받은 메시지에 직전 대화 + 현재 질문이 함께 있어야 한다
    assert "원인이 뭐야?" in captured["user_message"]
    assert "싱크 DB 연결 불가" in captured["user_message"]
    assert "아까 그거 어떻게 조치해?" in captured["user_message"]

    # (2) 저장: 현재 질문(user, 원문) + 최종 answer(assistant)가 thread에 추가됐다
    msgs = await message_repo.list_by_thread(thread_id)
    assert [m.role for m in msgs] == ["user", "assistant", "user", "assistant"]
    assert msgs[2].content == "아까 그거 어떻게 조치해?"   # 원문 저장(주입본 아님)
    assert msgs[3].content == "DB를 복구하고 커넥터를 재시작하세요"


@pytest.mark.asyncio
async def test_run_workflow_without_thread_is_noop_for_memory():
    message_repo = InMemoryMessageRepository()
    captured = {}

    async def fake_impl(*, run_id, user_message, **kwargs):
        captured["user_message"] = user_message

    with patch.object(runner, "_run_workflow_impl", fake_impl):
        await run_workflow(
            run_id="run-x",
            user_message="thread 없는 질문",
            project_id="00000000-0000-0000-0000-000000000001",
            bus=None,
            run_repo=None,
            registry=None,
            thread_id=None,
            message_repo=message_repo,
        )

    assert captured["user_message"] == "thread 없는 질문"  # 주입 없음
    assert await message_repo.list_by_thread("anything") == []  # 저장 없음
