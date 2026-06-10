"""Retrieval agent — fan-out tool calls plus RAG knowledge lookup."""
from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timezone
from typing import Any
from uuid import uuid4

from app.core.config import settings
from app.knowledge.vector_store import get_vector_store
from app.persistence.event_repository import AnyEventRepo, InMemoryEventRepository, append_event
from app.schemas.events import StreamingEvent, StreamingEventType
from app.schemas.outputs import PlannerOutput, RetrievalOutput
from app.schemas.state import AgentMode, EvidenceItem, EvidenceType, RedactionStatus
from app.schemas.tools import ToolContext, ToolStatus
from app.streaming.event_bus import EventBus
from app.tools.registry import ToolClientRegistry

logger = logging.getLogger(__name__)


async def _pub(bus: EventBus, repo: AnyEventRepo, run_id: str, event: StreamingEvent) -> None:
    await append_event(repo, run_id, event)
    await bus.publish(run_id, event)


async def run_retrieval(
    run_id: str,
    plan: PlannerOutput,
    context: ToolContext,
    registry: ToolClientRegistry,
    bus: EventBus,
    event_repo: AnyEventRepo | None = None,
    *,
    user_message: str | None = None,
    mode: AgentMode | None = None,
    vector_store: Any | None = None,
) -> RetrievalOutput:
    if event_repo is None:
        event_repo = InMemoryEventRepository()

    knowledge_items = await _collect_knowledge_evidence(
        run_id=run_id,
        user_message=user_message,
        mode=mode,
        context=context,
        bus=bus,
        event_repo=event_repo,
        vector_store=vector_store,
    )

    # simple_query 지식 질의는 RAG 근거가 있으면 운영 runtime tool 호출 없이 답변한다.
    if mode == AgentMode.SIMPLE_QUERY and knowledge_items:
        return RetrievalOutput(evidence_items=knowledge_items)

    async def call_step(step: "RetrievalPlanStep") -> EvidenceItem:  # type: ignore[name-defined]
        now = datetime.now(timezone.utc)
        await _pub(bus, event_repo, run_id, StreamingEvent(
            event_id=str(uuid4()),
            run_id=run_id,
            timestamp=now,
            type=StreamingEventType.TOOL_CALL_STARTED,
            agent="retrieval",
            message=f"{step.tool_name} 조회 중...",
            payload={"tool": step.tool_name, "step_id": step.step_id},
        ))

        result = await registry.call_tool(step.tool_name, step.params, context)

        if result.status == ToolStatus.SUCCESS:
            summary = result.summary
            store_ref = f"evidence://{run_id}/{step.step_id}"
            evidence = EvidenceItem(
                evidence_id=str(uuid4()),
                type=EvidenceType.TOOL_RESULT,
                store_ref=store_ref,
                summary=summary,
                redaction_status=RedactionStatus.REDACTED,
                collected_by="retrieval",
                collected_at=datetime.now(timezone.utc),
            )
            await _pub(bus, event_repo, run_id, StreamingEvent(
                event_id=str(uuid4()),
                run_id=run_id,
                timestamp=datetime.now(timezone.utc),
                type=StreamingEventType.TOOL_CALL_COMPLETED,
                agent="retrieval",
                message=summary,
                payload={"tool": step.tool_name, "step_id": step.step_id, "summary": summary},
            ))
            await _pub(bus, event_repo, run_id, StreamingEvent(
                event_id=str(uuid4()),
                run_id=run_id,
                timestamp=datetime.now(timezone.utc),
                type=StreamingEventType.EVIDENCE_COLLECTED,
                agent="retrieval",
                message=f"근거 수집: {summary[:80]}",
                payload={
                    "evidence_id": evidence.evidence_id,
                    "evidence_type": evidence.type.value,
                    "summary": summary[:80],
                    "redaction_status": evidence.redaction_status.value,
                },
            ))
        else:
            error_msg = result.error.message if result.error else "조회 실패"
            summary = f"{step.tool_name}: {error_msg}"
            store_ref = f"evidence://{run_id}/{step.step_id}/failed"
            await _pub(bus, event_repo, run_id, StreamingEvent(
                event_id=str(uuid4()),
                run_id=run_id,
                timestamp=datetime.now(timezone.utc),
                type=StreamingEventType.TOOL_CALL_FAILED,
                agent="retrieval",
                message=f"{step.tool_name} 호출 실패: {error_msg}",
                payload={
                    "tool": step.tool_name,
                    "step_id": step.step_id,
                    "error": result.error.model_dump() if result.error else {},
                },
            ))
            evidence = EvidenceItem(
                evidence_id=str(uuid4()),
                type=EvidenceType.TOOL_RESULT,
                store_ref=store_ref,
                summary=summary,
                redaction_status=RedactionStatus.REDACTED,
                collected_by="retrieval",
                collected_at=datetime.now(timezone.utc),
            )

        return evidence

    # 독립 read-only tool은 병렬(fan-out) 실행
    tool_evidence = list(await asyncio.gather(*[call_step(s) for s in plan.retrieval_plan]))
    return RetrievalOutput(evidence_items=[*knowledge_items, *tool_evidence])


async def _collect_knowledge_evidence(
    *,
    run_id: str,
    user_message: str | None,
    mode: AgentMode | None,
    context: ToolContext,
    bus: EventBus,
    event_repo: AnyEventRepo,
    vector_store: Any | None,
) -> list[EvidenceItem]:
    if not user_message or mode not in {AgentMode.SIMPLE_QUERY, AgentMode.INCIDENT_ANALYSIS}:
        return []

    step_id = "knowledge_search"
    await _pub(bus, event_repo, run_id, StreamingEvent(
        event_id=str(uuid4()),
        run_id=run_id,
        timestamp=datetime.now(timezone.utc),
        type=StreamingEventType.TOOL_CALL_STARTED,
        agent="retrieval",
        message="지식 베이스 검색 중...",
        payload={"tool": step_id, "step_id": step_id},
    ))

    try:
        store = vector_store or get_vector_store()
        results = await store.search(
            user_message,
            project_id=context.project_id,
            doc_types=("glossary", "runbook", "ops_doc", "catalog", "incident_report"),
            limit=settings.knowledge_search_limit,
            min_score=settings.knowledge_min_score,
        )
    except Exception as exc:  # DB/pgvector 미가용 시 runtime tool 경로는 계속 진행
        logger.warning("knowledge search unavailable: %s", exc)
        await _pub(bus, event_repo, run_id, StreamingEvent(
            event_id=str(uuid4()),
            run_id=run_id,
            timestamp=datetime.now(timezone.utc),
            type=StreamingEventType.TOOL_CALL_FAILED,
            agent="retrieval",
            message=f"지식 베이스 검색 실패: {exc}",
            payload={"tool": step_id, "step_id": step_id, "error": str(exc)},
        ))
        return []

    evidence_items: list[EvidenceItem] = []
    for result in results:
        summary = result.summary() if callable(getattr(result, "summary", None)) else str(result)
        evidence = EvidenceItem(
            evidence_id=str(uuid4()),
            type=EvidenceType.KNOWLEDGE,
            store_ref=result.store_ref,
            summary=summary,
            redaction_status=RedactionStatus.REDACTED,
            collected_by="retrieval",
            collected_at=datetime.now(timezone.utc),
        )
        evidence_items.append(evidence)
        await _pub(bus, event_repo, run_id, StreamingEvent(
            event_id=str(uuid4()),
            run_id=run_id,
            timestamp=datetime.now(timezone.utc),
            type=StreamingEventType.EVIDENCE_COLLECTED,
            agent="retrieval",
            message=f"지식 근거 수집: {summary[:80]}",
            payload={
                "evidence_id": evidence.evidence_id,
                "evidence_type": evidence.type.value,
                "summary": summary[:80],
                "redaction_status": evidence.redaction_status.value,
                "store_ref": evidence.store_ref,
            },
        ))

    await _pub(bus, event_repo, run_id, StreamingEvent(
        event_id=str(uuid4()),
        run_id=run_id,
        timestamp=datetime.now(timezone.utc),
        type=StreamingEventType.TOOL_CALL_COMPLETED,
        agent="retrieval",
        message=f"지식 근거 {len(evidence_items)}건 검색",
        payload={"tool": step_id, "step_id": step_id, "count": len(evidence_items)},
    ))
    return evidence_items
