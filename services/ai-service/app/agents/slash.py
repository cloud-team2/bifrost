"""Slash command 결정적 단축 경로 — LLM 호출 없이 read-only 조회로 라우팅.

#504 — 사용자가 `/` 로 시작하는 슬래시 명령을 입력하면 Router/Planner 의 LLM
분류를 건너뛰고 정해진 read-only tool 로 바로 보낸다. 미등록 명령·오타는 None 을
돌려 기존 LLM(→ keyword) 경로로 안전하게 흘려보낸다(#483 회귀 보존).

선택 가능한 tool 은 모두 planner._READ_TOOL_ALLOWLIST 안에 있어야 한다 — 별도
목록을 하드코딩하지 않고 planner 의 frozenset 을 단일 진실 원천으로 재사용한다.
"""
from __future__ import annotations

from dataclasses import dataclass, field

from app.schemas.state import AgentMode


@dataclass(frozen=True)
class SlashResolution:
    command: str
    mode: AgentMode
    tool: str | None
    params: dict
    needs_identifier: bool
    identifier: str | None


@dataclass(frozen=True)
class _SlashSpec:
    command: str  # canonical 명령(별칭 입력도 이 이름으로 정규화)
    mode: AgentMode
    tool: str
    needs_identifier: bool = False
    params: dict = field(default_factory=dict)


# read-only 4 명령(별칭 포함). 모든 tool 은 planner._READ_TOOL_ALLOWLIST 소속이어야 한다.
_SPECS: tuple[_SlashSpec, ...] = (
    _SlashSpec("/pipelines", AgentMode.SIMPLE_QUERY, "list_project_pipelines"),
    _SlashSpec("/connectors", AgentMode.SIMPLE_QUERY, "get_connector_status", needs_identifier=True),
    _SlashSpec("/consumer-groups", AgentMode.SIMPLE_QUERY, "get_consumer_lag", needs_identifier=True),
    _SlashSpec("/events", AgentMode.SIMPLE_QUERY, "search_logs"),
)
# 별칭 → canonical 명령 매핑. canonical 자기 자신도 키로 포함된다.
_ALIASES: dict[str, str] = {
    "/cg": "/consumer-groups",
    "/lag": "/consumer-groups",
    "/logs": "/events",
}
SLASH_REGISTRY: dict[str, _SlashSpec] = {spec.command: spec for spec in _SPECS}
for _alias, _canonical in _ALIASES.items():
    SLASH_REGISTRY[_alias] = SLASH_REGISTRY[_canonical]


def resolve_slash(user_message: str) -> SlashResolution | None:
    """슬래시 명령을 결정적 resolution 으로 변환. 미해당 시 None(→ LLM 경로)."""
    text = user_message.strip()
    if not text.startswith("/"):
        return None

    parts = text.split(maxsplit=1)
    command = parts[0].lower()
    identifier = parts[1].strip() if len(parts) > 1 and parts[1].strip() else None

    spec = SLASH_REGISTRY.get(command)
    if spec is None:
        # 미등록 명령·오타 → None 으로 흘려 LLM(→ keyword) 경로에 맡긴다(안전).
        return None

    # 선택 tool 은 planner allowlist 안에 있어야 한다 — 단일 진실 원천 재사용.
    from app.agents.planner import _READ_TOOL_ALLOWLIST

    if spec.tool not in _READ_TOOL_ALLOWLIST:
        return None

    return SlashResolution(
        command=spec.command,
        mode=spec.mode,
        tool=spec.tool,
        params=dict(spec.params),
        needs_identifier=spec.needs_identifier,
        identifier=identifier,
    )
