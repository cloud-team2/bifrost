"""Report agent — LLM으로 최종 답변 생성, LLM 미연결 시 fallback.

RCA Assistant 가 catalog 에서 선택한 root cause 후보·confidence·evidence_gap 을
Report 입력으로 받아(#451) 최종 답변에 진단 결론을 반영한다. RCA 결과가 없으면
(simple_query 등) 기존처럼 evidence 요약만으로 답변한다.
"""
from __future__ import annotations

from app.llm.provider import LLMProvider
from app.schemas.outputs import ClassifierOutput, RcaOutput, RetrievalOutput
from app.schemas.state import AgentMode


async def run_report(
    user_message: str,
    retrieval: RetrievalOutput,
    mode: AgentMode,
    llm: LLMProvider,
    rca_out: RcaOutput | None = None,
    classifier_out: ClassifierOutput | None = None,
) -> str:
    evidence_summary = "\n".join(f"- {e.summary}" for e in retrieval.evidence_items)
    diagnosis = _diagnosis_block(rca_out, classifier_out)

    system_content = (
        "당신은 Bifrost 플랫폼의 DevOps 운영 도우미입니다. "
        "수집된 운영 데이터를 바탕으로 사용자 질문에 한국어로 답변하세요. "
        "근거 없는 추측은 하지 말고, 수집된 데이터 내에서만 답변하세요."
    )
    if diagnosis:
        system_content += (
            " '진단 결과' 섹션에 RCA 가 선택한 root cause 후보와 confidence 가 주어지면, "
            "그 결론을 evidence 근거와 함께 설명하세요. confidence 가 낮거나 evidence gap 이 있으면 "
            "불확실성과 추가 확인(escalation) 필요성을 명시하세요. "
            "제공된 root cause 후보 밖의 원인을 임의로 만들지 마세요."
        )

    user_parts = [
        f"질문: {user_message}",
        "",
        "수집된 운영 데이터:",
        evidence_summary or "- (수집된 evidence 없음)",
    ]
    if diagnosis:
        user_parts += ["", diagnosis]

    messages = [
        {"role": "system", "content": system_content},
        {"role": "user", "content": "\n".join(user_parts)},
    ]

    return await llm.generate(messages)


def _diagnosis_block(
    rca_out: RcaOutput | None,
    classifier_out: ClassifierOutput | None,
) -> str:
    """RCA·분류 결과를 LLM 프롬프트용 진단 블록으로 직렬화. 후보 없으면 빈 문자열."""
    if rca_out is None or not rca_out.root_cause_candidates:
        return ""

    lines = ["진단 결과 (RCA Assistant 가 catalog 에서 선택한 후보):"]

    if classifier_out is not None and classifier_out.classification.incident_types:
        top_type = classifier_out.classification.incident_types[0]
        scope = classifier_out.classification.incident_scope.value
        lines.append(
            f"- 분류: {top_type.type} (confidence {top_type.confidence:.2f}), scope={scope}"
        )

    for i, c in enumerate(rca_out.root_cause_candidates, start=1):
        marker = "선택" if i == 1 else f"후보{i}"
        lines.append(
            f"- [{marker}] root_cause_id={c.root_cause_id}, confidence={c.confidence:.2f}, "
            f"required_evidence_satisfied={c.required_evidence_satisfied}"
        )
        lines.append(f"    설명: {c.explanation}")
        if c.supporting_evidence_ids:
            lines.append(f"    근거 evidence: {', '.join(c.supporting_evidence_ids)}")
        if c.evidence_gap:
            lines.append(f"    evidence gap(불확실): {', '.join(c.evidence_gap)}")

    top = rca_out.root_cause_candidates[0]
    if (
        not top.required_evidence_satisfied
        or top.evidence_gap
        or top.confidence < 0.5
        or "unknown" in top.root_cause_id.lower()
    ):
        lines.append(
            "- ⚠ 불확실성: 선택 후보의 evidence 가 충분치 않거나 confidence 가 낮습니다. "
            "단정하지 말고 추가 확인/escalation 필요성을 답변에 명시하세요."
        )

    return "\n".join(lines)
