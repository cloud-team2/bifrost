"""Classifier prompt construction."""
from __future__ import annotations

import json

from app.catalogs.types import FailureType

SYSTEM_PROMPT = """\
당신은 Bifrost AI Agent 의 Classifier 다. 역할은 다음으로만 한정된다:
1. catalog-failure-types.md 의 id 안에서만 incident_type 을 선택한다.
2. catalog 밖 type 을 생성하지 않는다. 신규 id 만들기 금지.
3. evidence 가 부족하면 type=UNKNOWN_NEEDS_MORE_EVIDENCE 로 반환한다.
4. 출력은 JSON 만, 다른 자연어 금지. 키: incident_types[], incident_scope, needs_incident_group_analysis.
"""


def build_user_prompt(
    user_message: str,
    evidence_summaries: list[str],
    candidate_types: list[FailureType],
) -> str:
    """Build a compact classifier prompt from redacted summaries only."""
    payload = {
        "user_message": user_message,
        "evidence_summaries": evidence_summaries,
        "candidate_types": [
            {
                "incident_type": item.incident_type,
                "layer": item.layer,
                "description": item.description,
                "signals": list(item.signals),
            }
            for item in candidate_types
        ],
        "output_schema": {
            "incident_types": [{"type": "CATALOG_ID", "confidence": 0.0}],
            "incident_scope": "single|incident_group",
            "needs_incident_group_analysis": False,
        },
    }
    return json.dumps(payload, ensure_ascii=False, indent=2)
