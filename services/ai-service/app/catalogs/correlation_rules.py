"""Correlation rules — alert 병합 규칙 (rule/score/time-window 기반).

실제 alert 스트림 연동 전까지 keyword/severity 기반 단순 그룹핑으로 동작한다.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta

from app.schemas.state import IncidentScope

_MULTI_INCIDENT_KEYWORDS = (
    "여러", "다수", "multiple", "cascading", "전체", "all", "모든",
    "연쇄", "파급", "widespread",
)

_GROUP_WINDOW_MINUTES = 5


@dataclass
class Alert:
    alert_id: str
    severity: str
    source: str
    occurred_at: datetime = field(default_factory=datetime.utcnow)
    labels: dict[str, str] = field(default_factory=dict)


@dataclass
class AlertGroup:
    group_id: str
    alerts: list[Alert] = field(default_factory=list)
    common_labels: dict[str, str] = field(default_factory=dict)


def merge(alerts: list[Alert]) -> list[AlertGroup]:
    """severity + time-window(5분) 기준으로 alert를 그룹핑한다."""
    if not alerts:
        return []

    sorted_alerts = sorted(alerts, key=lambda a: a.occurred_at)
    groups: list[AlertGroup] = []
    current_group: AlertGroup | None = None

    for alert in sorted_alerts:
        if current_group is None:
            current_group = AlertGroup(
                group_id=f"grp_{alert.alert_id}",
                alerts=[alert],
                common_labels={"severity": alert.severity},
            )
        else:
            last = current_group.alerts[-1]
            time_gap = alert.occurred_at - last.occurred_at
            same_severity = alert.severity == last.severity
            within_window = time_gap <= timedelta(minutes=_GROUP_WINDOW_MINUTES)

            if same_severity and within_window:
                current_group.alerts.append(alert)
            else:
                groups.append(current_group)
                current_group = AlertGroup(
                    group_id=f"grp_{alert.alert_id}",
                    alerts=[alert],
                    common_labels={"severity": alert.severity},
                )

    if current_group:
        groups.append(current_group)

    return groups


def infer_scope_from_message(user_message: str) -> IncidentScope:
    """user_message 키워드로 단일/복수 incident scope를 추론한다."""
    lower = user_message.lower()
    if any(kw in lower for kw in _MULTI_INCIDENT_KEYWORDS):
        return IncidentScope.INCIDENT_GROUP
    return IncidentScope.SINGLE
