"""#988 SIMPLE_QUERY 단일 의도(로그/메트릭 수치) 라우팅 가드레일.

LLM 이 식별자 의존 tool 을 비결정적으로 골라 clarification→knowledge-only 로 새던 변동을,
project-scope 정답 tool 을 plan 선두에 강제해 결정적으로 막는다. incident mode 는 불변.
"""
from __future__ import annotations

import pytest

import app.agents.planner as planner
from app.agents.planner import _force_clear_intent_primary, run_planner
from app.schemas.state import AgentMode


def _tools(selected):
    return [t for t, _ in selected]


# ── 가드레일 순수 함수 ────────────────────────────────────────────────────────
def test_metric_value_intent_forces_get_metrics_first_and_drops_identifier_tool():
    # LLM 이 get_consumer_groups(오선택) + get_consumer_lag(식별자 의존) 를 골랐다고 가정.
    out = _force_clear_intent_primary(
        [("get_consumer_groups", {}), ("get_consumer_lag", {})],
        "consumer lag 메트릭 수치 보여줘",
    )
    assert out[0][0] == "get_metrics"  # 정답 tool 선두
    assert "get_consumer_lag" not in _tools(out)  # 식별자 의존(허위 clarification 유발) 제거
    assert "get_consumer_groups" in _tools(out)  # project-scope 는 유지


@pytest.mark.parametrize(
    "msg",
    ["파이프라인 에러 로그 좀 검색해줘", "search the recent error logs for the pipeline"],
)
def test_log_intent_forces_search_logs_first(msg):
    out = _force_clear_intent_primary([("get_connector_task_trace", {})], msg)
    assert out[0][0] == "search_logs"
    assert "get_connector_task_trace" not in _tools(out)


def test_explicit_identifier_respects_llm_selection():
    # 식별자가 명시되면 식별자 의존 조회가 정당 → 가드레일 미적용.
    sel = [("get_connector_status", {})]
    assert _force_clear_intent_primary(sel, "products-sink 커넥터 로그 보여줘") == sel


def test_no_data_intent_is_unchanged():
    sel = [("list_connectors", {})]
    assert _force_clear_intent_primary(sel, "커넥터 목록 보여줘") == sel


def test_eventish_log_query_not_forced_to_search_logs():
    # "인시던트/이벤트 로그" 류는 analyze_event_log 의도 → search_logs 강제하지 않는다.
    sel = [("analyze_event_log", {})]
    assert _force_clear_intent_primary(sel, "인시던트 이벤트 로그 요약") == sel


# ── run_planner 통합 (mode 스코프) ───────────────────────────────────────────
@pytest.mark.asyncio
async def test_run_planner_simple_mode_recovers_log_routing(monkeypatch):
    # LLM 이 식별자 의존 tool 을 골라(변동) clarification 으로 샐 상황을 재현.
    async def fake_llm(_msg):
        return [("get_connector_task_trace", {})]

    monkeypatch.setattr(planner, "_llm_select_tools", fake_llm)
    out = await run_planner(
        "파이프라인 에러 로그 좀 검색해줘", "proj-1", mode=AgentMode.SIMPLE_QUERY
    )
    tools = [s.tool_name for s in out.retrieval_plan]
    assert tools and tools[0] == "search_logs"  # 가드레일이 정답 tool 을 선두에 보장
    assert out.clarification_message is None  # 허위 clarification 차단


@pytest.mark.asyncio
async def test_run_planner_without_simple_mode_is_unaffected(monkeypatch):
    # mode 미지정(예: incident/기본)에선 가드레일이 적용되지 않는다.
    async def fake_llm(_msg):
        return [("get_connector_task_trace", {})]

    monkeypatch.setattr(planner, "_llm_select_tools", fake_llm)
    out = await run_planner("파이프라인 에러 로그 좀 검색해줘", "proj-1")
    tools = [s.tool_name for s in out.retrieval_plan]
    # 식별자 의존 tool 을 LLM 이 골랐고 식별자 없음 → 가드레일 미적용이므로 search_logs 강제 안 됨.
    assert tools[:1] != ["search_logs"] or out.clarification_message is not None
