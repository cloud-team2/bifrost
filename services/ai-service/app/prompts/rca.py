"""RCA assistant prompt construction."""
from __future__ import annotations

from app.prompts.domain import DOMAIN_PRIMER

import json

from app.catalogs.types import EvidenceProfile, RootCause

SYSTEM_PROMPT = DOMAIN_PRIMER + """\
당신은 Bifrost RCA Assistant 다. RCA Engine 이 아니다.

엄격한 제약:
1. catalog-root-causes.md §3~§10 안 root_cause_id 만 선택한다. 신규 id 절대 금지.
2. catalog-evidence-matrix.md 의 required/supporting/negative 기준으로만 후보를 평가한다.
3. required evidence 가 없으면 confidence ≤ default_confidence_cap. 부족하면 confidence < 0.6.
4. evidence 가 전부 부족하면 root_cause_id=UNKNOWN_WITH_EVIDENCE_GAP 로 반환한다.
5. 출력은 JSON 만. 키: selected_root_cause_id, confidence (0.0-1.0), explanation (evidence 기반, raw 로그 금지).
"""


def build_user_prompt(
    candidate_pool: list[tuple[RootCause, EvidenceProfile]],
    evidence_summaries: list[tuple[str, str]],
    classifier_types: list[str],
) -> str:
    """Build a compact RCA prompt from redacted summaries and catalog criteria."""
    payload = {
        "classifier_types": classifier_types,
        "evidence_summaries": [
            {"evidence_id": evidence_id, "summary": summary}
            for evidence_id, summary in evidence_summaries
        ],
        "candidate_pool": [
            {
                "root_cause_id": root_cause.root_cause_id,
                "layer": root_cause.layer,
                "description": root_cause.description,
                "owned_by": root_cause.owned_by,
                "default_confidence_cap": root_cause.default_confidence_cap,
                "required": [rule.evidence for rule in profile.required],
                "supporting": [rule.evidence for rule in profile.supporting],
                "negative": [rule.evidence for rule in profile.negative],
            }
            for root_cause, profile in candidate_pool
        ],
        "output_schema": {
            "selected_root_cause_id": "CATALOG_ROOT_CAUSE_ID",
            "confidence": 0.0,
            "explanation": "short evidence-based explanation without raw logs",
        },
    }
    return json.dumps(payload, ensure_ascii=False, indent=2)
