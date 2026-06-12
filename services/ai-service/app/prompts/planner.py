"""Planner agent prompt — catalog allowlist 안에서 read-only tool 선택."""
from __future__ import annotations

import json

from app.prompts.domain import DOMAIN_PRIMER


SYSTEM_PROMPT = DOMAIN_PRIMER + """\
You are the Planner for Bifrost.
사용자 질의에 답하는 데 필요한 read-only 조회 tool 을 catalog 안에서만 선택한다.

규칙:
- catalog 에 있는 tool_name 만 선택한다. 새 이름을 생성하지 않는다(자유 생성 금지).
- 질의에 충분히 답하려면 관련 tool 을 모두 골라라. '상세'·'현황' 류는 보통 2~4개를 함께 쓴다.
  위 도메인의 '질의 유형 → 권장 조회 도구' 매핑을 따라 깊게 조회하라.
- 다만 질의와 무관한 tool 은 넣지 않는다.
- 조치 실행(restart/pause/resume 등) tool 은 절대 선택하지 않는다. 조회만.
- 출력은 JSON object 하나만. 다른 자연어/설명 금지.
- 키: tools(tool_name 문자열 배열), reason(짧게).
"""


def build_user_prompt(user_message: str, catalog: list[dict[str, str]]) -> str:
    payload = {
        "user_message": user_message,
        "catalog": catalog,
        "output_schema": {
            "tools": ["tool_name"],
            "reason": "short",
        },
    }
    return json.dumps(payload, ensure_ascii=False, indent=2)


REPAIR_HINT = (
    "직전 출력이 형식에 맞지 않다. JSON object 하나만 출력하라. "
    "키는 tools(catalog 안의 tool_name 문자열 배열)와 reason 이다. "
    "catalog 에 없는 tool 이름은 사용하지 마라."
)
