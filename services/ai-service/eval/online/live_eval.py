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
import os
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
    CDC_PRODUCTS_SINK,
    CDC_PRODUCTS_SOURCE,
    EDA_CUSTOMERS_SOURCE,
    FAULT_SPECS,
    FaultSpec,
    get_root_cause,
    list_fault_specs,
)
from eval.online.safe_live_fault_specs import (
    SAFE_FAULT_SPECS,
    list_safe_fault_specs,
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
#
# 설계: 이 하니스는 *운영자 머신*에서 돈다. 클러스터 접근은 직접 DB 자격증명이 아니라
# `kubectl exec` subprocess(psql/curl)로만 한다. DB 자격증명은 각 deployment 의 env
# ($POSTGRES_USER/$POSTGRES_DB)에 이미 있으므로 컨테이너 안에서 그대로 참조한다.
#
# clean-state 는 incident resolve(게이트된 prod write)에 의존하지 않는다. 대신 주입 직전
# UTC baseline timestamp 를 찍고, baseline 이후 *새로* 생성된 incident/RCA 만 본다. 같은
# grouping 의 OPEN incident 가 이미 있으면 dedup 으로 새 incident 가 안 뜰 수 있어 WARNING
# 을 남기고 timestamp 기준으로 진행한다.
# ─────────────────────────────────────────────────────────────────────────────
TENANT_ID = "8898903c-d5db-4a8c-9ff3-104632f4f70f"

# 트래픽 유발 — sink 가 죽은 동안 source 에 row 를 넣어 CDC 가 sink 로 흘려보내다 실패하게 한다.
# tenant-postgres deployment 안에서 컨테이너 env($POSTGRES_USER/$POSTGRES_DB)로 psql 실행.
TRAFFIC_SQL = (
    "insert into public.products (name,category,price,stock,created_at) "
    "select 'live-eval-'||g,'rca-test',(random()*100)::numeric(10,2),"
    "(random()*1000)::int,now() from generate_series(1,300) g;"
)
# Connect REST 재시작 — connect pod 이름을 grep 으로 동적 해석(deploy 이름이 환경마다 다름).
_CONNECT_POD = (
    "CPOD=$(kubectl -n platform-kafka get pods -o name | grep -i connect | head -1 "
    "| sed 's|pod/||')"
)


def _connect_restart_cmd(connector: str) -> str:
    return (
        f"{_CONNECT_POD}; "
        f'kubectl -n platform-kafka exec "$CPOD" -- '
        f'curl -s -X POST "http://localhost:8083/connectors/{connector}/restart?includeTasks=true"'
    )


def _run_cmd(cmd: str, *, timeout: int = 180, check: bool = False) -> tuple[int, str]:
    """주입/복구/폴링 스텝을 subprocess 로 실행(live 전용).

    spec 에 선언됐거나 이 모듈 상수로 만든 신뢰 입력만 shell 로 넘긴다(사용자 자유 입력 아님).
    check=True 면 비0 종료에서 RuntimeError 를 던진다(주입 실패를 조용히 넘기지 않기 위함).
    """
    proc = subprocess.run(  # noqa: S602 - 신뢰된 spec/상수 커맨드만 실행
        cmd,
        shell=True,
        capture_output=True,
        text=True,
        timeout=timeout,
    )
    out = (proc.stdout + proc.stderr).strip()
    if check and proc.returncode != 0:
        raise RuntimeError(f"command failed (rc={proc.returncode}): {cmd}\n{out}")
    return proc.returncode, out


def _kubectl_psql(namespace: str, deploy: str, sql: str, *, timeout: int = 60) -> str:
    """`kubectl exec` 로 psql 을 돌려 결과(stdout)를 돌려준다(live 전용).

    -tA -F'|' 로 헤더/정렬 없는 pipe 구분 결과를 받는다. 자격증명은 컨테이너 env 참조.
    sh -c 로 감싸 컨테이너 *내부*에서 $POSTGRES_USER/$POSTGRES_DB 를 확장하고(로컬 셸이
    빈 값으로 확장하던 버그 방지), SQL 은 stdin(psql -f -)으로 넘겨 따옴표 충돌을 없앤다.
    """
    cmd = (
        f"kubectl -n {namespace} exec -i deploy/{deploy} -- "
        "sh -c 'psql -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\" -tA -F\"|\" -f -'"
    )
    proc = subprocess.run(  # noqa: S602 - 신뢰된 spec/상수 커맨드만 실행
        cmd, shell=True, capture_output=True, text=True, timeout=timeout, input=sql,
    )
    if proc.returncode != 0:
        out = (proc.stdout + proc.stderr).strip()
        raise RuntimeError(f"psql failed on {namespace}/{deploy}: {out}")
    return proc.stdout.strip()


def _capture_baseline(spec: FaultSpec) -> str:
    """주입 직전 UTC baseline timestamp 를 metadb 시계로 찍고, dedup 위험을 경고(live 전용).

    DB 시계(now())를 기준으로 잡아 운영자 머신과의 시계 오차를 없앤다. 같은 grouping 의 OPEN
    incident 가 이미 있으면 새 incident 가 dedup 될 수 있어 WARNING 후 timestamp 로 진행한다.
    반환: 이후 'created_at > <baseline>' 필터에 쓸 ISO-ish UTC 문자열.
    """
    baseline = _kubectl_psql(
        "metadb", "metadb", "select now() at time zone 'utc';"
    ).strip()
    if not baseline:
        # DB 시계를 못 읽으면 운영자 머신 UTC 로 폴백(시계 오차 위험은 로그로 남긴다).
        baseline = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")
        print(f"  [WARN] metadb 시계 조회 실패 → 로컬 UTC baseline 사용: {baseline}")

    # dedup 위험 점검: 같은 grouping_key 의 OPEN incident 가 이미 있는가.
    if spec.grouping_key_pattern:
        gk = spec.grouping_key_pattern.replace("'", "''")
        existing = _kubectl_psql(
            "metadb",
            "metadb",
            f"select count(*) from incidents where tenant_id='{TENANT_ID}' "
            f"and grouping_key='{gk}' and status='OPEN';",
        ).strip()
        if existing and existing.isdigit() and int(existing) > 0:
            print(
                f"  [WARN] grouping_key={spec.grouping_key_pattern!r} 의 OPEN incident 가 "
                f"{existing}건 존재 → dedup 으로 새 incident 가 안 뜰 수 있음. "
                f"baseline({baseline}) 이후 신규만 timestamp 로 잡아 진행한다."
            )
    print(f"  baseline(UTC) = {baseline}")
    return baseline


def _poll_incident(baseline: str, *, timeout: int = 30) -> tuple[str | None, str]:
    """baseline 이후 생성된 신규 incident 1건을 metadb 에서 조회(live 전용).

    반환: (incident_id|None, raw_line). 없으면 (None, "").
    """
    bl = baseline.replace("'", "''")
    out = _kubectl_psql(
        "metadb",
        "metadb",
        f"select id,severity,status,title from incidents "
        f"where tenant_id='{TENANT_ID}' and created_at > '{bl}' "
        f"order by created_at desc limit 1;",
        timeout=timeout,
    ).strip()
    if not out:
        return None, ""
    first = out.splitlines()[0]
    incident_id = first.split("|", 1)[0].strip() if first else None
    return (incident_id or None), first


def _poll_rca_ranking(baseline: str, *, timeout: int = 30) -> tuple[list[str], list[float]]:
    """baseline 이후 생성된 최신 RCA report_snapshot 1건의 top-k 랭킹을 agentdb 에서 조회.

    report_snapshot 은 *run 당 1행*이고, 전체 랭킹은 body->'root_cause_candidates'(rank 순
    배열)에 들어있다. 그래서 최신 1행을 잡아 body 의 후보 배열을 (root_cause_id, confidence)
    로 풀어 ranking 을 만든다. body 가 비어있으면 top-1(root_cause_id,confidence) 컬럼으로 폴백.

    반환: (ranking[str], confidences[float]) — 비어있으면 ([], []).
    """
    bl = baseline.replace("'", "''")
    # body 의 후보 배열을 행으로 펼쳐 rank 순서대로 (id|conf) 를 받는다.
    sql = (
        "with latest as ("
        "  select id, root_cause_id, confidence, body, created_at "
        "  from report_snapshot "
        f"  where created_at > '{bl}' "
        "  order by created_at desc limit 1"
        ") "
        "select c->>'root_cause_id', c->>'confidence' "
        "from latest, "
        "  lateral jsonb_array_elements(coalesce(body->'root_cause_candidates','[]'::jsonb)) "
        "    with ordinality as t(c, ord) "
        "order by ord;"
    )
    out = _kubectl_psql("agentdb", "agentdb", sql, timeout=timeout).strip()
    ranking: list[str] = []
    confidences: list[float] = []
    if out:
        for line in out.splitlines():
            parts = line.split("|")
            rc = (parts[0] if parts else "").strip()
            if not rc:
                continue
            ranking.append(rc)
            conf_str = parts[1].strip() if len(parts) > 1 else ""
            try:
                confidences.append(float(conf_str) if conf_str else 0.0)
            except ValueError:
                confidences.append(0.0)
        return ranking, confidences

    # 폴백: body 에 후보 배열이 없으면 top-1 컬럼만이라도 잡는다.
    top = _kubectl_psql(
        "agentdb",
        "agentdb",
        f"select root_cause_id,confidence from report_snapshot "
        f"where created_at > '{bl}' order by created_at desc limit 1;",
        timeout=timeout,
    ).strip()
    if top:
        parts = top.splitlines()[0].split("|")
        rc = (parts[0] if parts else "").strip()
        if rc:
            ranking = [rc]
            conf_str = parts[1].strip() if len(parts) > 1 else ""
            try:
                confidences = [float(conf_str) if conf_str else 0.0]
            except ValueError:
                confidences = [0.0]
    return ranking, confidences


def _poll_rca_result(
    spec: FaultSpec, baseline: str, *, timeout_s: int, interval_s: int = 15
) -> FaultObservation:
    """주입 후 metadb(incident)+agentdb(RCA) 를 폴링해 top-k 를 포착(live 전용).

    timeout_s 까지 interval_s 간격으로: 신규 incident 가 뜨고 그 뒤 RCA 랭킹이 채워지면 포착.
    timeout 이면 captured=False 로 빈 랭킹 관측을 돌려준다(채점은 miss 로 잡힌다).
    """
    deadline = time.monotonic() + timeout_s
    incident_id: str | None = None
    incident_line = ""
    while time.monotonic() < deadline:
        if incident_id is None:
            incident_id, incident_line = _poll_incident(baseline)
            if incident_id:
                print(f"  incident 포착: {incident_line}")
        if incident_id is not None:
            ranking, confs = _poll_rca_ranking(baseline)
            if ranking:
                print(f"  RCA top-k 포착: {ranking[:5]}")
                return FaultObservation(
                    fault_id=spec.fault_id,
                    expected_root_cause_ids=spec.expected_root_cause_ids,
                    predicted_ranking=ranking,
                    predicted_confidences=confs,
                    incident_id=incident_id,
                    grouping_key=spec.grouping_key_pattern,
                    captured=True,
                    note=f"live: incident={incident_id}",
                )
        time.sleep(interval_s)

    print(f"  [WARN] {spec.fault_id}: timeout({timeout_s}s) — incident/RCA 미포착.")
    return FaultObservation(
        fault_id=spec.fault_id,
        expected_root_cause_ids=spec.expected_root_cause_ids,
        predicted_ranking=[],
        predicted_confidences=[],
        incident_id=incident_id,
        grouping_key=spec.grouping_key_pattern,
        captured=False,
        note=f"live: timeout {timeout_s}s",
    )


def _run_inject_steps(spec: FaultSpec) -> None:
    """spec inject 스텝 실행 + sink_db_down 은 추가로 트래픽 유발(live 전용)."""
    for step in spec.inject_steps:
        if step.lstrip().startswith("#"):
            continue
        _run_cmd(step, check=True)
    # sink 가 죽은 동안 source 에 row 를 넣어 CDC 가 sink write 를 시도하다 실패하게 한다.
    if spec.fault_id == "sink_db_down":
        time.sleep(3)  # mariadb pod 가 내려갈 시간을 잠깐 준다.
        # _kubectl_psql(sh -c + stdin)로 실행 — 컨테이너 내부 env 확장 + 따옴표 충돌 회피.
        try:
            _kubectl_psql("tenantdb", "tenant-postgres", TRAFFIC_SQL)
        except Exception as exc:  # 트래픽 유발 실패는 무시하고 진행.
            print(f"  [WARN] 트래픽 유발 실패(무시하고 진행): {exc}")


def _run_recover_steps(spec: FaultSpec) -> None:
    """spec recover 스텝 실행 + Connect REST 로 관련 connector 재시작(live 전용).

    *항상* 호출돼야 한다(run_live 의 finally). 한 스텝이 실패해도 나머지는 계속 시도한다.
    """
    errors: list[str] = []
    for step in spec.recover_steps:
        if step.lstrip().startswith("#"):
            continue
        try:
            _run_cmd(step)
        except Exception as exc:  # 복구는 best-effort — 한 스텝 실패가 나머지를 막지 않게.
            errors.append(f"{step!r}: {exc}")

    # Connect REST 재시작(동적 pod 해석). spec.recover_steps 의 deploy/kafka-connect exec
    # 가 환경에 따라 실패할 수 있어, 검증된 pod-grep 방식으로 한 번 더 확실히 재시작한다.
    restart_targets = {
        "sink_db_down": [CDC_PRODUCTS_SINK],
        "source_db_down": [CDC_PRODUCTS_SOURCE, EDA_CUSTOMERS_SOURCE],
    }
    for connector in restart_targets.get(spec.fault_id, []):
        try:
            _run_cmd(_connect_restart_cmd(connector))
        except Exception as exc:
            errors.append(f"connect-restart {connector}: {exc}")

    if errors:
        print(f"  [WARN] recover 중 일부 스텝 실패(best-effort): {'; '.join(errors)}")


def run_live(
    fault_ids: Sequence[str] | None,
    *,
    poll_timeout_s: int = 300,
    poll_interval_s: int = 15,
    confirm: bool = False,
) -> dict:
    """auto fault 만 실제 주입/폴링/복구. 반드시 --confirm 동반(이중 가드).

    한 fault 사이클: baseline 캡처 → 주입 → 폴링(timeout) → **항상 복구(finally)** → 다음.
    복구는 try/finally 로 보장돼 폴링 중 예외/크래시가 나도 클러스터가 깨진 채 남지 않는다.
    """
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
        print(f"\n=== live fault: {spec.fault_id} ({spec.layer}) ===")
        baseline = _capture_baseline(spec)
        obs: FaultObservation
        try:
            print("  inject...")
            _run_inject_steps(spec)
            time.sleep(2)
            print(f"  poll(timeout={poll_timeout_s}s, interval={poll_interval_s}s)...")
            obs = _poll_rca_result(
                spec, baseline, timeout_s=poll_timeout_s, interval_s=poll_interval_s
            )
        finally:
            # 복구는 무조건 — 예외가 나도 클러스터를 원복한다(replicas/selfHeal/connector).
            print("  recover...")
            _run_recover_steps(spec)
        observations.append(obs)

    report = score_observations(observations)
    report["mode"] = "live"
    json_path, md_path = write_reports(report, mode="live")
    report["_report_paths"] = {"json": str(json_path), "md": str(md_path)}
    return report


# ─────────────────────────────────────────────────────────────────────────────
# SAFE-LIVE — operations-backend safe-injection API only.
# No kubectl, DB exec, DB scale, direct SQL mutation, or existing-resource patch.
# ─────────────────────────────────────────────────────────────────────────────
def _safe_internal_headers() -> dict[str, str]:
    from app.core.config import settings
    from app.tools.spring_client import INTERNAL_OPS_TOKEN_HEADER

    headers = {
        "Content-Type": "application/json",
        "X-Agent-Request-Id": "safeinject-eval",
    }
    if settings.internal_ops_token:
        headers[INTERNAL_OPS_TOKEN_HEADER] = settings.internal_ops_token
    return headers


def _safe_login(client, *, base_url: str, email: str, password: str) -> tuple[str, str]:
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
        raise RuntimeError(f"login response missing accessToken/workspaceId: {body}")
    return str(token), str(project_id)


def _safe_inject_connector(
    client,
    *,
    ops_base_url: str,
    project_id: str,
    run_id: str,
    fault_id: str,
) -> dict:
    resp = client.post(
        f"{ops_base_url}/internal/ops/projects/{project_id}/safe-injection/connectors",
        headers=_safe_internal_headers(),
        json={"runId": run_id, "fault": fault_id},
    )
    resp.raise_for_status()
    env = resp.json()
    if not env.get("ok"):
        raise RuntimeError(f"safe injection failed: {env}")
    return env["result"]


def _safe_cleanup(client, *, ops_base_url: str, project_id: str, run_id: str) -> dict:
    resp = client.delete(
        f"{ops_base_url}/internal/ops/projects/{project_id}/safe-injection/runs/{run_id}",
        headers=_safe_internal_headers(),
    )
    resp.raise_for_status()
    env = resp.json()
    if not env.get("ok"):
        raise RuntimeError(f"safe cleanup failed: {env}")
    return env["result"]


def _run_completed(events: Sequence[dict]) -> bool:
    return any(e.get("type") == "run_completed" for e in events)


def _extract_candidates(payload: dict) -> tuple[list[str], list[float]]:
    candidates = payload.get("root_cause_candidates")
    if not candidates and isinstance(payload.get("body"), dict):
        candidates = payload["body"].get("root_cause_candidates")
    if not candidates and isinstance(payload.get("data"), dict):
        return _extract_candidates(payload["data"])
    if not candidates and payload.get("root_cause_id"):
        candidates = [{
            "root_cause_id": payload.get("root_cause_id"),
            "confidence": payload.get("confidence") or 0.0,
        }]
    ranking: list[str] = []
    confidences: list[float] = []
    for item in candidates or []:
        if not isinstance(item, dict):
            continue
        root_cause_id = item.get("root_cause_id")
        if not root_cause_id:
            continue
        ranking.append(str(root_cause_id))
        try:
            confidences.append(float(item.get("confidence") or 0.0))
        except (TypeError, ValueError):
            confidences.append(0.0)
    return ranking, confidences


def _poll_agent_ranking(
    client,
    *,
    agent_base_url: str,
    token: str,
    agent_project_id: str,
    connector_name: str,
    timeout_s: int,
    interval_s: float,
) -> tuple[str | None, list[str], list[float], str]:
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
    message = (
        f"커넥터 {connector_name} 상태를 조회하고 근본 원인을 분석해줘. "
        "필요하면 상태, 로그, 최근 변경, lag 같은 운영 증거를 수집해줘."
    )
    resp = client.post(
        f"{agent_base_url}/api/v1/agent/runs",
        headers=headers,
        json={
            "project_id": agent_project_id,
            "message": message,
            "mode": "incident_analysis",
            "stream": False,
        },
    )
    resp.raise_for_status()
    env = resp.json()
    run_id = ((env.get("data") or {}).get("run_id"))
    if not run_id:
        raise RuntimeError(f"agent run_id missing: {env}")

    deadline = time.monotonic() + timeout_s
    events: list[dict] = []
    while time.monotonic() < deadline:
        ev = client.get(
            f"{agent_base_url}/api/v1/agent/runs/{run_id}/events/history",
            headers=headers,
        )
        ev.raise_for_status()
        events = ((ev.json().get("data") or {}).get("events")) or []
        if _run_completed(events):
            break
        time.sleep(interval_s)

    # Report snapshot is the preferred actual RCA artifact. Reproducibility is a state-patch fallback.
    for path in (
        f"/api/v1/agent/runs/{run_id}/report",
        f"/api/v1/agent/runs/{run_id}/reproducibility",
    ):
        got = client.get(f"{agent_base_url}{path}", headers=headers)
        if got.status_code >= 400:
            continue
        body = got.json()
        data = body.get("data") or {}
        ranking, confidences = _extract_candidates(data)
        if ranking:
            return run_id, ranking, confidences, "safe-live agent rca"

    return run_id, [], [], "safe-live agent rca not captured"


def run_safe_live(
    fault_ids: Sequence[str] | None,
    *,
    confirm: bool = False,
    ops_base_url: str | None = None,
    safe_project_id: str | None = None,
    agent_base_url: str | None = None,
    agent_project_id: str | None = None,
    email: str | None = None,
    password: str | None = None,
    run_id: str | None = None,
    poll_timeout_s: int = 180,
    poll_interval_s: float = 2.0,
) -> dict:
    """Safe live injection using only new labelled test connector resources.

    This mode deliberately avoids the destructive live_fault_specs inject/recover steps.
    It requires --confirm because it creates and deletes labelled test resources.
    """
    if not confirm:
        raise SystemExit("safe-live requires --confirm because it creates labelled test resources.")
    import httpx
    from app.core.config import settings

    ops_base_url = (ops_base_url or os.getenv("SAFE_INJECT_OPS_BASE_URL")
                    or settings.spring_ops_base_url).rstrip("/")
    agent_base_url = (agent_base_url or os.getenv("BIFROST_BASE_URL")
                      or "http://localhost:8000").rstrip("/")
    safe_project_id = safe_project_id or os.getenv("SAFE_INJECT_PROJECT_ID")
    if not safe_project_id:
        raise SystemExit("safe-live requires --safe-project-id or SAFE_INJECT_PROJECT_ID")
    email = email or os.getenv("BIFROST_EMAIL")
    password = password or os.getenv("BIFROST_PASSWORD")
    run_id = run_id or "safeinject-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")

    spec_index = {s.fault_id: s for s in list_safe_fault_specs()}
    selected = [spec_index[f] for f in fault_ids if f in spec_index] if fault_ids else list(SAFE_FAULT_SPECS)
    if not selected:
        raise SystemExit("selected safe-live faults are empty")

    observations: list[FaultObservation] = []
    cleanup_results: list[dict] = []
    with httpx.Client(timeout=30.0) as client:
        token: str | None = None
        if email and password:
            token, resolved_project = _safe_login(
                client, base_url=agent_base_url, email=email, password=password
            )
            agent_project_id = agent_project_id or os.getenv("BIFROST_PROJECT_ID") or resolved_project
        elif not agent_project_id:
            agent_project_id = os.getenv("BIFROST_PROJECT_ID")

        try:
            for spec in selected:
                print(f"\n=== safe-live fault: {spec.fault_id} ===")
                injection = _safe_inject_connector(
                    client,
                    ops_base_url=ops_base_url,
                    project_id=safe_project_id,
                    run_id=run_id,
                    fault_id=spec.fault_id,
                )
                connector_name = str(injection["connector_name"])
                print(f"  injected connector={connector_name}")

                if token and agent_project_id:
                    agent_run, ranking, confidences, note = _poll_agent_ranking(
                        client,
                        agent_base_url=agent_base_url,
                        token=token,
                        agent_project_id=agent_project_id,
                        connector_name=connector_name,
                        timeout_s=poll_timeout_s,
                        interval_s=poll_interval_s,
                    )
                    print(f"  rca run={agent_run} ranking={ranking[:5]}")
                    observations.append(FaultObservation(
                        fault_id=spec.fault_id,
                        expected_root_cause_ids=spec.expected_root_cause_ids,
                        predicted_ranking=ranking,
                        predicted_confidences=confidences,
                        incident_id=agent_run,
                        grouping_key=connector_name,
                        captured=bool(ranking),
                        note=note,
                    ))
                else:
                    observations.append(FaultObservation(
                        fault_id=spec.fault_id,
                        expected_root_cause_ids=spec.expected_root_cause_ids,
                        predicted_ranking=[],
                        predicted_confidences=[],
                        grouping_key=connector_name,
                        captured=False,
                        note="safe-live injection completed; agent credentials not supplied",
                    ))
        finally:
            cleanup = _safe_cleanup(
                client,
                ops_base_url=ops_base_url,
                project_id=safe_project_id,
                run_id=run_id,
            )
            cleanup_results.append(cleanup)
            if cleanup.get("residual_count") != 0:
                raise RuntimeError(f"safe-live cleanup residuals remain: {cleanup}")

    report = score_observations(observations)
    report["mode"] = "safe-live"
    report["safe_run_id"] = run_id
    report["safe_project_id"] = safe_project_id
    report["cleanup"] = cleanup_results
    report["spec_summary"] = {
        "safe": [s.fault_id for s in SAFE_FAULT_SPECS],
        "forbidden_surfaces": ["kubectl exec", "scale/patch/delete existing resources", "direct DB SQL"],
    }
    json_path, md_path = write_reports(report, mode="safe-live")
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
    mode.add_argument(
        "--safe-live",
        action="store_true",
        help="가드. 신규 라벨 테스트 커넥터만 생성/정리하는 안전 주입 모드(--confirm 필수).",
    )
    parser.add_argument("--confirm", action="store_true", help="live 이중 가드.")
    parser.add_argument(
        "--faults", nargs="*", default=None, help="live/safe-live 에서 주입할 fault id 들(미지정 시 전체)."
    )
    parser.add_argument(
        "--poll-timeout", type=int, default=300, help="live: RCA 결과 폴링 timeout(초, 기본 300=5분)."
    )
    parser.add_argument(
        "--poll-interval", type=int, default=15, help="live: 폴링 간격(초, 기본 15)."
    )
    parser.add_argument("--safe-project-id", default=None, help="safe-live: Spring internal-ops project id/slug.")
    parser.add_argument("--agent-project-id", default=None, help="safe-live: FastAPI agent project UUID.")
    parser.add_argument("--ops-base-url", default=None, help="safe-live: operations-backend base URL.")
    parser.add_argument("--agent-base-url", default=None, help="safe-live: ai-service/API base URL.")
    parser.add_argument("--email", default=None, help="safe-live: agent login email.")
    parser.add_argument("--password", default=None, help="safe-live: agent login password.")
    parser.add_argument("--safe-run-id", default=None, help="safe-live: deterministic cleanup run id.")
    parser.add_argument(
        "--no-write", action="store_true", help="dry-run: 리포트 파일 미기록(콘솔만)."
    )
    args = parser.parse_args(argv)

    if args.safe_live:
        report = run_safe_live(
            args.faults,
            confirm=args.confirm,
            ops_base_url=args.ops_base_url,
            safe_project_id=args.safe_project_id,
            agent_base_url=args.agent_base_url,
            agent_project_id=args.agent_project_id,
            email=args.email,
            password=args.password,
            run_id=args.safe_run_id,
            poll_timeout_s=args.poll_timeout,
            poll_interval_s=float(args.poll_interval),
        )
    elif args.live:
        report = run_live(
            args.faults,
            poll_timeout_s=args.poll_timeout,
            poll_interval_s=args.poll_interval,
            confirm=args.confirm,
        )
    else:
        report = run_dry_run(write=not args.no_write)

    _print_summary(report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
