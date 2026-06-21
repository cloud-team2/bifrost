"""Agentic ReAct 루프 (#633 harness 코어) — native tool-calling 으로 read-only 도구를 반복 호출.

기존 planner→retrieval 은 flat plan(도구를 한 번에 선택)이라, 한 도구의 결과를 다음 도구의
입력으로 잇는 chaining 을 못 했다(예: topology 로 커넥터 이름을 얻어 connector_status 호출 →
'connector_name 을 모른다'며 멈춤). 본 루프는 LLM function-calling 으로 도구를 반복 호출하며
직전 결과를 보고 다음 도구를 결정한다. read-only 도구만 노출하고 mutation 은 제외한다(거버넌스 분리).

SSE/evidence repo 와 분리된 순수 로직 — caller(retrieval)가 결과를 evidence 로 매핑한다.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field

from app.prompts.domain import DOMAIN_PRIMER
from app.schemas.state import RiskLevel
from app.schemas.tools import ToolContext, ToolResult, ToolStatus
from app.tools.registry import ToolClientRegistry

LOOP_SYSTEM_PROMPT = DOMAIN_PRIMER + """\
You are the Bifrost operations agent. 사용자 질문에 답하기 위해 read-only 조회 tool 을
반복 호출한다. 한 tool 의 결과(예: 파이프라인 topology 의 커넥터 이름)를 다음 tool 의 입력으로
이어서 호출(chaining)할 수 있다.

규칙:
- 필요한 만큼 tool 을 호출해 충분한 근거를 모은 뒤, 더 호출할 게 없으면 한국어 최종 답변을 작성한다.
- tool 의 필수 param 을 직전 결과/질의에서 확보할 수 없으면 그 tool 을 호출하지 말고,
  이미 확보한 근거로 답하거나 무엇을 더 알아야 하는지 안내한다.
- 수집 데이터에 근거해 정확히 답하고 상태값 의미(active=동작 중 등)를 지킨다.
- DB 테이블의 행 수·집계·실제 데이터 내용을 묻는 질문은 반드시 list_datasources 로 datasource_id 를
  얻은 뒤 sql_read(datasource_id, "SELECT ...") 로 조회한다. search_logs(애플리케이션 로그 검색)와
  혼동하지 마라.
- 클러스터·브로커·파티션(ISR/leader) 질문은 get_cluster_info 를 쓴다.
"""


@dataclass
class ToolCallRecord:
    tool_name: str
    params: dict
    result: ToolResult


@dataclass
class AgenticResult:
    answer: str
    calls: list[ToolCallRecord] = field(default_factory=list)
    used_llm: bool = True


def build_tool_schemas(registry: ToolClientRegistry) -> list[dict]:
    """read-only 도구를 OpenAI function-calling tool 스키마로 변환한다(mutation 제외)."""
    schemas: list[dict] = []
    for definition in registry.list_tools():
        if definition.risk != RiskLevel.READ_ONLY:
            continue
        params_schema = definition.params_model.model_json_schema()
        params_schema.pop("title", None)
        schemas.append(
            {
                "type": "function",
                "function": {
                    "name": definition.name,
                    "description": (
                        f"{definition.operation} — {definition.description}"
                        if definition.description
                        else f"{definition.operation} — Bifrost internal-ops read-only 조회"
                    ),
                    "parameters": params_schema,
                },
            }
        )
    return schemas


def _tool_message_content(result: ToolResult) -> str:
    """도구 결과를 LLM 에 돌려줄 직렬화(chaining 위해 구조화 데이터 포함, 길이 제한).

    raw_payload 에 Spring 전체 응답(result 본문 포함)이 있어 커넥터 이름 등 후속 도구 입력을
    추출할 수 있다. result 필드는 structured 도구만 채워지므로 raw_payload 를 우선 사용한다.
    """
    if result.status == ToolStatus.SUCCESS:
        data = result.raw_payload if result.raw_payload is not None else result.result
        payload = {"status": "success", "summary": result.summary, "data": data}
    else:
        msg = result.error.message if result.error else "조회 실패"
        payload = {"status": result.status.value, "error": msg}
    return json.dumps(payload, ensure_ascii=False, default=str)[:4000]


async def run_tool_loop(
    *,
    user_message: str,
    registry: ToolClientRegistry,
    context: ToolContext,
    provider,
    model: str | None = None,
    max_steps: int = 6,
) -> AgenticResult:
    """read-only 도구를 반복 호출하는 ReAct 루프. (answer, calls) 반환.

    provider.generate_with_tools 가 None 을 주면(LLM 미연결) used_llm=False 로 즉시 반환 →
    caller 가 기존 flat retrieval 로 폴백한다.
    """
    tools = build_tool_schemas(registry)
    user_content = user_message
    incident_id = getattr(context, "incident_id", None)
    if incident_id:
        # 인시던트 분석: 먼저 get_incident_summary(incident_id)로 맥락을 잡도록 유도(체이닝 시작점).
        user_content = (
            f"분석 대상 incident_id={incident_id}. 먼저 get_incident_summary 로 인시던트 맥락을 확인하라.\n"
            f"summary 의 connectors[].name 이 영향 파이프라인의 실제 커넥터명이므로, 이를 connector_name 으로\n"
            f"사용해 get_connector_task_trace·analyze_event_log 등으로 이어서 조사하라(텍스트 추측 금지).\n\n{user_message}"
        )
    messages: list[dict] = [
        {"role": "system", "content": LOOP_SYSTEM_PROMPT},
        {"role": "user", "content": user_content},
    ]
    calls: list[ToolCallRecord] = []

    for _ in range(max_steps):
        message = await provider.generate_with_tools(messages, tools, model=model)
        if message is None:
            return AgenticResult(answer="", calls=calls, used_llm=False)

        tool_calls = getattr(message, "tool_calls", None)
        if not tool_calls:
            return AgenticResult(answer=message.content or "", calls=calls)

        messages.append(
            {
                "role": "assistant",
                "content": message.content or "",
                "tool_calls": [
                    {
                        "id": tc.id,
                        "type": "function",
                        "function": {"name": tc.function.name, "arguments": tc.function.arguments},
                    }
                    for tc in tool_calls
                ],
            }
        )

        for tc in tool_calls:
            name = tc.function.name
            try:
                params = json.loads(tc.function.arguments or "{}")
            except json.JSONDecodeError:
                params = {}
            result = await registry.call_tool(name, params, context)
            calls.append(ToolCallRecord(tool_name=name, params=params, result=result))
            messages.append(
                {
                    "role": "tool",
                    "tool_call_id": tc.id,
                    "content": _tool_message_content(result),
                }
            )

    # max_steps 소진 → 도구 없이 최종 답변 강제
    messages.append(
        {"role": "user", "content": "더 이상 도구 호출 없이 지금까지 근거로 한국어 최종 답변을 작성하라."}
    )
    final = await provider.generate_with_tools(messages, [], model=model)
    answer = (getattr(final, "content", "") if final else "") or ""
    return AgenticResult(answer=answer, calls=calls)
