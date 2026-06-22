"""#981 NL → tool-routing live eval — 자연어 질문이 올바른 tool 로, 낭비 없이 라우팅되는지 평가.

목적: 사용자가 제품 채팅에 **자연어**(슬래시 커맨드 X)로 물었을 때, agent 가 (1) *맞는* tool 을
부르고, (2) 그 tool 에 *효율적으로*(앞선 낭비 호출 없이) 도달하는지 측정한다.

평가 축:
  - routing@1   : expected tool 이 *첫* tool 호출이었는가(정확 + 효율 동시 만족).
  - routing@hit : expected tool 이 trace 어디에서든 호출됐는가(정확성).
  - wasted_steps: 첫 정답 tool 앞에 끼어든 tool 호출 수(낮을수록 효율적). 미호출 시 전체 호출 수.
  - latency_ms  : run 종료까지 걸린 시간(라이브에서만, 가용 시).

agent 가 tool 을 *어떻게* 고르고 부르는지(탐색 결과):
  - tool 선택: app/agents/planner.py `run_planner` — LLM 이 read-only allowlist(19개) 안에서
    tool 을 고르고, LLM 미가용 시 keyword fallback(`_keyword_select_tools`).
  - tool 실행: app/agents/retrieval.py `run_retrieval` — plan step 을 순차/병렬 실행하며 각 호출마다
    SSE 이벤트 TOOL_CALL_STARTED(payload {"tool": <name>, "step_id": ...}) 를 낸다.
  - 관측: 그 이벤트들이 event repo 에 적재돼 `GET /api/v1/agent/runs/{run_id}/events/history`
    (plain JSON) 로 시간순 조회된다. 이게 우리가 읽는 tool-call trace 다.

라이브 구동 경로(실제 배포 agent 를 운전):
  1) POST /api/v1/auth/login {email,password} → accessToken(+workspaceId=project_id).
  2) POST /api/v1/agent/runs {project_id, message, mode:"simple_query", stream:false}
     (Authorization: Bearer <token>) → run_id.
  3) run 종료까지 GET /api/v1/agent/runs/{run_id}/events/history 폴링 → type=="run_completed"
     가 보일 때까지 대기 → tool_call_started 이벤트의 payload["tool"] 를 순서대로 모아 trace 구성.

두 모드:
  --dry-run (기본): 네트워크 무접촉. fixture trace 로 채점 로직(routing@1/wasted_steps)을 증명.
  --live          : 배포 agent 를 실제로 운전(--confirm 필수, 이중 가드).

실행:
  cd services/ai-service && .venv/bin/python -m eval.online.nl_tool_routing            # dry-run
  cd services/ai-service && \
    BIFROST_BASE_URL=https://bifrost.skala-ai.com \
    BIFROST_EMAIL=ta@bifrost.io BIFROST_PASSWORD=ta123456 \
    .venv/bin/python -m eval.online.nl_tool_routing --live --confirm                   # 실라이브
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Sequence

REPORTS_DIR = Path(__file__).resolve().parents[2] / "eval" / "reports"

# 배포 agent 좌표(라이브에서만 사용). env 로 override 가능.
DEFAULT_BASE_URL = "https://bifrost.skala-ai.com"
DEFAULT_EMAIL = "ta@bifrost.io"
DEFAULT_PASSWORD = "ta123456"


# ─────────────────────────────────────────────────────────────────────────────
# 라벨링된 NL 질의 세트 — tool 카탈로그를 가로지른다(한/영 혼합).
# expected_tool = 1순위 정답. acceptable = 같은 의도를 만족하는 대체 tool(있으면 hit 인정).
# tool 이름은 app/tools/registry.py default_tool_definitions() 의 식별자와 일치한다.
# ─────────────────────────────────────────────────────────────────────────────
@dataclass(frozen=True)
class RoutingCase:
    case_id: str
    query: str
    expected_tool: str
    acceptable_tools: tuple[str, ...] = ()  # expected 외에 정답으로 인정할 대체 tool.
    note: str = ""

    @property
    def accepted_set(self) -> frozenset[str]:
        return frozenset({self.expected_tool, *self.acceptable_tools})


ROUTING_CASES: tuple[RoutingCase, ...] = (
    RoutingCase(
        case_id="logs_ko",
        query="파이프라인 에러 로그 좀 검색해줘",
        expected_tool="search_logs",
        note="로그 검색 의도.",
    ),
    RoutingCase(
        case_id="logs_en",
        query="search the recent error logs for the pipeline",
        expected_tool="search_logs",
    ),
    RoutingCase(
        case_id="metrics_ko",
        query="consumer lag 메트릭 수치 보여줘",
        expected_tool="get_metrics",
        acceptable_tools=("get_consumer_lag",),
        note="메트릭 조회 vs lag 직접 조회 — 둘 다 의도 충족.",
    ),
    RoutingCase(
        case_id="metrics_en",
        query="show me the pipeline lag metric values for the last 30 minutes",
        expected_tool="get_metrics",
    ),
    RoutingCase(
        case_id="consumer_lag_ko",
        query="컨슈머 그룹 lag 지연 얼마나 밀렸어?",
        expected_tool="get_consumer_lag",
        acceptable_tools=("get_consumer_groups", "get_kafka_lag", "get_metrics"),
    ),
    RoutingCase(
        case_id="consumer_groups_ko",
        query="컨슈머 그룹들 현황 목록 보여줘",
        expected_tool="get_consumer_groups",
        acceptable_tools=("get_consumer_lag",),
    ),
    RoutingCase(
        case_id="deployments_ko",
        query="최근에 배포나 설정 변경된 거 있어?",
        expected_tool="get_deployments",
        note="변경 이력(change) 의도.",
    ),
    RoutingCase(
        case_id="connector_status_ko",
        query="products-sink 커넥터 상태 어때?",
        expected_tool="get_connector_status",
        acceptable_tools=("list_connectors",),
        note="식별자 있으면 status, 없으면 list 로 fallback 될 수 있음.",
    ),
    RoutingCase(
        case_id="list_connectors_ko",
        query="커넥터 목록 다 보여줘",
        expected_tool="list_connectors",
    ),
    RoutingCase(
        case_id="cluster_info_en",
        query="show the kafka broker and topic partition cluster info",
        expected_tool="get_cluster_info",
    ),
    RoutingCase(
        case_id="pipelines_ko",
        query="이 프로젝트 파이프라인 목록 뭐 있어?",
        expected_tool="list_project_pipelines",
        acceptable_tools=("list_pipelines",),
    ),
    RoutingCase(
        case_id="pipeline_status_ko",
        query="파이프라인 상태랑 lag 현황 요약해줘",
        expected_tool="list_pipelines",
        acceptable_tools=("list_project_pipelines", "get_metrics", "get_consumer_lag"),
    ),
    RoutingCase(
        case_id="topology_en",
        query="show the source to sink topology of the products pipeline",
        expected_tool="get_pipeline_topology",
        acceptable_tools=("list_project_pipelines",),
        note="식별자 미해석 시 discovery(list)로 fallback 될 수 있음.",
    ),
    RoutingCase(
        case_id="alerts_ko",
        query="발생한 알람 있는지 확인해줘",
        expected_tool="get_alerts",
    ),
    RoutingCase(
        case_id="event_log_ko",
        query="최근 이벤트랑 인시던트 경고 요약해줘",
        expected_tool="analyze_event_log",
        acceptable_tools=("get_alerts",),
    ),
    RoutingCase(
        case_id="trace_en",
        query="get the distributed trace spans for the products-sink connector",
        expected_tool="get_traces",
        acceptable_tools=("list_connectors", "get_connector_task_trace"),
    ),
    RoutingCase(
        case_id="task_trace_ko",
        query="products-sink 커넥터 task 예외 스택트레이스 보여줘",
        expected_tool="get_connector_task_trace",
        acceptable_tools=("list_connectors", "get_traces"),
    ),
    RoutingCase(
        case_id="datasources_en",
        query="list the source and sink databases and their status",
        expected_tool="list_datasources",
        acceptable_tools=("list_connectors",),
    ),
)


# ─────────────────────────────────────────────────────────────────────────────
# 한 질의의 관측 — agent 가 부른 tool 들의 순서(trace) + 메타.
# ─────────────────────────────────────────────────────────────────────────────
@dataclass
class RoutingObservation:
    case_id: str
    query: str
    expected_tool: str
    accepted_tools: tuple[str, ...]
    tool_trace: list[str]  # 시간순 tool 호출 이름. (planner→retrieval 실행 순서)
    latency_ms: float | None = None
    run_id: str | None = None
    error: str | None = None  # 라이브 호출 실패 시.


# ─────────────────────────────────────────────────────────────────────────────
# 채점 — 순수 함수(테스트 더블 무관). routing@1 / routing@hit / wasted_steps.
# ─────────────────────────────────────────────────────────────────────────────
def _first_accepted_index(trace: Sequence[str], accepted: frozenset[str]) -> int | None:
    """trace 에서 accepted tool 이 처음 나오는 index. 없으면 None."""
    for idx, tool in enumerate(trace):
        if tool in accepted:
            return idx
    return None


def score_one(obs: RoutingObservation) -> dict:
    """한 관측을 채점한다.

    - routing@1   : 첫 tool 호출이 accepted set 에 들었는가.
    - routing@hit : accepted tool 이 trace 어디든 있었는가.
    - wasted_steps: 첫 정답 tool 앞에 끼어든 호출 수(= 첫 정답 index). 미호출이면 전체 호출 수.
    """
    accepted = frozenset({obs.expected_tool, *obs.accepted_tools})
    trace = list(obs.tool_trace)
    first_idx = _first_accepted_index(trace, accepted)
    hit = first_idx is not None
    routing_at_1 = bool(trace) and first_idx == 0
    wasted = first_idx if first_idx is not None else len(trace)
    return {
        "case_id": obs.case_id,
        "query": obs.query,
        "expected_tool": obs.expected_tool,
        "accepted_tools": list(obs.accepted_tools),
        "tool_trace": trace,
        "first_tool": trace[0] if trace else None,
        "routing@1": routing_at_1,
        "routing@hit": hit,
        "wasted_steps": wasted,
        "n_tool_calls": len(trace),
        "latency_ms": round(obs.latency_ms, 1) if obs.latency_ms is not None else None,
        "run_id": obs.run_id,
        "error": obs.error,
    }


def score_observations(observations: Sequence[RoutingObservation], *, mode: str) -> dict:
    rows = [score_one(obs) for obs in observations]
    n = len(rows)
    scored = [r for r in rows if r["error"] is None]  # 에러 row 는 비율 분모에서 제외.
    m = len(scored) or 1
    routing_at_1 = sum(1 for r in scored if r["routing@1"]) / m
    routing_hit = sum(1 for r in scored if r["routing@hit"]) / m
    mean_wasted = sum(r["wasted_steps"] for r in scored) / m
    latencies = [r["latency_ms"] for r in scored if r["latency_ms"] is not None]
    mean_latency = round(sum(latencies) / len(latencies), 1) if latencies else None
    return {
        "mode": mode,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "total_cases": n,
        "scored_cases": len(scored),
        "errored_cases": n - len(scored),
        "routing@1": round(routing_at_1, 4),
        "routing@hit": round(routing_hit, 4),
        "mean_wasted_steps": round(mean_wasted, 4),
        "mean_latency_ms": mean_latency,
        "rows": rows,
    }


# ─────────────────────────────────────────────────────────────────────────────
# 리포트 렌더링/쓰기.
# ─────────────────────────────────────────────────────────────────────────────
def render_markdown(report: dict) -> str:
    lines = [
        f"# NL → tool-routing eval report ({report['mode']})",
        "",
        f"- generated_at: {report['generated_at']}",
        f"- total_cases: {report['total_cases']} "
        f"(scored {report['scored_cases']}, errored {report['errored_cases']})",
        f"- routing@1: {report['routing@1']}  routing@hit: {report['routing@hit']}",
        f"- mean_wasted_steps: {report['mean_wasted_steps']}  "
        f"mean_latency_ms: {report['mean_latency_ms']}",
        "",
        "## per-query",
        "",
        "| case | expected | first_tool | trace | r@1 | hit | wasted | n | latency_ms |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for r in report["rows"]:
        trace = " → ".join(r["tool_trace"]) if r["tool_trace"] else "—"
        err = f" (ERR: {r['error']})" if r["error"] else ""
        lines.append(
            f"| {r['case_id']} | {r['expected_tool']} | {r['first_tool']} | {trace}{err} | "
            f"{r['routing@1']} | {r['routing@hit']} | {r['wasted_steps']} | "
            f"{r['n_tool_calls']} | {r['latency_ms']} |"
        )
    return "\n".join(lines) + "\n"


def write_reports(report: dict) -> tuple[Path, Path]:
    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    json_path = REPORTS_DIR / f"nl_tool_routing_{report['mode']}_{stamp}.json"
    md_path = REPORTS_DIR / f"nl_tool_routing_{report['mode']}_{stamp}.md"
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    md_path.write_text(render_markdown(report), encoding="utf-8")
    return json_path, md_path


# ─────────────────────────────────────────────────────────────────────────────
# DRY-RUN — 네트워크 무접촉. fixture trace 로 채점 로직 증명.
# ─────────────────────────────────────────────────────────────────────────────
# (case_id → tool_trace) 합성 fixture. 정답-첫호출/정답-2번째/대체-tool/미스를 섞어
# routing@1 < routing@hit, mean_wasted_steps > 0 가 *실제로* 계산됨을 보인다.
DRY_RUN_FIXTURE: dict[str, list[str]] = {
    "logs_ko": ["search_logs"],                                  # r@1 hit, wasted 0
    "logs_en": ["search_logs"],                                  # r@1 hit, wasted 0
    "metrics_ko": ["get_consumer_lag"],                          # acceptable 첫호출 → r@1 hit
    "metrics_en": ["get_metrics"],                               # r@1 hit
    "consumer_lag_ko": ["list_connectors", "get_consumer_lag"],  # 1칸 낭비 후 정답 → hit, not@1
    "consumer_groups_ko": ["get_consumer_groups"],               # r@1 hit
    "deployments_ko": ["get_deployments"],                       # r@1 hit
    "connector_status_ko": ["list_connectors"],                  # acceptable 첫호출 → r@1 hit
    "list_connectors_ko": ["list_connectors"],                   # r@1 hit
    "cluster_info_en": ["get_cluster_info"],                     # r@1 hit
    "pipelines_ko": ["list_project_pipelines"],                  # r@1 hit
    "pipeline_status_ko": ["list_pipelines"],                    # r@1 hit
    "topology_en": ["list_project_pipelines", "get_pipeline_topology"],  # discovery→정답, hit not@1
    "alerts_ko": ["search_logs", "get_alerts"],                  # 1칸 낭비 → hit, not@1
    "event_log_ko": ["analyze_event_log"],                       # r@1 hit
    "trace_en": ["search_logs"],                                 # 완전 미스 → no hit
    "task_trace_ko": ["list_connectors", "get_connector_task_trace"],  # discovery→정답
    "datasources_en": ["list_datasources"],                      # r@1 hit
}


def build_dry_run_observations() -> list[RoutingObservation]:
    case_index = {c.case_id: c for c in ROUTING_CASES}
    observations: list[RoutingObservation] = []
    for case_id, trace in DRY_RUN_FIXTURE.items():
        case = case_index.get(case_id)
        if case is None:  # pragma: no cover - fixture 키는 case 에 존재
            raise KeyError(f"fixture case_id {case_id!r} not in ROUTING_CASES")
        observations.append(
            RoutingObservation(
                case_id=case.case_id,
                query=case.query,
                expected_tool=case.expected_tool,
                accepted_tools=case.acceptable_tools,
                tool_trace=list(trace),
                latency_ms=None,
                run_id=f"dry::{case_id}",
            )
        )
    return observations


def run_dry_run(*, write: bool = True) -> dict:
    observations = build_dry_run_observations()
    report = score_observations(observations, mode="dry-run")
    if write:
        json_path, md_path = write_reports(report)
        report["_report_paths"] = {"json": str(json_path), "md": str(md_path)}
    return report


# ─────────────────────────────────────────────────────────────────────────────
# LIVE — 배포 agent 를 실제로 운전. 모든 네트워크 접근은 --live(+--confirm) 뒤에서만.
# ─────────────────────────────────────────────────────────────────────────────
def extract_tool_trace(events: Sequence[dict]) -> list[str]:
    """events/history JSON 의 이벤트 목록에서 tool_call_started 의 tool 을 시간순으로 뽑는다.

    각 이벤트는 {type, timestamp, payload:{tool,...}} 형태(StreamingEvent). timestamp 로 정렬해
    실제 호출 순서를 복원한다(retrieval 이 fan-out 병렬 실행해도 emit 순서를 따른다).
    """
    started = [
        e
        for e in events
        if e.get("type") == "tool_call_started" and (e.get("payload") or {}).get("tool")
    ]
    started.sort(key=lambda e: str(e.get("timestamp") or ""))
    return [str(e["payload"]["tool"]) for e in started]


def _run_completed(events: Sequence[dict]) -> bool:
    return any(e.get("type") == "run_completed" for e in events)


def _drive_one_live(
    client,
    case: RoutingCase,
    *,
    base_url: str,
    token: str,
    project_id: str,
    run_timeout_s: int,
    poll_interval_s: float,
) -> RoutingObservation:
    """한 질의를 배포 agent 로 보내 trace 를 포착(live 전용).

    POST /runs → run_id, run_completed 가 보일 때까지 events/history 폴링 → tool trace 추출.
    """
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
    t0 = time.monotonic()
    try:
        resp = client.post(
            f"{base_url}/api/v1/agent/runs",
            headers=headers,
            json={
                "project_id": project_id,
                "message": case.query,
                "mode": "simple_query",
                "stream": False,
            },
        )
        resp.raise_for_status()
        env = resp.json()
        data = env.get("data") or {}
        run_id = data.get("run_id")
        if not run_id:
            raise RuntimeError(f"run_id 없음: {env}")
    except Exception as exc:  # 생성 실패 → 에러 관측(분모에서 제외).
        return RoutingObservation(
            case_id=case.case_id, query=case.query, expected_tool=case.expected_tool,
            accepted_tools=case.acceptable_tools, tool_trace=[], error=f"create_run: {exc}",
        )

    # run 종료까지 events/history 폴링.
    deadline = time.monotonic() + run_timeout_s
    events: list[dict] = []
    error: str | None = None
    while time.monotonic() < deadline:
        try:
            ev = client.get(
                f"{base_url}/api/v1/agent/runs/{run_id}/events/history", headers=headers
            )
            ev.raise_for_status()
            events = ((ev.json().get("data") or {}).get("events")) or []
        except Exception as exc:
            error = f"events: {exc}"
            break
        if _run_completed(events):
            break
        time.sleep(poll_interval_s)

    latency_ms = (time.monotonic() - t0) * 1000.0
    trace = extract_tool_trace(events)
    if not error and not _run_completed(events):
        error = f"timeout {run_timeout_s}s (run 미완료)"
    return RoutingObservation(
        case_id=case.case_id, query=case.query, expected_tool=case.expected_tool,
        accepted_tools=case.acceptable_tools, tool_trace=trace,
        latency_ms=latency_ms, run_id=run_id, error=error,
    )


def _login(client, *, base_url: str, email: str, password: str) -> tuple[str, str]:
    """POST /auth/login → (accessToken, project_id=workspaceId)."""
    resp = client.post(
        f"{base_url}/api/v1/auth/login",
        json={"email": email, "password": password},
        headers={"Content-Type": "application/json"},
    )
    resp.raise_for_status()
    body = resp.json()
    token = body.get("accessToken")
    project_id = body.get("workspaceId")
    if not token or not project_id:
        raise RuntimeError(f"login 응답에 accessToken/workspaceId 없음: {body}")
    return token, str(project_id)


def run_live(
    case_ids: Sequence[str] | None,
    *,
    confirm: bool = False,
    base_url: str | None = None,
    email: str | None = None,
    password: str | None = None,
    project_id: str | None = None,
    run_timeout_s: int = 120,
    poll_interval_s: float = 2.0,
) -> dict:
    """배포 agent 를 실제로 운전해 NL→tool 라우팅을 채점. 반드시 --confirm(이중 가드).

    env: BIFROST_BASE_URL / BIFROST_EMAIL / BIFROST_PASSWORD / BIFROST_PROJECT_ID 로 override.
    project_id 가 없으면 login 의 workspaceId 를 쓴다.
    """
    if not confirm:
        raise SystemExit(
            "live 구동은 --confirm 가 함께 있어야 한다(이중 가드). "
            "이 작업 범위에서는 live 구동을 실행하지 말 것."
        )
    import httpx  # 라이브에서만 필요 — dry-run 은 무의존.

    base_url = (base_url or os.getenv("BIFROST_BASE_URL") or DEFAULT_BASE_URL).rstrip("/")
    email = email or os.getenv("BIFROST_EMAIL") or DEFAULT_EMAIL
    password = password or os.getenv("BIFROST_PASSWORD") or DEFAULT_PASSWORD
    project_id = project_id or os.getenv("BIFROST_PROJECT_ID")

    case_index = {c.case_id: c for c in ROUTING_CASES}
    selected = (
        [case_index[c] for c in case_ids if c in case_index]
        if case_ids
        else list(ROUTING_CASES)
    )
    if not selected:
        raise SystemExit("선택된 routing case 가 없다.")

    observations: list[RoutingObservation] = []
    with httpx.Client(timeout=30.0) as client:
        token, resolved_project = _login(
            client, base_url=base_url, email=email, password=password
        )
        project_id = project_id or resolved_project
        for case in selected:
            print(f"  [{case.case_id}] {case.query!r}")
            obs = _drive_one_live(
                client, case, base_url=base_url, token=token, project_id=project_id,
                run_timeout_s=run_timeout_s, poll_interval_s=poll_interval_s,
            )
            mark = obs.error or f"trace={obs.tool_trace}"
            print(f"    → {mark}")
            observations.append(obs)

    report = score_observations(observations, mode="live")
    json_path, md_path = write_reports(report)
    report["_report_paths"] = {"json": str(json_path), "md": str(md_path)}
    return report


def _print_summary(report: dict) -> None:
    keys = [
        "mode", "total_cases", "scored_cases", "errored_cases",
        "routing@1", "routing@hit", "mean_wasted_steps", "mean_latency_ms",
    ]
    summary = {k: report.get(k) for k in keys if k in report}
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    if "_report_paths" in report:
        print(f"report json: {report['_report_paths']['json']}")
        print(f"report md  : {report['_report_paths']['md']}")


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="#981 NL→tool-routing live eval")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument(
        "--dry-run", action="store_true",
        help="기본. 네트워크 무접촉. fixture trace 로 채점(routing@1/wasted_steps) 증명.",
    )
    mode.add_argument(
        "--live", action="store_true",
        help="가드. 배포 agent 를 실제로 운전(--confirm 필수).",
    )
    parser.add_argument("--confirm", action="store_true", help="live 이중 가드.")
    parser.add_argument(
        "--cases", nargs="*", default=None, help="live 에서 돌릴 case id 들(미지정 시 전체)."
    )
    parser.add_argument(
        "--run-timeout", type=int, default=120, help="live: run 1건 종료 폴링 timeout(초)."
    )
    parser.add_argument(
        "--no-write", action="store_true", help="dry-run: 리포트 파일 미기록(콘솔만)."
    )
    args = parser.parse_args(argv)

    if args.live:
        report = run_live(args.cases, confirm=args.confirm, run_timeout_s=args.run_timeout)
    else:
        report = run_dry_run(write=not args.no_write)

    _print_summary(report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
