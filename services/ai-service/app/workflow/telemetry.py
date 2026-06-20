"""#883 run telemetry — stage별 timing/cost/agent/tool 호출 수 집계.

Supervisor advance() 루프 안에서 stage 시작/종료 시 record()를 호출하면
run 전체의 telemetry 요약이 만들어진다. run 종료 시 summary()를 꺼내
state_patch + run_repo에 저장한다.
"""
from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Any


@dataclass
class StageTelemetry:
    stage: str
    agent: str
    started_at: float = 0.0
    ended_at: float = 0.0
    tool_calls: int = 0
    llm_calls: int = 0
    estimated_tokens: int = 0
    error: str | None = None

    @property
    def latency_ms(self) -> float:
        if self.ended_at <= 0 or self.started_at <= 0:
            return 0.0
        return (self.ended_at - self.started_at) * 1000


class RunTelemetryCollector:
    """run 한 건의 telemetry를 수집하는 컬렉터."""

    def __init__(self, run_id: str) -> None:
        self.run_id = run_id
        self._stages: list[StageTelemetry] = []
        self._current: StageTelemetry | None = None
        self._run_started_at: float = time.monotonic()
        self._called_agents: set[str] = set()
        self._called_tools: list[str] = []
        self._total_tool_calls: int = 0
        self._total_llm_calls: int = 0
        self._total_tokens: int = 0
        self._handoff_reasons: list[dict[str, str]] = []

    def start_stage(self, stage: str, agent: str) -> None:
        self._finish_current()
        self._current = StageTelemetry(
            stage=stage, agent=agent, started_at=time.monotonic()
        )
        self._called_agents.add(agent)

    def record_tool_call(self, tool_name: str) -> None:
        self._called_tools.append(tool_name)
        self._total_tool_calls += 1
        if self._current:
            self._current.tool_calls += 1

    def record_llm_call(self, estimated_tokens: int = 0) -> None:
        self._total_llm_calls += 1
        self._total_tokens += estimated_tokens
        if self._current:
            self._current.llm_calls += 1
            self._current.estimated_tokens += estimated_tokens

    def record_error(self, error: str) -> None:
        if self._current:
            self._current.error = error

    def record_handoff(self, from_stage: str, to_stage: str, reason: str) -> None:
        self._handoff_reasons.append({
            "from": from_stage,
            "to": to_stage,
            "reason": reason,
        })

    def finish(self) -> None:
        self._finish_current()

    def _finish_current(self) -> None:
        if self._current is not None:
            self._current.ended_at = time.monotonic()
            self._stages.append(self._current)
            self._current = None

    def summary(self) -> dict[str, Any]:
        self._finish_current()
        total_latency_ms = (time.monotonic() - self._run_started_at) * 1000

        stages = []
        for s in self._stages:
            stages.append({
                "stage": s.stage,
                "agent": s.agent,
                "latency_ms": round(s.latency_ms, 1),
                "tool_calls": s.tool_calls,
                "llm_calls": s.llm_calls,
                "estimated_tokens": s.estimated_tokens,
                "error": s.error,
            })

        tool_counts: dict[str, int] = {}
        for t in self._called_tools:
            tool_counts[t] = tool_counts.get(t, 0) + 1

        return {
            "run_id": self.run_id,
            "total_latency_ms": round(total_latency_ms, 1),
            "total_stages": len(self._stages),
            "called_agents": sorted(self._called_agents),
            "called_tools": tool_counts,
            "total_tool_calls": self._total_tool_calls,
            "total_llm_calls": self._total_llm_calls,
            "total_estimated_tokens": self._total_tokens,
            "stages": stages,
            "handoff_reasons": self._handoff_reasons,
        }
