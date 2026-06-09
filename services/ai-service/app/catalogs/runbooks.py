"""Runbook catalog — 장애 유형별 조치 후보 매핑.

tool_name은 registry.py ToolDefinition.name과 일치해야 한다.
"""
from __future__ import annotations

from dataclasses import dataclass, field

from app.schemas.state import ActionType, RiskLevel


@dataclass(frozen=True)
class RunbookAction:
    tool_name: str
    action_type: ActionType
    risk: RiskLevel
    description: str
    rollback_plan: str | None = None
    estimated_duration: str | None = None


@dataclass(frozen=True)
class Runbook:
    incident_type: str
    actions: list[RunbookAction] = field(default_factory=list)


_RUNBOOKS: list[Runbook] = [
    Runbook(
        incident_type="connector_failed",
        actions=[
            RunbookAction(
                tool_name="restart_connector",
                action_type=ActionType.RUNTIME_TOOL,
                risk=RiskLevel.HIGH,
                description="FAILED 상태 커넥터를 재시작합니다.",
                rollback_plan="재시작 실패 시 pause 후 원인 분석",
                estimated_duration="30s",
            ),
        ],
    ),
    Runbook(
        incident_type="connector_lagging",
        actions=[
            RunbookAction(
                tool_name="pause_connector",
                action_type=ActionType.RUNTIME_TOOL,
                risk=RiskLevel.MEDIUM,
                description="지연 중인 커넥터를 일시 정지합니다.",
                rollback_plan="resume_connector 로 재개",
                estimated_duration="10s",
            ),
            RunbookAction(
                tool_name="resume_connector",
                action_type=ActionType.RUNTIME_TOOL,
                risk=RiskLevel.MEDIUM,
                description="정지된 커넥터를 재개합니다.",
                rollback_plan="pause_connector 로 재정지",
                estimated_duration="10s",
            ),
        ],
    ),
    Runbook(
        incident_type="consumer_lag_high",
        actions=[
            RunbookAction(
                tool_name="restart_consumer_group",
                action_type=ActionType.RUNTIME_TOOL,
                risk=RiskLevel.HIGH,
                description="lag이 높은 consumer group을 재시작합니다.",
                rollback_plan="offset reset 후 재시작 고려",
                estimated_duration="60s",
            ),
        ],
    ),
]

_INDEX: dict[str, Runbook] = {rb.incident_type: rb for rb in _RUNBOOKS}


def get_runbook(incident_type: str) -> Runbook | None:
    return _INDEX.get(incident_type)


def get_actions_for_incident(incident_type: str) -> list[RunbookAction]:
    rb = _INDEX.get(incident_type)
    return rb.actions if rb else []
