"""Catalog and tool metadata API."""
from __future__ import annotations

import uuid
from typing import Any

from fastapi import APIRouter, status
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from app.catalogs.failure_types import list_failure_types
from app.catalogs.incident_rootcause_map import INCIDENT_ROOT_CAUSE_MAP
from app.catalogs.kedb import get_static_kedb_record
from app.catalogs.policy_matrix import lookup
from app.catalogs.root_causes import list_root_causes
from app.catalogs.runbooks import list_runbooks
from app.core.config import settings
from app.schemas import ApiResponse
from app.schemas.state import ActionType, RiskLevel
from app.schemas.tools import ToolContext, ToolStatus
from app.tools.registry import ToolDefinition, get_tool_registry

router = APIRouter()


class ExecuteToolRequest(BaseModel):
    project_id: str
    params: dict[str, Any] = Field(default_factory=dict)


def _request_id() -> str:
    return f"req_{uuid.uuid4().hex[:12]}"


def _failure(
    request_id: str,
    code: str,
    message: str,
    *,
    status_code: int = status.HTTP_404_NOT_FOUND,
    retryable: bool = False,
) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        content={
            "ok": False,
            "request_id": request_id,
            "data": None,
            "error": {"code": code, "message": message, "retryable": retryable},
        },
    )


def _params_schema(model: type[BaseModel]) -> dict[str, Any]:
    schema = model.model_json_schema()
    schema["required"] = [
        field.alias or name
        for name, field in model.model_fields.items()
        if field.is_required()
    ]
    return schema


def _tool_summary(definition: ToolDefinition) -> dict[str, Any]:
    return {
        "name": definition.name,
        "description": definition.description,
        "operation": definition.operation,
        "risk": definition.risk.value,
        "method": definition.method,
        "path_template": definition.path_template,
        "params_schema": _params_schema(definition.params_model),
        # 그룹형 명령 팔레트(한국어) 메타 — group이 비면 팔레트 미노출(#599 후속).
        "group": definition.group,
        "label_ko": definition.label_ko,
    }


def _tool_detail(definition: ToolDefinition) -> dict[str, Any]:
    data = _tool_summary(definition)
    data.update(
        {
            "params_schema": _params_schema(definition.params_model),
            "result_schema": definition.result_model.model_json_schema(),
        }
    )
    return data


@router.get("/catalogs/failure-types")
async def get_failure_types() -> ApiResponse:
    return ApiResponse.success(
        _request_id(),
        {
            "items": [
                {
                    "incident_type": item.incident_type,
                    "layer": item.layer,
                    "description": item.description,
                    "signals": list(item.signals),
                }
                for item in list_failure_types()
            ],
            "version": settings.catalog_version,
        },
    )


@router.get("/catalogs/incident-root-cause-map")
async def get_incident_root_cause_map() -> ApiResponse:
    return ApiResponse.success(
        _request_id(),
        {
            "mapping": {item.incident_type: list(item.root_cause_ids) for item in INCIDENT_ROOT_CAUSE_MAP},
            "version": settings.catalog_version,
        },
    )


@router.get("/catalogs/root-causes")
async def get_root_causes() -> ApiResponse:
    return ApiResponse.success(
        _request_id(),
        {
            "items": [
                {
                    "root_cause_id": item.root_cause_id,
                    "layer": item.layer,
                    "owned_by": item.owned_by,
                    "direct_action_allowed": item.direct_action_allowed,
                    "default_confidence_cap": item.default_confidence_cap,
                    "kedb": _kedb_summary(item.root_cause_id),
                }
                for item in list_root_causes()
            ],
            "version": settings.catalog_version,
        },
    )


def _kedb_summary(root_cause_id: str) -> dict[str, object] | None:
    record = get_static_kedb_record(root_cause_id)
    if record is None:
        return None
    return {
        "owner": record.owner,
        "verified_fixes": list(record.verified_fixes),
        "recurrence_count": record.recurrence_count,
        "last_seen": record.last_seen,
        "incident_links": list(record.incident_links),
    }


@router.get("/catalogs/policies")
async def get_policies() -> ApiResponse:
    items = []
    for action_type in ActionType:
        for risk in RiskLevel:
            rule = lookup(action_type, risk)
            items.append(
                {
                    "action_type": action_type.value,
                    "risk": risk.value,
                    "decision": rule.decision.value,
                    "reason": rule.reason,
                }
            )
    return ApiResponse.success(_request_id(), {"items": items, "version": settings.catalog_version})


@router.get("/catalogs/runbooks")
async def get_runbooks() -> ApiResponse:
    return ApiResponse.success(
        _request_id(),
        {
            "items": [
                {
                    "root_cause_id": item.root_cause_id,
                    "disposition": item.disposition,
                    "allowed_action_types": list(item.allowed_action_types),
                    "basis": item.basis,
                    "actions": [
                        {
                            "action_name": action.action_name,
                            "action_type": action.action_type,
                            "risk": action.risk,
                            "tool_name": action.tool_name,
                        }
                        for action in item.actions
                    ],
                    "forbidden_actions": list(item.forbidden_actions),
                }
                for item in list_runbooks()
            ],
            "version": settings.catalog_version,
        },
    )


@router.get("/tools")
async def list_tools() -> ApiResponse:
    tools = [_tool_summary(definition) for definition in get_tool_registry().list_tools()]
    return ApiResponse.success(_request_id(), {"tools": tools})


@router.get("/tools/{tool_name}")
async def get_tool(tool_name: str) -> Any:
    request_id = _request_id()
    definition = get_tool_registry().get_definition(tool_name)
    if definition is None:
        return _failure(request_id, "TOOL_NOT_FOUND", f"tool not found: {tool_name}")
    return ApiResponse.success(request_id, _tool_detail(definition))


@router.post("/tools/{tool_name}/execute")
async def execute_read_tool(tool_name: str, req: ExecuteToolRequest) -> Any:
    request_id = _request_id()
    registry = get_tool_registry()
    definition = registry.get_definition(tool_name)
    if definition is None:
        return _failure(request_id, "TOOL_NOT_FOUND", f"tool not found: {tool_name}")
    if definition.risk != RiskLevel.READ_ONLY or definition.requires_approval:
        return _failure(
            request_id,
            "POLICY_DENIED",
            f"tool is not a read-only slash command target: {tool_name}",
            status_code=status.HTTP_400_BAD_REQUEST,
        )

    context = ToolContext(
        run_id=f"slash_{uuid.uuid4().hex[:16]}",
        step_id=str(uuid.uuid4()),
        agent_name="slash_command",
        project_id=req.project_id,
        request_id=request_id,
    )
    tool_result, result = await registry.call_tool_with_data(tool_name, req.params, context)
    if tool_result.status != ToolStatus.SUCCESS:
        error = tool_result.error
        code = getattr(error.code, "value", error.code) if error else "SPRING_BACKEND_ERROR"
        message = error.message if error else tool_result.summary
        retryable = bool(error.retryable) if error else False
        return _failure(
            request_id,
            str(code),
            message,
            status_code=status.HTTP_400_BAD_REQUEST,
            retryable=retryable,
        )

    return ApiResponse.success(
        request_id,
        {
            "tool_result": tool_result.model_dump(mode="json"),
            "result": result,
        },
    )
