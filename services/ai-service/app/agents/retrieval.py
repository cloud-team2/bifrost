"""Retrieval agent — fan-out tool calls plus RAG knowledge lookup."""
from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timezone
from typing import Any
from uuid import uuid4

from app.core.config import settings
from app.evidence.redaction import redact_payload, redact_text
from app.knowledge.vector_store import get_vector_store
from app.persistence.evidence_repository import AnyEvidenceRepo, get_evidence_repo
from app.persistence.event_repository import AnyEventRepo, InMemoryEventRepository, append_event
from app.schemas.events import StreamingEvent, StreamingEventType
from app.schemas.outputs import PlannerOutput, RetrievalOutput
from app.schemas.state import AgentMode, EvidenceItem, EvidenceType, RedactionStatus
from app.schemas.tools import ToolContext, ToolStatus
from app.streaming.event_bus import EventBus
from app.tools.registry import ToolClientRegistry

logger = logging.getLogger(__name__)

# planner 가 명시적 키워드 매칭 없이 fallback 으로 선택하는 도구. 이 도구만으로 이뤄진
# plan 은 순수 지식/용어 질의("DLQ가 뭐야?")로 간주해 RAG 근거가 있으면 단락한다.
# 그 외 도구(list_project_pipelines·get_connector_status·get_consumer_lag 등)는 명시적
# 운영 조회 의도를 뜻하므로, knowledge 근거가 있어도 실제 운영 데이터를 조회한다 (#478).
_FALLBACK_ONLY_TOOLS = frozenset({"search_logs"})


def _has_operational_tool(plan: PlannerOutput) -> bool:
    """planner 가 fallback 이 아닌 명시적 운영 runtime tool 을 계획했는지 여부."""
    return any(step.tool_name not in _FALLBACK_ONLY_TOOLS for step in plan.retrieval_plan)


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
    evidence_repo: AnyEvidenceRepo | None = None,
) -> RetrievalOutput:
    if event_repo is None:
        event_repo = InMemoryEventRepository()
    if evidence_repo is None:
        evidence_repo = get_evidence_repo()

    knowledge_items = await _collect_knowledge_evidence(
        run_id=run_id,
        user_message=user_message,
        mode=mode,
        context=context,
        bus=bus,
        event_repo=event_repo,
        vector_store=vector_store,
    )

    # 순수 지식 질의(planner 가 fallback tool 만 계획)는 RAG 근거가 있으면 운영 runtime tool
    # 호출 없이 답변한다. 단, 상태/목록 등 명시적 운영 tool 이 계획됐다면 knowledge 근거가
    # 있어도 단락하지 않고 실제 운영 데이터를 조회한다 (#478).
    if mode == AgentMode.SIMPLE_QUERY and knowledge_items and not _has_operational_tool(plan):
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

        evidence_id = str(uuid4())
        if result.status == ToolStatus.SUCCESS:
            summary = redact_text(result.summary)
            store_ref = await evidence_repo.put(
                run_id=run_id,
                evidence_id=evidence_id,
                tool_name=step.tool_name,
                step_id=step.step_id,
                status=result.status.value,
                payload=redact_payload(_raw_payload_for_store(result)),
            )
            evidence = EvidenceItem(
                evidence_id=evidence_id,
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
            error_msg = redact_text(result.error.message if result.error else "조회 실패")
            summary = f"{step.tool_name}: {error_msg}"
            store_ref = await evidence_repo.put(
                run_id=run_id,
                evidence_id=evidence_id,
                tool_name=step.tool_name,
                step_id=step.step_id,
                status=result.status.value,
                payload=redact_payload(_raw_payload_for_store(result)),
                failed=True,
            )
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
                    "error": redact_payload(result.error.model_dump(mode="json")) if result.error else {},
                },
            ))
            evidence = EvidenceItem(
                evidence_id=evidence_id,
                type=EvidenceType.TOOL_RESULT,
                store_ref=store_ref,
                summary=summary,
                redaction_status=RedactionStatus.REDACTED,
                collected_by="retrieval",
                collected_at=datetime.now(timezone.utc),
            )

        return evidence

    # depends_on을 해석해 독립 tool은 병렬(fan-out), 의존 tool은 선행 완료 후 순차 실행
    tool_evidence = await _run_plan_steps(plan.retrieval_plan, call_step)
    return RetrievalOutput(evidence_items=[*knowledge_items, *tool_evidence])


async def _run_plan_steps(steps: list, call_step) -> list[EvidenceItem]:
    """depends_on을 해석한 wave 실행기 (#481).

    depends_on이 없는(또는 선행이 모두 끝난) step들은 같은 wave에서 asyncio.gather로
    병렬 실행하고, 의존이 남은 step은 선행 step이 끝난 다음 wave로 미룬다. 결과는
    plan 순서대로 정렬해 반환한다. 미해결/순환 의존은 deadlock 대신 남은 step을
    한꺼번에 실행해 안전하게 흡수한다.
    """
    if not steps:
        return []

    valid_ids = {s.step_id for s in steps}
    done: dict[str, EvidenceItem] = {}
    remaining = list(steps)
    while remaining:
        ready = [
            s
            for s in remaining
            if all((dep not in valid_ids) or (dep in done) for dep in s.depends_on)
        ]
        if not ready:  # 순환/미해결 의존 — deadlock 방지
            ready = remaining
        wave = await asyncio.gather(*[call_step(s) for s in ready])
        for step, evidence in zip(ready, wave):
            done[step.step_id] = evidence
        ready_ids = {s.step_id for s in ready}
        remaining = [s for s in remaining if s.step_id not in ready_ids]

    return [done[s.step_id] for s in steps]


def _raw_payload_for_store(result: Any) -> dict[str, Any] | list[Any]:
    raw_payload = getattr(result, "raw_payload", None)
    if raw_payload is not None:
        return raw_payload
    return result.model_dump(mode="json", exclude_none=True, exclude={"raw_payload"})


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
