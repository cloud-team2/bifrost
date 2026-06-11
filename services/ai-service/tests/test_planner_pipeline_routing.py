"""#468 — Planner 파이프라인 질의 라우팅 회귀 테스트.

"파이프라인 리스트" 류 질의가 search_logs 가 아니라 list_project_pipelines 로
라우팅되는지, 로그/에러 의도는 여전히 search_logs 로 유지되는지 검증한다.
"""
from __future__ import annotations

import pytest

from app.agents.planner import run_planner


def _tool_names(plan) -> list[str]:
    return [step.tool_name for step in plan.retrieval_plan]


@pytest.mark.asyncio
async def test_pipeline_list_routes_to_list_project_pipelines():
    plan = await run_planner("파이프라인 리스트 알려줘", "proj_001")

    names = _tool_names(plan)
    assert "list_project_pipelines" in names
    assert "search_logs" not in names


@pytest.mark.asyncio
async def test_list_project_pipelines_uses_empty_params():
    plan = await run_planner("파이프라인 목록 보여줘", "proj_001")

    step = next(
        s for s in plan.retrieval_plan if s.tool_name == "list_project_pipelines"
    )
    assert step.params == {}


@pytest.mark.asyncio
async def test_error_log_keeps_search_logs():
    plan = await run_planner("에러 로그 보여줘", "proj_001")

    names = _tool_names(plan)
    assert "search_logs" in names
    assert "list_project_pipelines" not in names


@pytest.mark.asyncio
async def test_plain_log_query_routes_to_search_logs():
    plan = await run_planner("로그 보여줘", "proj_001")

    names = _tool_names(plan)
    assert "search_logs" in names
    assert "list_project_pipelines" not in names


@pytest.mark.asyncio
async def test_no_duplicate_tool_steps():
    # "에러 로그" 는 incident·log 버킷 둘 다에 매칭되지만 search_logs step 은 1개여야 한다.
    plan = await run_planner("에러 로그 보여줘", "proj_001")

    names = _tool_names(plan)
    assert names.count("search_logs") == 1


# ── #481 depends_on 순차 chain ────────────────────────────────────────────────
@pytest.mark.asyncio
async def test_planner_fills_depends_on_for_identifier_dependent_tool():
    # discovery(list_project_pipelines) + 식별자 의존(get_connector_status)이 함께
    # 계획되면 의존 tool은 discovery step에 depends_on을 건다.
    plan = await run_planner("파이프라인 목록과 `my-connector` 커넥터 상태 알려줘", "proj_001")

    steps = {s.tool_name: s for s in plan.retrieval_plan}
    assert "list_project_pipelines" in steps
    assert "get_connector_status" in steps
    assert steps["list_project_pipelines"].depends_on == []
    assert steps["get_connector_status"].depends_on == [steps["list_project_pipelines"].step_id]


@pytest.mark.asyncio
async def test_planner_independent_tools_have_empty_depends_on():
    # discovery tool이 없는 독립 조회는 depends_on이 비어 병렬 실행을 유지한다.
    plan = await run_planner("메트릭이랑 배포 알려줘", "proj_001")

    assert len(plan.retrieval_plan) >= 2
    for step in plan.retrieval_plan:
        assert step.depends_on == []
