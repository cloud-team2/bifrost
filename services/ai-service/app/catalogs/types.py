"""Shared dataclass types for ai-service diagnosis catalogs.

The catalog modules keep static, reviewable data only.  These frozen types make
fixture construction stable for Classifier/RCA/Verifier/Remediation agents while
leaving runtime decision models in ``app.schemas.state``.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Literal, TypeAlias

CatalogLayer: TypeAlias = Literal[
    "source",
    "pipeline",
    "kafka",
    "sink",
    "infra",
    "change",
    "data_quality",
    "unknown",
]

DirectActionPolicy: TypeAlias = Literal[
    "no",
    "limited",
    "approval",
    "change_management",
    "escalation",
]

EvidenceKind: TypeAlias = Literal[
    "required",
    "supporting",
    "negative",
    "exclusion",
]

CausalityType: TypeAlias = Literal[
    "causal",
    "correlational",
    "temporal",
]

ActionTypeValue: TypeAlias = Literal[
    "runtime_tool",
    "workflow_action",
    "composite_action",
    "notification",
    "escalation",
]

RiskLevelValue: TypeAlias = Literal[
    "read_only",
    "low",
    "medium",
    "high",
    "forbidden",
]

RunbookDisposition: TypeAlias = Literal[
    "detailed_runbook",
    "diagnose_only",
    "escalation_only",
    "manual_change_required",
    "unsupported_v1",
]


@dataclass(frozen=True, slots=True)
class FailureType:
    """Observed incident classification consumed by the Classifier."""

    incident_type: str
    layer: CatalogLayer
    description: str
    signals: tuple[str, ...] = ()
    notes: tuple[str, ...] = ()


@dataclass(frozen=True, slots=True)
class RootCause:
    """Stable root-cause candidate consumed by RCA."""

    root_cause_id: str
    layer: CatalogLayer
    description: str
    owned_by: str
    direct_action_allowed: DirectActionPolicy
    default_confidence_cap: float = 0.88
    notes: tuple[str, ...] = ()


@dataclass(frozen=True, slots=True)
class EvidenceRule:
    """Evidence criterion for accepting or rejecting a root-cause candidate."""

    root_cause_id: str
    kind: EvidenceKind
    evidence: str
    example: str | None = None
    semantic_allowed: bool = True
    causality_type: CausalityType = "causal"
    temporality_required: bool = False
    causal_chain_step: int | None = None


@dataclass(frozen=True, slots=True)
class EvidenceProfile:
    """Grouped evidence rules for one root cause."""

    root_cause_id: str
    required: tuple[EvidenceRule, ...] = field(default_factory=tuple)
    supporting: tuple[EvidenceRule, ...] = field(default_factory=tuple)
    negative: tuple[EvidenceRule, ...] = field(default_factory=tuple)
    exclusion: tuple[EvidenceRule, ...] = field(default_factory=tuple)
    min_confidence_for_action: float = 0.80
    needs_more_evidence_band: tuple[float, float] = (0.60, 0.79)


@dataclass(frozen=True, slots=True)
class IncidentRootCauseMapEntry:
    """Priority mapping from observed incident type to RCA candidates."""

    incident_type: str
    root_cause_ids: tuple[str, ...]
    notes: tuple[str, ...] = ()


@dataclass(frozen=True, slots=True)
class RunbookActionTemplate:
    """Allowed remediation action template for a root cause."""

    action_name: str
    action_type: ActionTypeValue
    risk: RiskLevelValue
    description: str
    policy: str
    tool_name: str | None = None
    rollback_plan: str | None = None
    estimated_duration: str | None = None


@dataclass(frozen=True, slots=True)
class Runbook:
    """Remediation coverage and action templates for one root cause."""

    root_cause_id: str
    disposition: RunbookDisposition
    allowed_action_types: tuple[ActionTypeValue, ...]
    basis: str
    actions: tuple[RunbookActionTemplate, ...] = field(default_factory=tuple)
    forbidden_actions: tuple[str, ...] = ()
