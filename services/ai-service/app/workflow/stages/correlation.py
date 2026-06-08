"""Correlation Engine skeleton — alert 그룹화 결정론적 단계."""
from __future__ import annotations

from app.schemas.state import CorrelationState


async def run_correlation(incident_id: str | None = None) -> CorrelationState:
    return CorrelationState(correlation_id=None, related_alert_ids=[])
