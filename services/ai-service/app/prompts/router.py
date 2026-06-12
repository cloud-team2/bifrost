"""Router agent prompt — 사용자 메시지를 mode + remediation_requested 로 분류."""
from __future__ import annotations

from app.prompts.domain import DOMAIN_PRIMER

import json

from app.schemas.state import AgentMode

SYSTEM_PROMPT = DOMAIN_PRIMER + """\
You are the Router for Bifrost incident-response orchestration.
사용자의 가장 최근 메시지를 정확히 하나의 mode 로 분류한다.

mode 정의:
- simple_query: 정보·현황·로그·메트릭 조회만 원함. 조치 실행/승인 의도 없음.
- incident_analysis: 장애·이상·오류의 원인 분석을 요청. (키워드가 없어도 증상 서술이면 해당)
- action_execution: 지금 실제 조치를 실행하라는 명시적 지시(재시작/재기동/롤백/적용 등).
- approval_decision: 직전에 제안된 조치에 대한 승인 또는 거절.

remediation_requested (bool):
- incident_analysis 이면서 원인뿐 아니라 조치 후보(해결 방법 추천)도 함께 원하면 true.
- 그 외에는 false.

규칙:
- mode 는 위 4개 값 중 하나만. 새 값 생성 금지.
- 출력은 JSON object 하나만. 다른 자연어/설명 금지.
- 키: mode, remediation_requested, reason(짧게).
"""

ALLOWED_MODES = [mode.value for mode in AgentMode]


def build_user_prompt(user_message: str) -> str:
    payload = {
        "user_message": user_message,
        "allowed_modes": ALLOWED_MODES,
        "output_schema": {
            "mode": "simple_query|incident_analysis|action_execution|approval_decision",
            "remediation_requested": False,
            "reason": "short",
        },
    }
    return json.dumps(payload, ensure_ascii=False, indent=2)


REPAIR_HINT = (
    "직전 출력이 형식에 맞지 않다. JSON object 하나만 출력하라. "
    f"키는 mode, remediation_requested, reason 이고 mode 는 {ALLOWED_MODES} 중 하나여야 한다."
)
