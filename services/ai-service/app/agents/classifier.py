"""Classifier agent skeleton — Failure Types catalog 기준 incident type·scope 분류."""
from __future__ import annotations

from app.schemas.outputs import Classification, ClassifierOutput, IncidentTypeOutput, RetrievalOutput
from app.schemas.state import IncidentScope


async def run_classifier(user_message: str, retrieval_out: RetrievalOutput | None) -> ClassifierOutput:
    return ClassifierOutput(
        classification=Classification(
            incident_scope=IncidentScope.SINGLE,
            incident_types=[IncidentTypeOutput(type="unknown", confidence=0.0, evidence_ids=[])],
            needs_incident_group_analysis=False,
        )
    )
