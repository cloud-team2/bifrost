from __future__ import annotations

import pytest
from unittest.mock import AsyncMock

from app.agents.retrieval import run_retrieval
from app.persistence.evidence_repository import InMemoryEvidenceRepository
from app.persistence.event_repository import InMemoryEventRepository
from app.schemas.events import StreamingEventType
from app.schemas.outputs import PlannerOutput, RetrievalPlanStep
from app.schemas.tools import ToolContext, ToolError, ToolResult, ToolStatus, SpringErrorCode
from app.schemas.state import AgentMode, RiskLevel
from app.streaming.event_bus import EventBus


def _context() -> ToolContext:
    return ToolContext(
        run_id="r1",
        step_id="s1",
        agent_name="retrieval",
        project_id="p1",
        request_id="req1",
    )


def _plan(tool_name: str = "get_metrics") -> PlannerOutput:
    return PlannerOutput(
        retrieval_plan=[
            RetrievalPlanStep(
                step_id="s1",
                tool_name=tool_name,
                params={},
                purpose="test",
                depends_on=[],
                plan_hash="abc123",
            )
        ]
    )


@pytest.mark.asyncio
async def test_retrieval_success_emits_completed_not_failed() -> None:
    registry = AsyncMock()
    registry.call_tool.return_value = ToolResult(
        tool_name="get_metrics",
        status=ToolStatus.SUCCESS,
        risk=RiskLevel.READ_ONLY,
        summary="metric data",
        evidence_ids=[],
    )
    bus = EventBus()
    published = []
    bus.publish = AsyncMock(side_effect=lambda run_id, evt: published.append(evt))
    event_repo = InMemoryEventRepository()

    out = await run_retrieval("r1", _plan(), _context(), registry, bus, event_repo)

    types = [e.type for e in published]
    assert StreamingEventType.TOOL_CALL_COMPLETED in types
    assert StreamingEventType.EVIDENCE_COLLECTED in types
    assert StreamingEventType.TOOL_CALL_FAILED not in types
    assert "[mock]" not in out.evidence_items[0].summary
    assert "failed" not in out.evidence_items[0].store_ref

    ev_collected = next(e for e in published if e.type == StreamingEventType.EVIDENCE_COLLECTED)
    assert ev_collected.payload["evidence_id"] == out.evidence_items[0].evidence_id


@pytest.mark.asyncio
async def test_retrieval_stores_redacted_raw_evidence() -> None:
    registry = AsyncMock()
    registry.call_tool.return_value = ToolResult(
        tool_name="search_logs",
        status=ToolStatus.SUCCESS,
        risk=RiskLevel.READ_ONLY,
        summary="log evidence password=hunter2",
        evidence_ids=[],
        raw_payload={
            "result": {
                "logs": [
                    "AccessDenied token=abc123",
                    {"message": "jdbc:postgresql://db", "password": "hunter2"},
                ],
                "authorization": "Bearer secret-token",
            }
        },
    )
    bus = EventBus()
    bus.publish = AsyncMock()
    event_repo = InMemoryEventRepository()
    evidence_repo = InMemoryEvidenceRepository()

    out = await run_retrieval(
        "r1",
        _plan("search_logs"),
        _context(),
        registry,
        bus,
        event_repo,
        evidence_repo=evidence_repo,
    )

    item = out.evidence_items[0]
    record = await evidence_repo.get(item.store_ref)
    assert record is not None
    assert item.store_ref.startswith(f"evidence://r1/{item.evidence_id}")
    assert "hunter2" not in str(record.payload)
    assert "abc123" not in str(record.payload)
    assert record.payload["result"]["authorization"] == "[REDACTED]"
    assert "hunter2" not in item.summary
    assert "[REDACTED]" in item.summary


@pytest.mark.asyncio
async def test_retrieval_failure_emits_tool_call_failed() -> None:
    registry = AsyncMock()
    registry.call_tool.return_value = ToolResult(
        tool_name="get_metrics",
        status=ToolStatus.FAILED,
        risk=RiskLevel.READ_ONLY,
        summary="",
        error=ToolError(
            code=SpringErrorCode.TRANSIENT_ERROR,
            message="connection refused",
            retryable=True,
        ),
    )
    bus = EventBus()
    published = []
    bus.publish = AsyncMock(side_effect=lambda run_id, evt: published.append(evt))
    event_repo = InMemoryEventRepository()

    out = await run_retrieval("r1", _plan(), _context(), registry, bus, event_repo)

    types = [e.type for e in published]
    assert StreamingEventType.TOOL_CALL_FAILED in types
    assert StreamingEventType.TOOL_CALL_COMPLETED not in types
    assert "/failed" in out.evidence_items[0].store_ref
    assert "[mock]" not in out.evidence_items[0].summary
    assert "connection refused" in out.evidence_items[0].summary


@pytest.mark.asyncio
async def test_retrieval_failure_event_error_is_redacted() -> None:
    registry = AsyncMock()
    registry.call_tool.return_value = ToolResult(
        tool_name="get_metrics",
        status=ToolStatus.FAILED,
        risk=RiskLevel.READ_ONLY,
        summary="",
        error=ToolError(
            code=SpringErrorCode.TRANSIENT_ERROR,
            message="upstream rejected token=abc123",
            retryable=True,
        ),
    )
    bus = EventBus()
    published = []
    bus.publish = AsyncMock(side_effect=lambda run_id, evt: published.append(evt))

    await run_retrieval(
        "r1",
        _plan(),
        _context(),
        registry,
        bus,
        InMemoryEventRepository(),
        evidence_repo=InMemoryEvidenceRepository(),
    )

    failed = next(e for e in published if e.type == StreamingEventType.TOOL_CALL_FAILED)
    assert "abc123" not in str(failed.payload)
    assert "[REDACTED]" in failed.payload["error"]["message"]


@pytest.mark.asyncio
async def test_retrieval_timeout_emits_tool_call_failed() -> None:
    registry = AsyncMock()
    registry.call_tool.return_value = ToolResult(
        tool_name="get_metrics",
        status=ToolStatus.TIMEOUT,
        risk=RiskLevel.READ_ONLY,
        summary="",
        error=ToolError(
            code=SpringErrorCode.TIMEOUT,
            message="Spring operation timed out",
            retryable=True,
        ),
    )
    bus = EventBus()
    published = []
    bus.publish = AsyncMock(side_effect=lambda run_id, evt: published.append(evt))
    event_repo = InMemoryEventRepository()

    out = await run_retrieval("r1", _plan(), _context(), registry, bus, event_repo)

    types = [e.type for e in published]
    assert StreamingEventType.TOOL_CALL_FAILED in types
    assert StreamingEventType.TOOL_CALL_COMPLETED not in types
    assert "/failed" in out.evidence_items[0].store_ref


@pytest.mark.asyncio
async def test_evidence_collected_emitted_per_retrieval_step() -> None:
    registry = AsyncMock()
    registry.call_tool.return_value = ToolResult(
        tool_name="get_metrics",
        status=ToolStatus.SUCCESS,
        risk=RiskLevel.READ_ONLY,
        summary="data",
        evidence_ids=[],
    )
    bus = EventBus()
    published = []
    bus.publish = AsyncMock(side_effect=lambda run_id, evt: published.append(evt))
    event_repo = InMemoryEventRepository()

    plan = PlannerOutput(
        retrieval_plan=[
            RetrievalPlanStep(
                step_id=f"s{i}",
                tool_name="get_metrics",
                params={},
                purpose="test",
                depends_on=[],
                plan_hash="abc",
            )
            for i in range(3)
        ]
    )
    out = await run_retrieval("r1", plan, _context(), registry, bus, event_repo)

    ec_events = [e for e in published if e.type == StreamingEventType.EVIDENCE_COLLECTED]
    assert len(ec_events) == 3
    collected_ids = {e.payload["evidence_id"] for e in ec_events}
    item_ids = {item.evidence_id for item in out.evidence_items}
    assert collected_ids == item_ids


@pytest.mark.asyncio
async def test_simple_query_returns_rag_evidence_without_runtime_tool() -> None:
    from app.knowledge.vector_store import KnowledgeSearchResult

    class FakeVectorStore:
        async def search(self, query: str, **kwargs):
            assert query == "DLQ가 뭐야?"
            return [
                KnowledgeSearchResult(
                    chunk_id="chunk-1",
                    doc_id="glossary:dlq",
                    doc_type="glossary",
                    title="DLQ (Dead Letter Queue)",
                    content="DLQ는 처리 실패 메시지를 격리해 재처리할 수 있게 하는 큐다.",
                    scope="global",
                    doc_version="test",
                    metadata={},
                    score=0.9,
                )
            ]

    registry = AsyncMock()
    bus = EventBus()
    published = []
    bus.publish = AsyncMock(side_effect=lambda run_id, evt: published.append(evt))
    event_repo = InMemoryEventRepository()

    # 순수 지식 질의 "DLQ가 뭐야?" 는 planner 가 키워드 미매칭으로 fallback(search_logs)만
    # 계획한다 → 운영 tool 미계획이므로 RAG-only 단락 유지.
    out = await run_retrieval(
        "r1",
        _plan("search_logs"),
        _context(),
        registry,
        bus,
        event_repo,
        user_message="DLQ가 뭐야?",
        mode=AgentMode.SIMPLE_QUERY,
        vector_store=FakeVectorStore(),
    )

    assert len(out.evidence_items) == 1
    assert out.evidence_items[0].type.value == "knowledge"
    assert "DLQ" in out.evidence_items[0].summary
    assert out.evidence_items[0].store_ref.startswith("knowledge://global/glossary:dlq/")
    registry.call_tool.assert_not_called()
    assert any(e.type == StreamingEventType.EVIDENCE_COLLECTED for e in published)


@pytest.mark.asyncio
async def test_simple_query_with_operational_tool_calls_runtime_tool_despite_knowledge() -> None:
    """#478: 상태/목록 질의가 simple_query 로 분류돼도, planner 가 운영 tool 을 계획했으면
    knowledge hit 시에도 runtime tool 을 호출해 최신 운영 데이터를 근거에 포함한다."""
    from app.knowledge.vector_store import KnowledgeSearchResult

    class FakeVectorStore:
        async def search(self, query: str, **kwargs):
            return [
                KnowledgeSearchResult(
                    chunk_id="chunk-1",
                    doc_id="ops_doc:pipeline",
                    doc_type="ops_doc",
                    title="파이프라인 운영 가이드",
                    content="파이프라인 목록은 list_project_pipelines 로 조회한다.",
                    scope="global",
                    doc_version="test",
                    metadata={},
                    score=0.9,
                )
            ]

    registry = AsyncMock()
    registry.call_tool.return_value = ToolResult(
        tool_name="list_project_pipelines",
        status=ToolStatus.SUCCESS,
        risk=RiskLevel.READ_ONLY,
        summary="파이프라인 3건: orders, payments, audit",
        evidence_ids=[],
    )
    bus = EventBus()
    published = []
    bus.publish = AsyncMock(side_effect=lambda run_id, evt: published.append(evt))
    event_repo = InMemoryEventRepository()

    out = await run_retrieval(
        "r1",
        _plan("list_project_pipelines"),
        _context(),
        registry,
        bus,
        event_repo,
        user_message="파이프라인 리스트 알려줘",
        mode=AgentMode.SIMPLE_QUERY,
        vector_store=FakeVectorStore(),
    )

    # 운영 tool 이 실제 호출됐고, knowledge 근거 + tool 결과가 모두 evidence 에 포함된다.
    registry.call_tool.assert_called_once()
    assert registry.call_tool.call_args[0][0] == "list_project_pipelines"
    summaries = [item.summary for item in out.evidence_items]
    assert any("파이프라인 3건" in s for s in summaries)
    types = {item.type.value for item in out.evidence_items}
    assert "knowledge" in types
    assert "tool_result" in types
