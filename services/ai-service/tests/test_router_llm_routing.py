"""#483 — Router LLM 기반 분류 회귀 테스트.

키워드를 포함하지 않는 자연어 의도가 LLM 으로 올바른 mode 로 분류되는지,
LLM 미가용·무효 응답 시 keyword fallback 으로 회귀 없이 복구되는지 검증한다.
"""
from __future__ import annotations

import json

import pytest

from app.agents.router import run_router
from app.schemas.outputs import RouterOutput
from app.schemas.state import AgentMode


class _DummyLLMProvider:
    def __init__(self, response: str = "") -> None:
        self.response = response
        self.calls: list[list[dict]] = []

    async def generate(self, messages: list[dict], model: str | None = None) -> str:
        self.calls.append(messages)
        return self.response


def _patch_llm(monkeypatch: pytest.MonkeyPatch, response: str) -> _DummyLLMProvider:
    provider = _DummyLLMProvider(response)
    monkeypatch.setattr("app.llm.provider.get_llm_provider", lambda: provider)
    return provider


def _mode_response(mode: str, remediation: bool = False) -> str:
    return json.dumps(
        {"mode": mode, "remediation_requested": remediation, "reason": "test"}
    )


# ── 키워드 비포함 자연어 → LLM 으로 올바른 mode 분류 ─────────────────────────
@pytest.mark.parametrize(
    "message, mode",
    [
        # 키워드 set 에 없는 증상 서술 → incident_analysis
        ("주문 처리가 자꾸 멈춰서 원인 좀 봐줘", AgentMode.INCIDENT_ANALYSIS),
        # 키워드 없는 단순 조회 → simple_query
        ("지금 잘 돌아가고 있는지 한번 봐줘", AgentMode.SIMPLE_QUERY),
        # 키워드 없는 실행 지시 → action_execution
        ("아까 그거 그대로 진행해줘", AgentMode.ACTION_EXECUTION),
        # 키워드 없는 승인 → approval_decision
        ("그렇게 해도 좋아요", AgentMode.APPROVAL_DECISION),
    ],
)
@pytest.mark.asyncio
async def test_llm_classifies_keywordless_intents(
    monkeypatch: pytest.MonkeyPatch, message: str, mode: AgentMode
) -> None:
    _patch_llm(monkeypatch, _mode_response(mode.value))

    out = await run_router(message)

    assert out.route_decision.mode == mode


@pytest.mark.asyncio
async def test_llm_remediation_requested_flows_through(monkeypatch: pytest.MonkeyPatch) -> None:
    _patch_llm(monkeypatch, _mode_response("incident_analysis", remediation=True))

    out = await run_router("왜 느린지 보고 어떻게 풀지도 같이 알려줘")

    assert out.route_decision.mode == AgentMode.INCIDENT_ANALYSIS
    assert out.route_decision.remediation_requested is True
    # remediation 단계가 stage flow 에 반영된다.
    assert "remediation" in out.route_decision.required_flow


@pytest.mark.asyncio
async def test_remediation_ignored_for_non_incident_mode(monkeypatch: pytest.MonkeyPatch) -> None:
    # incident_analysis 가 아니면 remediation_requested 는 무시한다.
    _patch_llm(monkeypatch, _mode_response("simple_query", remediation=True))

    out = await run_router("현황만 보여줘")

    assert out.route_decision.mode == AgentMode.SIMPLE_QUERY
    assert out.route_decision.remediation_requested is False


# ── fallback: LLM 미가용/무효 응답 → keyword 회귀 없음 ───────────────────────
@pytest.mark.asyncio
async def test_empty_llm_falls_back_to_keyword(monkeypatch: pytest.MonkeyPatch) -> None:
    _patch_llm(monkeypatch, "")

    out = await run_router("connector 재시작해줘")

    # keyword fallback: 실행 동사 → action_execution
    assert out.route_decision.mode == AgentMode.ACTION_EXECUTION


@pytest.mark.asyncio
async def test_invalid_mode_repairs_then_falls_back(monkeypatch: pytest.MonkeyPatch) -> None:
    # mode 가 allowlist 밖이면 1회 repair 시도 후에도 무효 → keyword fallback.
    provider = _patch_llm(monkeypatch, _mode_response("MADE_UP_MODE"))

    out = await run_router("장애 원인 분석해줘")

    assert out.route_decision.mode == AgentMode.INCIDENT_ANALYSIS  # keyword fallback
    assert len(provider.calls) == 2  # 1차 + repair 1회


@pytest.mark.asyncio
async def test_repair_recovers_valid_mode(monkeypatch: pytest.MonkeyPatch) -> None:
    class _RepairingProvider:
        def __init__(self) -> None:
            self.calls = 0

        async def generate(self, messages: list[dict], model: str | None = None) -> str:
            self.calls += 1
            if self.calls == 1:
                return "그건 잘 모르겠어요"  # 무효 — 파싱 실패
            return _mode_response("incident_analysis")

    provider = _RepairingProvider()
    monkeypatch.setattr("app.llm.provider.get_llm_provider", lambda: provider)

    out = await run_router("이거 좀 살펴봐")

    assert out.route_decision.mode == AgentMode.INCIDENT_ANALYSIS
    assert provider.calls == 2


@pytest.mark.asyncio
async def test_output_schema_strict(monkeypatch: pytest.MonkeyPatch) -> None:
    _patch_llm(monkeypatch, _mode_response("simple_query"))

    out = await run_router("상태 알려줘")

    RouterOutput.model_validate(out.model_dump())
