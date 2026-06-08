"""Report agent — LLM으로 최종 답변 생성, LLM 미연결 시 fallback."""
from __future__ import annotations

from app.llm.provider import LLMProvider
from app.schemas.outputs import RetrievalOutput
from app.schemas.state import AgentMode


async def run_report(
    user_message: str,
    retrieval: RetrievalOutput,
    mode: AgentMode,
    llm: LLMProvider,
) -> str:
    evidence_summary = "\n".join(f"- {e.summary}" for e in retrieval.evidence_items)

    messages = [
        {
            "role": "system",
            "content": (
                "당신은 Bifrost 플랫폼의 DevOps 운영 도우미입니다. "
                "수집된 운영 데이터를 바탕으로 사용자 질문에 한국어로 답변하세요. "
                "근거 없는 추측은 하지 말고, 수집된 데이터 내에서만 답변하세요."
            ),
        },
        {
            "role": "user",
            "content": f"질문: {user_message}\n\n수집된 운영 데이터:\n{evidence_summary}",
        },
    ]

    return await llm.generate(messages)
