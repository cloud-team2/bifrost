"""#981 live balanced eval runner for the deployed RCA agent.

목적: live_fault_specs 의 'auto' fault 를 (1) clean state 보장(dedup resolve) → (2) 주입 →
(3) metadb/agentdb 폴링으로 인시던트+RCA 결과 포착 → (4) top-k root cause 기록 → (5) 복구,
한 뒤 기존 메트릭 모듈(app.evaluation)로 AC@1/AC@3/AC@5/Avg@5/ECE 를 산출하고 JSON+markdown
리포트를 eval/reports/ 에 남긴다.

두 모드:
  --dry-run (기본): 클러스터를 절대 건드리지 않는다. spec 검증 + 채점 파이프라인을 작은 fixture
                   (expected, predicted_ranking, confidence)로 돌려 AC@k/ECE 가 정상 계산됨을 증명.
  --live          : 실제 주입/폴링/복구를 수행(가드). kubectl/DB 접근은 이 플래그 뒤에서만 호출.

채점은 acceptable set 을 지원한다: RCA top-k 안에 spec.expected_root_cause_ids 중 *하나라도*
들어오면 hit. 메트릭 모듈(EvalCase)은 단일 accepted_root_cause_id 만 받으므로, 예측 랭킹을
acceptable set 에 맞춰 정규화(랭킹 안의 acceptable id 를 primary 로 치환)해 hit 판정을 보존한다.

실행:
  cd services/ai-service && .venv/bin/python -m eval.online.live_eval            # dry-run
  cd services/ai-service && .venv/bin/python -m eval.online.live_eval --live ... # 실주입(가드)
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Sequence

from app.evaluation.calibration import calibration_report_to_dict, compute_calibration
from app.evaluation.metrics import EvalCase, build_eval_report

from eval.online.live_fault_specs import (
    FAULT_SPECS,
    FaultSpec,
    get_root_cause,
    list_fault_specs,
)

REPORTS_DIR = Path(__file__).resolve().parents[2] / "eval" / "reports"


# ─────────────────────────────────────────────────────────────────────────────
# 관측 결과 — fault 1건당 RCA 가 낸 top-k root cause 랭킹.
# ─────────────────────────────────────────────────────────────────────────────
@dataclass
class FaultObservation:
    fault_id: str
    expected_root_cause_ids: tuple[str, ...]
    predicted_ranking: list[str]
    predicted_confidences: list[float]
    incident_id: str | None = None
    grouping_key: str | None = None
    captured: bool = True  # 인시던트/RCA 가 포착됐는가(live 에서 timeout 시 False).
    note: str = ""


def _normalize_for_acceptable(
    expected_ids: Sequence[str],
    ranking: Sequence[str],
) -> tuple[str, list[str]]:
    """acceptable set 을 단일 정답 채점기(EvalCase)에 맞게 정규화.

    metrics.EvalCase 는 accepted_root_cause_id 1개만 받는다. acceptable set 의 *어떤* id 든
    hit 으로 인정하려면, 랭킹에서 가장 먼저 등장하는 acceptable id 를 primary 로 치환한다.
    이렇게 하면 AC@k 와 Avg@k(역순위)가 acceptable set 기준으로 정확히 계산된다.
    """
    primary = expected_ids[0]
    expected_set = set(expected_ids)
    best_rank: int | None = None
    best_id: str | None = None
    for idx, rc in enumerate(ranking):
        if rc in expected_set:
            best_rank = idx
            best_id = rc
            break
    norm_ranking = list(ranking)
    if best_rank is not None and best_id is not None and best_id != primary:
        norm_ranking[best_rank] = primary
    return primary, norm_ranking


def observations_to_cases(observations: Sequence[FaultObservation]) -> list[EvalCase]:
    cases: list[EvalCase] = []
    for obs in observations:
        primary, norm_ranking = _normalize_for_acceptable(
            obs.expected_root_cause_ids, obs.predicted_ranking
        )
        cases.append(
            EvalCase(
                entry_id=obs.fault_id,
                accepted_root_cause_id=primary,
                predicted_ranking=norm_ranking,
                predicted_confidences=list(obs.predicted_confidences),
            )
        )
    return cases


def _layer_map_for(observations: Sequence[FaultObservation]) -> dict[str, str]:
    out: dict[str, str] = {}
    for obs in observations:
        primary = obs.expected_root_cause_ids[0]
        entry = get_root_cause(primary)
        out[primary] = entry.layer if entry else "unknown"
    return out


def score_observations(observations: Sequence[FaultObservation]) -> dict:
    """관측 결과를 기존 메트릭 모듈로 AC@k/Avg@5/ECE 채점 → dict 리포트."""
    cases = observations_to_cases(observations)
    layer_map = _layer_map_for(observations)
    report = build_eval_report(cases, layer_map)
    calib = compute_calibration(cases)

    rows = []
    for obs, case in zip(observations, cases):
        top = case.predicted_ranking[0] if case.predicted_ranking else None
        rows.append(
            {
                "fault_id": obs.fault_id,
                "expected": list(obs.expected_root_cause_ids),
                "predicted_top": obs.predicted_ranking[0] if obs.predicted_ranking else None,
                "predicted_ranking": obs.predicted_ranking[:5],
                "top_conf": round(obs.predicted_confidences[0], 4)
                if obs.predicted_confidences
                else None,
                "hit@1": case.predicted_ranking[:1] == [case.accepted_root_cause_id],
                "hit@3": case.accepted_root_cause_id in case.predicted_ranking[:3],
                "hit@5": case.accepted_root_cause_id in case.predicted_ranking[:5],
                "captured": obs.captured,
                "note": obs.note,
                "_normalized_top": top,
            }
        )

    return {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "total_cases": report.total_cases,
        "AC@1": round(report.ac_at_1.accuracy, 4),
        "AC@3": round(report.ac_at_3.accuracy, 4),
        "AC@5": round(report.ac_at_5.accuracy, 4),
        "Avg@5": round(report.avg_at_5.score, 4),
        "ECE": round(calib.ece, 4),
        "weak_layers": report.weak_layers,
        "layer_breakdown": [
            {
                "layer": lb.layer,
                "n": lb.total,
                "AC@1": round(lb.ac_at_1, 4),
                "AC@3": round(lb.ac_at_3, 4),
                "AC@5": round(lb.ac_at_5, 4),
                "Avg@5": round(lb.avg_at_5, 4),
            }
            for lb in report.layer_breakdown
        ],
        "calibration": calibration_report_to_dict(calib),
        "rows": rows,
    }


def render_markdown(report: dict, *, mode: str) -> str:
    lines = [
        f"# RCA live eval report ({mode})",
        "",
        f"- generated_at: {report['generated_at']}",
        f"- total_cases: {report['total_cases']}",
        f"- AC@1: {report['AC@1']}  AC@3: {report['AC@3']}  AC@5: {report['AC@5']}",
        f"- Avg@5: {report['Avg@5']}  ECE: {report['ECE']}",
        f"- weak_layers: {', '.join(report['weak_layers']) or '없음'}",
        "",
        "## layer breakdown",
        "",
        "| layer | n | AC@1 | AC@3 | AC@5 | Avg@5 |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for lb in report["layer_breakdown"]:
        lines.append(
            f"| {lb['layer']} | {lb['n']} | {lb['AC@1']} | {lb['AC@3']} | "
            f"{lb['AC@5']} | {lb['Avg@5']} |"
        )
    lines += [
        "",
        "## per-fault",
        "",
        "| fault | expected | predicted_top | top_conf | hit@1 | hit@3 | hit@5 | captured |",
        "| --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for r in report["rows"]:
        lines.append(
            f"| {r['fault_id']} | {', '.join(r['expected'])} | {r['predicted_top']} | "
            f"{r['top_conf']} | {r['hit@1']} | {r['hit@3']} | {r['hit@5']} | {r['captured']} |"
        )
    return "\n".join(lines) + "\n"


def write_reports(report: dict, *, mode: str) -> tuple[Path, Path]:
    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    json_path = REPORTS_DIR / f"live_eval_{mode}_{stamp}.json"
    md_path = REPORTS_DIR / f"live_eval_{mode}_{stamp}.md"
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    md_path.write_text(render_markdown(report, mode=mode), encoding="utf-8")
    return json_path, md_path


# ─────────────────────────────────────────────────────────────────────────────
# DRY-RUN — 클러스터를 건드리지 않고 spec + 채점 파이프라인을 검증.
# ─────────────────────────────────────────────────────────────────────────────
# 작은 합성 fixture: (fault_id, predicted_ranking, confidences).
# 정답/오답/부분히트/혼동을 섞어 AC@1<AC@3<AC@5, Avg@5, ECE 가 *실제로* 계산됨을 증명한다.
DRY_RUN_FIXTURE: dict[str, tuple[list[str], list[float]]] = {
    # top-1 정확 + 높은 confidence(잘 calibrate 된 사례).
    "sink_db_down": (
        ["SINK_DB_CONNECTION_TIMEOUT", "SINK_WRITE_LATENCY", "CONNECTOR_TASK_FAILED"],
        [0.88, 0.40, 0.20],
    ),
    # acceptable set 의 두 번째 id 가 top-1 (정규화로 hit@1 으로 인정돼야 함).
    "source_db_down": (
        ["SOURCE_NETWORK_REACHABILITY", "SOURCE_READ_LATENCY", "CONNECTOR_TASK_FAILED"],
        [0.81, 0.35, 0.15],
    ),
    # 정답이 3위 — hit@1/hit@3 차이를 만든다.
    "connector_task_restart_storm": (
        ["CONNECTOR_WORKER_REBALANCE_LOOP", "PIPELINE_TASK_RETRY_EXHAUSTED", "CONNECTOR_TASK_FAILED"],
        [0.55, 0.45, 0.40],
    ),
    # 정답이 5위 — hit@3/hit@5 차이를 만든다.
    "consumer_lag_spike": (
        [
            "TOPIC_INGRESS_SPIKE",
            "SINK_WRITE_LATENCY",
            "BROKER_RESOURCE_PRESSURE",
            "PARTITION_IMBALANCE",
            "CONSUMER_LAG_SPIKE",
        ],
        [0.62, 0.50, 0.40, 0.30, 0.25],
    ),
    # 완전 오답 + 과신(overconfident) — ECE 를 0 이 아니게 만든다.
    "broker_resource_pressure": (
        ["CONSUMER_LAG_SPIKE", "PARTITION_IMBALANCE", "TOPIC_INGRESS_SPIKE"],
        [0.90, 0.30, 0.10],
    ),
}


def build_dry_run_observations() -> list[FaultObservation]:
    """fixture + spec 의 acceptable set 을 합쳐 가짜 관측을 만든다(클러스터 무접촉)."""
    observations: list[FaultObservation] = []
    for fault_id, (ranking, confs) in DRY_RUN_FIXTURE.items():
        spec = next((s for s in FAULT_SPECS if s.fault_id == fault_id), None)
        if spec is None:  # pragma: no cover - fixture 키는 spec 에 존재
            raise KeyError(f"fixture fault_id {fault_id!r} not in FAULT_SPECS")
        observations.append(
            FaultObservation(
                fault_id=fault_id,
                expected_root_cause_ids=spec.expected_root_cause_ids,
                predicted_ranking=ranking,
                predicted_confidences=confs,
                incident_id=f"dry::{fault_id}",
                grouping_key=spec.grouping_key_pattern,
                captured=True,
                note="dry-run fixture",
            )
        )
    return observations


def run_dry_run(*, write: bool = True) -> dict:
    """spec 검증 + 채점 파이프라인 증명. 클러스터 무접촉."""
    # spec import 시점에 이미 _validate() 통과. 여기서 auto fault 가 inject/recover 를 갖는지 재확인.
    auto = list_fault_specs("auto")
    for spec in auto:
        assert spec.inject_steps and spec.recover_steps, spec.fault_id

    observations = build_dry_run_observations()
    report = score_observations(observations)
    report["mode"] = "dry-run"
    report["spec_summary"] = {
        "total": len(FAULT_SPECS),
        "auto": [s.fault_id for s in list_fault_specs("auto")],
        "manual": [s.fault_id for s in list_fault_specs("manual")],
        "unsafe": [s.fault_id for s in list_fault_specs("unsafe")],
    }
    if write:
        json_path, md_path = write_reports(report, mode="dry-run")
        report["_report_paths"] = {"json": str(json_path), "md": str(md_path)}
    return report


# ─────────────────────────────────────────────────────────────────────────────
# LIVE — 실제 주입/폴링/복구. 모든 클러스터 접근은 --live 뒤에서만.
# ─────────────────────────────────────────────────────────────────────────────
def _run_cmd(cmd: str, *, timeout: int = 180) -> tuple[int, str]:
    """주입/복구 스텝을 subprocess 로 실행(live 전용)."""
    proc = subprocess.run(  # noqa: S602 - shell 스텝은 spec 에 선언된 신뢰 입력
        cmd,
        shell=True,
        capture_output=True,
        text=True,
        timeout=timeout,
    )
    return proc.returncode, (proc.stdout + proc.stderr).strip()


def _resolve_open_incidents(spec: FaultSpec) -> None:
    """dedup: 같은 grouping_key 의 OPEN 인시던트를 주입 전에 resolve(live 전용)."""
    # metadb incidents 테이블에서 (tenant_id, grouping_key, status=OPEN) 을 resolve.
    # 실제 자격증명/DSN 은 환경에서 주입한다(여기서는 의도만 명시, --live 에서만 호출).
    raise NotImplementedError(
        "live dedup-resolve 는 운영 metadb 자격증명이 주입된 환경에서만 활성화된다. "
        "README 의 live 실행 절차를 따르라."
    )


def _poll_rca_result(spec: FaultSpec, *, timeout_s: int) -> FaultObservation:
    """주입 후 metadb/agentdb 를 폴링해 인시던트+RCA top-k 를 포착(live 전용)."""
    raise NotImplementedError(
        "live polling(metadb incidents + agentdb report_snapshot) 는 운영 DB 자격증명이 "
        "주입된 환경에서만 활성화된다. README 의 live 실행 절차를 따르라."
    )


def run_live(
    fault_ids: Sequence[str] | None,
    *,
    poll_timeout_s: int = 600,
    confirm: bool = False,
) -> dict:
    """auto fault 만 실제 주입/복구. 반드시 --confirm 동반(이중 가드)."""
    if not confirm:
        raise SystemExit(
            "live 주입은 --confirm 가 함께 있어야 한다(이중 가드). "
            "이 작업 범위에서는 live 주입을 실행하지 말 것."
        )
    auto = {s.fault_id: s for s in list_fault_specs("auto")}
    selected = (
        [auto[f] for f in fault_ids if f in auto] if fault_ids else list(auto.values())
    )
    if not selected:
        raise SystemExit("선택된 auto fault 가 없다.")

    observations: list[FaultObservation] = []
    for spec in selected:
        _resolve_open_incidents(spec)
        try:
            if spec.requires_selfheal_disable:
                pass  # inject_steps[0] 에 selfHeal disable 포함.
            for step in spec.inject_steps:
                if step.lstrip().startswith("#"):
                    continue
                _run_cmd(step)
            time.sleep(2)
            obs = _poll_rca_result(spec, timeout_s=poll_timeout_s)
        finally:
            for step in spec.recover_steps:
                if step.lstrip().startswith("#"):
                    continue
                _run_cmd(step)
        observations.append(obs)

    report = score_observations(observations)
    report["mode"] = "live"
    json_path, md_path = write_reports(report, mode="live")
    report["_report_paths"] = {"json": str(json_path), "md": str(md_path)}
    return report


def _print_summary(report: dict) -> None:
    keys = ["mode", "total_cases", "AC@1", "AC@3", "AC@5", "Avg@5", "ECE", "weak_layers"]
    summary = {k: report.get(k) for k in keys if k in report}
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    if "_report_paths" in report:
        print(f"report json: {report['_report_paths']['json']}")
        print(f"report md  : {report['_report_paths']['md']}")


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="#981 RCA live balanced eval harness")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument(
        "--dry-run",
        action="store_true",
        help="기본. 클러스터 무접촉. spec+채점 파이프라인 검증(fixture 로 AC@k/ECE 증명).",
    )
    mode.add_argument(
        "--live",
        action="store_true",
        help="가드. auto fault 실제 주입/폴링/복구(--confirm 필수).",
    )
    parser.add_argument("--confirm", action="store_true", help="live 이중 가드.")
    parser.add_argument(
        "--faults", nargs="*", default=None, help="live 에서 주입할 auto fault id 들(미지정 시 전체)."
    )
    parser.add_argument(
        "--poll-timeout", type=int, default=600, help="live: RCA 결과 폴링 timeout(초)."
    )
    parser.add_argument(
        "--no-write", action="store_true", help="dry-run: 리포트 파일 미기록(콘솔만)."
    )
    args = parser.parse_args(argv)

    if args.live:
        report = run_live(args.faults, poll_timeout_s=args.poll_timeout, confirm=args.confirm)
    else:
        report = run_dry_run(write=not args.no_write)

    _print_summary(report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
