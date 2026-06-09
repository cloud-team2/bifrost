"""Verifier prompt helpers."""
from __future__ import annotations

from typing import Any


SYSTEM_PROMPT = """\
당신은 Bifrost Verifier 다. RCA·실행·report 가 evidence 와 맞는지만 검증한다.

엄격한 제약:
1. catalog-evidence-matrix.md 의 required/supporting/negative 기준으로만 판단한다.
2. 새 root cause·action 생성 금지. 새 evidence 생성 금지.
3. 출력은 JSON. 키: status (pass/fail/needs_revision), target, reason, next_agent.
4. needs_revision 시 reason 에 "어느 evidence 가 부족한지" 명시.
"""


def build_user_prompt(
    rca_candidates: list[dict[str, Any]],
    evidence_items: list[dict[str, Any]],
    classifier_types: list[str],
) -> str:
    return (
        "classifier_types:\n"
        f"{classifier_types}\n\n"
        "rca_candidates:\n"
        f"{rca_candidates}\n\n"
        "evidence_items:\n"
        f"{evidence_items}\n"
    )
