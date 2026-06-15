"""Policy Matrix — action_type + risk → PolicyDecision 룰 기반 lookup.

계약 §9: 불명확하면 더 안전한 decision으로 상향한다.
"""
from __future__ import annotations

from dataclasses import dataclass

from app.schemas.state import ActionType, PolicyDecisionType, RiskLevel

_DENY = PolicyDecisionType.DENY
_REQUIRE_APPROVAL = PolicyDecisionType.REQUIRE_APPROVAL
_REQUIRE_CHANGE = PolicyDecisionType.REQUIRE_CHANGE_MANAGEMENT
_ALLOW = PolicyDecisionType.ALLOW


@dataclass(frozen=True)
class PolicyRule:
    decision: PolicyDecisionType
    reason: str


# (action_type, risk) → PolicyRule. 명시되지 않은 조합은 _FALLBACK으로 처리.
_MATRIX: dict[tuple[ActionType, RiskLevel], PolicyRule] = {
    # RUNTIME_TOOL
    (ActionType.RUNTIME_TOOL, RiskLevel.FORBIDDEN):  PolicyRule(_DENY, "금지된 위험 수준"),
    (ActionType.RUNTIME_TOOL, RiskLevel.HIGH):        PolicyRule(_REQUIRE_APPROVAL, "고위험 조치 — 사람 승인 필요"),
    (ActionType.RUNTIME_TOOL, RiskLevel.MEDIUM):      PolicyRule(_REQUIRE_APPROVAL, "중위험 조치 — 사람 승인 필요"),
    (ActionType.RUNTIME_TOOL, RiskLevel.LOW):         PolicyRule(_ALLOW, "저위험 조치 — 자동 허용"),
    (ActionType.RUNTIME_TOOL, RiskLevel.READ_ONLY):   PolicyRule(_ALLOW, "읽기 전용 — 자동 허용"),
    # WORKFLOW_ACTION
    (ActionType.WORKFLOW_ACTION, RiskLevel.FORBIDDEN): PolicyRule(_DENY, "금지된 위험 수준"),
    (ActionType.WORKFLOW_ACTION, RiskLevel.HIGH):      PolicyRule(_REQUIRE_APPROVAL, "고위험 워크플로우 — 사람 승인 필요"),
    (ActionType.WORKFLOW_ACTION, RiskLevel.MEDIUM):    PolicyRule(_REQUIRE_CHANGE, "중위험 워크플로우 — 변경관리 티켓 필요"),
    (ActionType.WORKFLOW_ACTION, RiskLevel.LOW):       PolicyRule(_ALLOW, "저위험 워크플로우 — 자동 허용"),
    (ActionType.WORKFLOW_ACTION, RiskLevel.READ_ONLY): PolicyRule(_ALLOW, "읽기 전용 — 자동 허용"),
    # COMPOSITE_ACTION
    (ActionType.COMPOSITE_ACTION, RiskLevel.FORBIDDEN): PolicyRule(_DENY, "금지된 위험 수준"),
    (ActionType.COMPOSITE_ACTION, RiskLevel.HIGH):      PolicyRule(_REQUIRE_APPROVAL, "복합 고위험 조치 — 사람 승인 필요"),
    (ActionType.COMPOSITE_ACTION, RiskLevel.MEDIUM):    PolicyRule(_REQUIRE_APPROVAL, "복합 중위험 조치 — 상향(불명확) 승인 필요"),
    (ActionType.COMPOSITE_ACTION, RiskLevel.LOW):       PolicyRule(_REQUIRE_CHANGE, "복합 저위험 조치 — 변경관리 필요"),
    (ActionType.COMPOSITE_ACTION, RiskLevel.READ_ONLY): PolicyRule(_ALLOW, "읽기 전용 — 자동 허용"),
    # NOTIFICATION
    (ActionType.NOTIFICATION, RiskLevel.FORBIDDEN): PolicyRule(_DENY, "금지된 위험 수준"),
    (ActionType.NOTIFICATION, RiskLevel.HIGH):      PolicyRule(_REQUIRE_APPROVAL, "고위험 알림 — 사람 승인 필요"),
    (ActionType.NOTIFICATION, RiskLevel.MEDIUM):    PolicyRule(_ALLOW, "중위험 알림 — 자동 허용"),
    (ActionType.NOTIFICATION, RiskLevel.LOW):       PolicyRule(_ALLOW, "저위험 알림 — 자동 허용"),
    (ActionType.NOTIFICATION, RiskLevel.READ_ONLY): PolicyRule(_ALLOW, "읽기 전용 — 자동 허용"),
    # ESCALATION (항상 승인 필요)
    (ActionType.ESCALATION, RiskLevel.FORBIDDEN): PolicyRule(_DENY, "금지된 위험 수준"),
    (ActionType.ESCALATION, RiskLevel.HIGH):      PolicyRule(_REQUIRE_APPROVAL, "에스컬레이션 — 사람 승인 필요"),
    (ActionType.ESCALATION, RiskLevel.MEDIUM):    PolicyRule(_REQUIRE_APPROVAL, "에스컬레이션 — 사람 승인 필요"),
    (ActionType.ESCALATION, RiskLevel.LOW):       PolicyRule(_REQUIRE_APPROVAL, "에스컬레이션 — 사람 승인 필요"),
    (ActionType.ESCALATION, RiskLevel.READ_ONLY): PolicyRule(_ALLOW, "읽기 전용 에스컬레이션 — 자동 허용"),
}

# 매트릭스에 없는 조합: 불명확 → 안전한 방향으로 상향
_FALLBACK = PolicyRule(_REQUIRE_APPROVAL, "매트릭스 미정의 조합 — 불명확하여 승인으로 상향")


def lookup(action_type: ActionType, risk: RiskLevel) -> PolicyRule:
    return _MATRIX.get((action_type, risk), _FALLBACK)
