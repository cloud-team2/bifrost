"""LLM provider abstraction — OpenAI AsyncClient wrapper."""
from __future__ import annotations

import logging

from app.core.config import settings

logger = logging.getLogger(__name__)


class LLMProvider:
    def __init__(self) -> None:
        self._client = None
        if settings.llm_api_key:
            try:
                from openai import AsyncOpenAI
                self._client = AsyncOpenAI(api_key=settings.llm_api_key)
            except ImportError:
                logger.warning("openai package not installed — LLM calls will return fallback text")

    async def generate(self, messages: list[dict], model: str | None = None) -> str:
        if self._client is None:
            evidence_lines = [m["content"] for m in messages if m.get("role") == "user"]
            return f"[demo — LLM 미연결] 질문에 대한 조회가 완료되었습니다.\n\n{evidence_lines[-1] if evidence_lines else ''}"
        model = model or settings.llm_default_model
        resp = await self._client.chat.completions.create(model=model, messages=messages)
        return resp.choices[0].message.content or ""


_provider: LLMProvider | None = None


def get_llm_provider() -> LLMProvider:
    global _provider
    if _provider is None:
        _provider = LLMProvider()
    return _provider
