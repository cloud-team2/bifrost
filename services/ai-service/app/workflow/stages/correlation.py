"""Correlation Engine — alert 그룹화 결정론적 단계."""
from __future__ import annotations

from uuid import uuid4

from app.catalogs import correlation_rules
from app.schemas.outputs import AlertGroup as OutputAlertGroup, CorrelationOutput
from app.schemas.state import IncidentScope


async def run_correlation(
    user_message: str = "",
    alert_ids: list[str] | None = None,
) -> CorrelationOutput:
    """alert_ids가 있으면 time-window 병합, 없으면 user_message로 scope 추론."""
    correlation_id = str(uuid4())

    if alert_ids:
        from datetime import datetime
        from app.catalogs.correlation_rules import Alert, merge

        alerts = [
            Alert(alert_id=aid, severity="WARNING", source="unknown")
            for aid in alert_ids
        ]
        raw_groups = merge(alerts)
        scope = IncidentScope.INCIDENT_GROUP if len(raw_groups) > 1 else IncidentScope.SINGLE
        groups = [
            OutputAlertGroup(
                group_id=g.group_id,
                alert_ids=[a.alert_id for a in g.alerts],
                common_labels=g.common_labels,
            )
            for g in raw_groups
        ]
        related = alert_ids
    else:
        scope = correlation_rules.infer_scope_from_message(user_message)
        groups = []
        related = []

    return CorrelationOutput(
        correlation_id=correlation_id,
        scope=scope,
        groups=groups,
        related_alert_ids=related,
    )
