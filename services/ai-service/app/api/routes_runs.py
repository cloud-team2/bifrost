"""Run state/timeline facade endpoints."""
from __future__ import annotations

import inspect
import uuid
from collections import OrderedDict
from datetime import datetime
from typing import Any

from fastapi import APIRouter, BackgroundTasks, Query

from app.persistence.event_repository import get_event_repo
from app.persistence.run_repository import get_run_repo
from app.persistence.state_repository import PostgresStateRepository, StatePatchRecord
from app.schemas import ApiResponse, ErrorCode
from app.schemas.api import (
    ActionSummary,
    MessageRequest,
    RetryRequest,
    StateNamespaceSummary,
    StepSummary,
    TimelineItem,
)
from app.streaming.event_bus import get_event_bus
from app.tools.registry import get_tool_registry
from app.workflow.runner import run_workflow

router = APIRouter()


def _request_id() -> str:
    return f"req_{uuid.uuid4().hex[:12]}"


def get_state_repo() -> PostgresStateRepository:
    return PostgresStateRepository()


async def _resolve(value: Any) -> Any:
    if inspect.isawaitable(value):
        return await value
    return value


async def _get_patches(run_id: str, after_seq: int = 0) -> list[StatePatchRecord]:
    try:
        return await _resolve(get_state_repo().get_patches(run_id, from_seq=after_seq))
    except RuntimeError:
        return []


async def _get_events(run_id: str) -> list[Any]:
    try:
        return await _resolve(get_event_repo().get_after(run_id, last_event_id=None))
    except RuntimeError:
        return []


def _created_at(value: Any) -> datetime | None:
    return getattr(value, "created_at", None) or getattr(value, "timestamp", None)


def _timeline_sort_value(value: Any) -> tuple[datetime, int]:
    created_at = value.get("created_at") if isinstance(value, dict) else _created_at(value)
    if created_at is None:
        return datetime.min, 0
    return created_at, getattr(value, "seq", 0) or 0


def _merge_timeline(patches: list[StatePatchRecord], events: list[Any]) -> list[dict[str, Any]]:
    items: list[TimelineItem] = []
    for patch in patches:
        created_at = patch.created_at
        if created_at is None:
            continue
        items.append(TimelineItem(
            seq=patch.seq,
            type="state_patch",
            agent=_value_as_str(patch.author),
            message=(
                f"{_value_as_str(patch.namespace, 'unknown')}:"
                f"{_value_as_str(patch.op, 'unknown')} "
                f"{_value_as_str(patch.path, '/')}"
            ),
            created_at=created_at,
        ))

    for event in events:
        created_at = _created_at(event)
        if created_at is None:
            continue
        event_type = getattr(getattr(event, "type", None), "value", None) or getattr(event, "type", "event")
        items.append(TimelineItem(
            seq=None,
            type=_value_as_str(event_type, "event") or "event",
            agent=_value_as_str(getattr(event, "agent", None)),
            message=_value_as_str(getattr(event, "message", None), "") or "",
            created_at=created_at,
        ))

    return [item.model_dump(mode="json") for item in sorted(items, key=_timeline_sort_value)]


def _patch_payloads(value: Any) -> list[dict[str, Any]]:
    if isinstance(value, list):
        return [item for item in value if isinstance(item, dict)]
    if not isinstance(value, dict):
        return []
    if isinstance(value.get("actions"), list):
        return [item for item in value["actions"] if isinstance(item, dict)]
    if isinstance(value.get("value"), list):
        return [item for item in value["value"] if isinstance(item, dict)]
    if isinstance(value.get("value"), dict):
        return [value["value"]]
    return [value]


def _value_as_str(value: Any, default: str | None = None) -> str | None:
    if value is None:
        return default
    value = getattr(value, "value", value)
    return value if isinstance(value, str) else str(value)


def _action_id_from_patch(path: str, payload: dict[str, Any]) -> str | None:
    if payload.get("action_id"):
        return str(payload["action_id"])
    parts = [part for part in path.split("/") if part]
    if len(parts) >= 2 and parts[0] == "actions":
        return parts[1]
    return None


def _merge_actions(patches: list[StatePatchRecord]) -> list[dict[str, Any]]:
    actions: OrderedDict[str, dict[str, Any]] = OrderedDict()
    for patch in patches:
        if _value_as_str(patch.namespace) != "actions":
            continue
        for payload in _patch_payloads(patch.patch):
            action_id = _action_id_from_patch(patch.path, payload)
            if action_id is None:
                continue
            current = actions.setdefault(action_id, {"action_id": action_id})
            current.update({key: value for key, value in payload.items() if value is not None})

    summaries: list[dict[str, Any]] = []
    for action in actions.values():
        policy_decision = action.get("policy_decision", action.get("decision"))
        execution_status = action.get("execution_status", action.get("status"))
        summaries.append(ActionSummary(
            action_id=str(action["action_id"]),
            action_type=_value_as_str(action.get("action_type"), "") or "",
            tool_name=_value_as_str(action.get("tool_name")),
            risk=_value_as_str(action.get("risk"), "unknown") or "unknown",
            policy_decision=_value_as_str(policy_decision),
            approval_id=_value_as_str(action.get("approval_id")),
            approval_status=_value_as_str(action.get("approval_status")),
            execution_status=_value_as_str(execution_status),
            audit_event_id=_value_as_str(action.get("audit_event_id")),
        ).model_dump(mode="json"))
    return summaries


async def _require_run(run_id: str) -> Any:
    return await get_run_repo().get(run_id)


@router.get("/runs/{run_id}/state/summary")
async def get_state_summary(run_id: str) -> ApiResponse:
    request_id = _request_id()
    run = await _require_run(run_id)
    if run is None:
        return ApiResponse.failure(request_id, ErrorCode.RUN_NOT_FOUND, f"run not found: {run_id}")

    patches = await _get_patches(run_id)
    namespaces: dict[str, dict[str, Any]] = {}
    for patch in patches:
        namespace = _value_as_str(patch.namespace, "unknown") or "unknown"
        summary = namespaces.setdefault(namespace, {
            "patch_count": 0,
            "last_author": None,
            "last_op": None,
            "last_updated_at": None,
        })
        summary["patch_count"] += 1
        summary["last_author"] = _value_as_str(patch.author)
        summary["last_op"] = _value_as_str(patch.op)
        summary["last_updated_at"] = patch.created_at

    guards = {"step_count": len(patches), "gap_loops": 0}
    for patch in patches:
        if _value_as_str(patch.namespace) == "guards" and isinstance(patch.patch, dict):
            guards.update({
                key: value for key, value in patch.patch.items()
                if key in {"step_count", "gap_loops"} and isinstance(value, int)
            })

    return ApiResponse.success(request_id, {
        "run_id": run_id,
        "mode": _value_as_str(getattr(run, "mode", None)),
        "status": _value_as_str(getattr(run, "status", None)),
        "current_stage": _value_as_str(getattr(run, "current_agent", None)),
        "namespaces": {
            key: StateNamespaceSummary(**value).model_dump(mode="json")
            for key, value in namespaces.items()
        },
        "guards": guards,
    })


@router.get("/runs/{run_id}/timeline")
async def get_timeline(run_id: str, after_seq: int = Query(default=0, ge=0)) -> ApiResponse:
    request_id = _request_id()
    run = await _require_run(run_id)
    if run is None:
        return ApiResponse.failure(request_id, ErrorCode.RUN_NOT_FOUND, f"run not found: {run_id}")

    patches = await _get_patches(run_id, after_seq)
    events = await _get_events(run_id)
    items = _merge_timeline(patches, events)
    next_cursor = max((patch.seq for patch in patches), default=after_seq)
    return ApiResponse.success(request_id, {"items": items, "next_cursor": next_cursor})


@router.get("/runs/{run_id}/steps")
async def get_steps(run_id: str) -> ApiResponse:
    request_id = _request_id()
    run = await _require_run(run_id)
    if run is None:
        return ApiResponse.failure(request_id, ErrorCode.RUN_NOT_FOUND, f"run not found: {run_id}")

    patches = await _get_patches(run_id)
    by_author: OrderedDict[str, dict[str, Any]] = OrderedDict()
    for patch in patches:
        agent = _value_as_str(patch.author, "unknown") or "unknown"
        step = by_author.setdefault(agent, {
            "step_id": f"{run_id}:{agent}",
            "agent": agent,
            "status": "completed",
            "created_at": patch.created_at,
        })
        step["created_at"] = step["created_at"] or patch.created_at
    return ApiResponse.success(request_id, {
        "steps": [StepSummary(**step).model_dump(mode="json") for step in by_author.values()]
    })


@router.get("/runs/{run_id}/actions")
async def get_actions(run_id: str) -> ApiResponse:
    request_id = _request_id()
    run = await _require_run(run_id)
    if run is None:
        return ApiResponse.failure(request_id, ErrorCode.RUN_NOT_FOUND, f"run not found: {run_id}")

    patches = await _get_patches(run_id)
    return ApiResponse.success(request_id, {"actions": _merge_actions(patches)})


@router.post("/runs/{run_id}/messages")
async def add_message(
    run_id: str,
    req: MessageRequest,
    background_tasks: BackgroundTasks,
) -> ApiResponse:
    request_id = _request_id()
    run_repo = get_run_repo()
    run = await run_repo.get(run_id)
    if run is None:
        return ApiResponse.failure(request_id, ErrorCode.RUN_NOT_FOUND, f"run not found: {run_id}")
    if run.status in ("completed", "failed", "cancelled"):
        return ApiResponse.failure(
            request_id,
            ErrorCode.RUN_ALREADY_CLOSED,
            f"run already closed: {run_id}",
        )

    await run_repo.update_status(run_id, "running", getattr(run, "current_agent", None))
    background_tasks.add_task(
        run_workflow,
        run_id=run_id,
        user_message=req.message,
        project_id=getattr(run, "project_id", None),
        bus=get_event_bus(),
        run_repo=run_repo,
        registry=get_tool_registry(),
    )
    return ApiResponse.success(request_id, {"run_id": run_id, "status": "running"})


@router.post("/runs/{run_id}/cancel")
async def cancel_run(run_id: str) -> ApiResponse:
    request_id = _request_id()
    await get_run_repo().update_status(run_id, "cancelled", None)
    return ApiResponse.success(request_id, {"run_id": run_id, "status": "cancelled"})


@router.post("/runs/{run_id}/retry")
async def retry_run(run_id: str, req: RetryRequest) -> ApiResponse:
    _ = (run_id, req)
    return ApiResponse.failure(
        _request_id(),
        ErrorCode.NOT_IMPLEMENTED,
        "retry from_stage is not implemented in v1",
    )


@router.get("/runs")
async def list_runs(
    project_id: str | None = None,
    status: str | None = None,
    limit: int = Query(default=20, ge=0, le=100),
) -> ApiResponse:
    request_id = _request_id()
    runs = await get_run_repo().list(project_id=project_id, status=status, limit=limit)
    return ApiResponse.success(request_id, {"runs": [run.model_dump(mode="json") for run in runs]})
