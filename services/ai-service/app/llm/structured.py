"""Structured LLM 호출 헬퍼 — JSON 파싱 + 1회 repair.

Router·Planner 처럼 lightweight LLM 으로 structured 분류를 수행하는 agent 가 공유한다.
LLM 미가용(client 미설정)·파싱 실패·검증 실패 시 None 을 반환해 호출자가 keyword
fallback 으로 회귀 없이 복구하도록 한다.
"""
from __future__ import annotations

import json
import re
from typing import Any, Callable, TypeVar

T = TypeVar("T")

# validate(parsed_dict) -> 정상이면 결과(T), 아니면 None.
Validator = Callable[[dict[str, Any]], "T | None"]


def parse_json_object(raw_response: str) -> dict[str, Any] | None:
    """LLM 응답에서 첫 JSON object 를 추출한다. code-fence/잡설을 허용한다."""
    if not raw_response:
        return None
    try:
        value = json.loads(raw_response)
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", raw_response, re.DOTALL)
        if not match:
            return None
        try:
            value = json.loads(match.group(0))
        except json.JSONDecodeError:
            return None
    return value if isinstance(value, dict) else None


def _model_for(agent_name: str) -> str | None:
    try:
        from app.llm.model_router import model_for_agent
    except Exception:
        return None
    try:
        return model_for_agent(agent_name)
    except Exception:
        return None


async def complete_structured(
    agent_name: str,
    messages: list[dict[str, str]],
    validate: Validator[T],
    *,
    repair_hint: str,
) -> T | None:
    """messages 로 LLM 을 호출해 structured 결과를 얻는다.

    1차 응답이 파싱/검증에 실패하면 repair_hint 를 덧붙여 1회만 재시도한다.
    어떤 단계에서든 실패하면 None 을 반환한다(호출자가 fallback).
    """
    try:
        from app.llm.provider import get_llm_provider
    except Exception:
        return None

    model = _model_for(agent_name)
    provider = get_llm_provider()

    try:
        raw = await provider.generate(messages, model=model)
    except Exception:
        return None

    parsed = parse_json_object(raw)
    result = validate(parsed) if parsed is not None else None
    if result is not None:
        return result

    # 1회 repair — 직전 응답과 교정 지시를 덧붙여 재요청한다.
    repair_messages = [
        *messages,
        {"role": "assistant", "content": raw},
        {"role": "user", "content": repair_hint},
    ]
    try:
        repaired = await provider.generate(repair_messages, model=model)
    except Exception:
        return None

    parsed = parse_json_object(repaired)
    return validate(parsed) if parsed is not None else None
