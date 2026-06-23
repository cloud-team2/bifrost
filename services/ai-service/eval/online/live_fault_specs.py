"""#981 live fault catalog — balanced fault-injection spec for the deployed RCA agent.

각 fault는 (1) 어떤 장애를 주입하는지, (2) 어떻게 복구하는지, (3) RCA가 맞혔다고
인정할 root_cause_id 집합(acceptable set)과 혼동 가능 집합(confusion set), (4) 인시던트
dedup용 grouping_key 패턴, (5) 안전 등급을 선언한다.

안전 등급:
  - 'auto'   : release artifact 에서는 현재 사용하지 않는다. 자동 주입은 safe_live_fault_specs.py 로만 둔다.
  - 'manual' : 의도된 주입 방법은 명확하나 운영자 승인/수동 단계가 필요(자동 주입 금지).
  - 'unsafe' : prod에서 주입이 위험하거나 사실상 재현 불가(문서화만, 절대 자동 주입 금지).

scoring 관점:
  - expected_root_cause_ids[0] 가 "정답"(AC@1 판정 기준)이며, 나머지는 acceptable set.
  - confusion_root_cause_ids 는 RCA가 헷갈릴 법한 후보로, 리포트에서 오답 분석에 사용.

모든 root_cause_id 는 app.catalogs.root_causes 카탈로그에 대해 검증된다(import 시 assert).
스텝(inject/recover)은 문서화 전용 데이터다. legacy --live 실행기는 release artifact 에서
hard-disabled 되어 있으며, 비파괴 자동 주입은 safe_live_fault_specs.py 경로만 사용한다.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Literal

from app.catalogs.root_causes import ROOT_CAUSE_INDEX, get_root_cause

Safety = Literal["auto", "manual", "unsafe"]

# 테스트 테넌트/프로젝트/파이프라인 좌표 (주입 대상 식별용 상수).
TENANT_ID = "8898903c-d5db-4a8c-9ff3-104632f4f70f"
PROJECT_NAME = "E2E RCA Test 0621"
CDC_PRODUCTS_SOURCE = "5d4b0826-ba4b-4e3a-9f21-0d2cca4efc84-source"
CDC_PRODUCTS_SINK = "5d4b0826-ba4b-4e3a-9f21-0d2cca4efc84-sink"
EDA_CUSTOMERS_SOURCE = "d9d9497b-4d8e-4baa-937b-6007aaa6b6dd-source"

@dataclass(frozen=True)
class FaultSpec:
    """단일 장애 시나리오의 주입/복구/채점 선언."""

    fault_id: str
    layer: str
    description: str
    safety: Safety
    # acceptable set: [0] = 정답(AC@1 기준). 나머지는 정답으로 인정.
    expected_root_cause_ids: tuple[str, ...]
    # confusion set: RCA 가 헷갈릴 법한 후보(채점엔 안 쓰고 분석에 사용).
    confusion_root_cause_ids: tuple[str, ...] = ()
    # 인시던트 dedup 용 grouping_key 패턴(주입 전 같은 key 의 OPEN 인시던트를 resolve).
    grouping_key_pattern: str = ""
    # 주입/복구 스텝 — release artifact 에서는 자동 실행되지 않는다.
    inject_steps: tuple[str, ...] = ()
    recover_steps: tuple[str, ...] = ()
    # injection 이 selfHeal 영향을 받는가(끄고/켜야 하는가).
    requires_selfheal_disable: bool = False
    notes: str = ""

    @property
    def primary_root_cause_id(self) -> str:
        return self.expected_root_cause_ids[0]


# ─────────────────────────────────────────────────────────────────────────────
# Fault catalog — 8 layers. legacy destructive auto injection has been removed.
# ─────────────────────────────────────────────────────────────────────────────
FAULT_SPECS: tuple[FaultSpec, ...] = (
    # ── sink layer ────────────────────────────────────────────────────────────
    FaultSpec(
        fault_id="sink_db_down",
        layer="sink",
        description="sink DB down 시나리오. legacy destructive live injection path is removed.",
        safety="unsafe",
        expected_root_cause_ids=("SINK_DB_CONNECTION_TIMEOUT",),
        confusion_root_cause_ids=("SINK_WRITE_LATENCY", "CONNECTOR_TASK_FAILED"),
        grouping_key_pattern=f"{CDC_PRODUCTS_SINK}:SINK_CONNECTION_TIMEOUT",
        notes="기존 deployment scale 기반 자동 주입은 release artifact 에서 제거됐다. "
        "비파괴 검증은 safe_live_fault_specs.py 를 사용한다.",
    ),
    # ── source layer ──────────────────────────────────────────────────────────
    FaultSpec(
        fault_id="source_db_down",
        layer="source",
        description="source DB down 시나리오. legacy destructive live injection path is removed.",
        safety="unsafe",
        # pod-down 은 host unreachable 로도 보이므로 둘 다 정답으로 인정.
        expected_root_cause_ids=(
            "SOURCE_DB_CONNECTION_TIMEOUT",
            "SOURCE_NETWORK_REACHABILITY",
        ),
        confusion_root_cause_ids=("SOURCE_READ_LATENCY", "CONNECTOR_TASK_FAILED"),
        grouping_key_pattern=f"{CDC_PRODUCTS_SOURCE}:SOURCE_CONNECTION_TIMEOUT",
        notes="기존 deployment scale 기반 자동 주입은 release artifact 에서 제거됐다. "
        "timeout 과 reachability 가 acceptable set 으로 함께 인정.",
    ),
    # ── pipeline/connector layer ──────────────────────────────────────────────
    FaultSpec(
        fault_id="connector_task_restart_storm",
        layer="pipeline",
        description="sink connector 를 반복 restart 하여 task 불안정/FAILED 표출 유도.",
        safety="manual",
        expected_root_cause_ids=("CONNECTOR_TASK_FAILED",),
        confusion_root_cause_ids=(
            "CONNECTOR_WORKER_REBALANCE_LOOP",
            "PIPELINE_TASK_RETRY_EXHAUSTED",
        ),
        grouping_key_pattern=f"{CDC_PRODUCTS_SINK}:CONNECTOR_TASK_FAILED",
        inject_steps=("# 반복 restart 로 task 안정성 저하(수동 절차만 문서화)",),
        recover_steps=("# connector 상태 확인 후 필요 시 수동 복구",),
        notes="restart 자체는 가역적이나 반복 restart 가 sink 쓰기 일관성을 흔들 수 있어 manual. "
        "실제 FAILED 를 보장하려면 sink_db_down 과 결합하는 편이 결정적.",
    ),
    # ── kafka/broker layer ────────────────────────────────────────────────────
    FaultSpec(
        fault_id="consumer_lag_spike",
        layer="kafka",
        description="source 측 대량 insert 로 topic 유입을 급증시켜 sink consumer lag 누적.",
        safety="manual",
        expected_root_cause_ids=("CONSUMER_LAG_SPIKE",),
        confusion_root_cause_ids=("TOPIC_INGRESS_SPIKE", "SINK_WRITE_LATENCY"),
        grouping_key_pattern=f"{CDC_PRODUCTS_SINK}:CONSUMER_LAG_SPIKE",
        inject_steps=(
            "# tenant-postgres public.products 에 대량 row insert 하여 CDC 유입 급증",
        ),
        recover_steps=("# 유입 중단 후 lag 자연 소진 대기",),
        notes="트래픽 주입은 가역적이나 lag 임계 도달이 확률적이고 sink 처리량에 의존해 비결정적 → manual.",
    ),
    FaultSpec(
        fault_id="broker_resource_pressure",
        layer="kafka",
        description="broker CPU/disk/network 압박 — 공용 platform-kafka 영향으로 위험.",
        safety="unsafe",
        expected_root_cause_ids=("BROKER_RESOURCE_PRESSURE",),
        confusion_root_cause_ids=("CONSUMER_LAG_SPIKE", "PARTITION_IMBALANCE"),
        grouping_key_pattern="platform-kafka:BROKER_RESOURCE_PRESSURE",
        notes="broker 는 모든 테넌트가 공유하는 platform 리소스라 prod 주입 불가. blast radius 가 테넌트 격리를 벗어남.",
    ),
    # ── schema (pipeline) layer ───────────────────────────────────────────────
    FaultSpec(
        fault_id="schema_incompatible_change",
        layer="pipeline",
        description="source 테이블에 비호환 schema 변경(타입 변경)으로 ser/deser 실패 유도.",
        safety="unsafe",
        expected_root_cause_ids=("SCHEMA_MISMATCH",),
        confusion_root_cause_ids=("SINK_CONSTRAINT_VIOLATION", "CONNECTOR_TASK_FAILED"),
        grouping_key_pattern=f"{CDC_PRODUCTS_SINK}:SCHEMA_MISMATCH",
        notes="JDBC sink 의 auto.evolve 가 대부분의 schema 변경을 흡수해 SCHEMA_MISMATCH 가 표출되지 않음. "
        "결정적 재현이 어렵고 sink 테이블 스키마를 영구 변형할 위험 → unsafe.",
    ),
    # ── sink constraint (sink) layer ──────────────────────────────────────────
    FaultSpec(
        fault_id="sink_constraint_violation",
        layer="sink",
        description="sink testdb 에 중복키/제약 위반을 유발할 row 를 직접 삽입.",
        safety="manual",
        expected_root_cause_ids=("SINK_CONSTRAINT_VIOLATION",),
        confusion_root_cause_ids=("PIPELINE_DUPLICATE_SPIKE", "SCHEMA_MISMATCH"),
        grouping_key_pattern=f"{CDC_PRODUCTS_SINK}:SINK_CONSTRAINT_ERROR",
        inject_steps=(
            "# sink testdb 에 connector 가 곧 upsert 할 PK 와 충돌하는 row 선삽입",
        ),
        recover_steps=("# 충돌 row 삭제 후 connector restart",),
        notes="upsert 모드라 대부분 흡수됨. 충돌을 만들려면 sink 테이블을 직접 조작해야 해 운영 데이터 위험 → manual.",
    ),
    # ── source auth (source) layer ────────────────────────────────────────────
    FaultSpec(
        fault_id="source_auth_revoke",
        layer="source",
        description="source DB 사용자 권한/비밀번호를 무효화하여 인증 실패 유도.",
        safety="unsafe",
        expected_root_cause_ids=("SOURCE_AUTH_EXPIRED",),
        confusion_root_cause_ids=("SOURCE_DB_CONNECTION_TIMEOUT", "CREDENTIAL_ROTATION_REGRESSION"),
        grouping_key_pattern=f"{CDC_PRODUCTS_SOURCE}:SOURCE_AUTH_FAILURE",
        notes="DB admin 자격으로 debezium 유저 권한을 변조해야 하고 되돌리기 전까지 CDC 가 영구 손상될 위험 → unsafe.",
    ),
    FaultSpec(
        fault_id="sink_auth_revoke",
        layer="sink",
        description="sink DB 사용자 권한/비밀번호 무효화로 write 인증 실패 유도.",
        safety="unsafe",
        expected_root_cause_ids=("SINK_AUTH_EXPIRED",),
        confusion_root_cause_ids=("SINK_DB_CONNECTION_TIMEOUT", "CREDENTIAL_ROTATION_REGRESSION"),
        grouping_key_pattern=f"{CDC_PRODUCTS_SINK}:SINK_AUTH_FAILURE",
        notes="sink DB admin 자격 변조 필요. source_auth_revoke 와 동일 사유로 unsafe.",
    ),
    # ── config (pipeline) layer ───────────────────────────────────────────────
    FaultSpec(
        fault_id="connector_config_invalid",
        layer="pipeline",
        description="connector 설정을 잘못된 값으로 변경하여 config 오류 유도.",
        safety="unsafe",
        expected_root_cause_ids=("PIPELINE_CONFIG_INVALID",),
        confusion_root_cause_ids=("RECENT_CONFIG_CHANGE_REGRESSION", "CONNECTOR_TASK_FAILED"),
        grouping_key_pattern=f"{CDC_PRODUCTS_SINK}:CONFIG_CHANGE_REGRESSION",
        notes="Kafka Connect 가 config-set 시점에 잘못된 설정을 거부(PUT 422)하므로 런타임 장애로 표출되지 않음. "
        "유효하지만 해로운 설정을 넣으면 connector 가 영구 손상될 위험 → unsafe.",
    ),
    # ── infra layer ───────────────────────────────────────────────────────────
    FaultSpec(
        fault_id="pod_oom_killed",
        layer="infra",
        description="connect worker 에 과도한 메모리 부하를 유발해 OOMKill 유도.",
        safety="unsafe",
        expected_root_cause_ids=("POD_OOM_KILLED",),
        confusion_root_cause_ids=("POD_CRASH_LOOP", "CONNECTOR_WORKER_REBALANCE_LOOP"),
        grouping_key_pattern="platform-kafka:POD_OOM_KILLED",
        notes="connect worker 는 모든 테넌트 파이프라인을 호스팅 → OOM 이 공용 영향. 격리 불가 → unsafe.",
    ),
    # ── change layer ──────────────────────────────────────────────────────────
    FaultSpec(
        fault_id="config_change_regression",
        layer="change",
        description="connector 설정 변경 직후 오류/지연 증가를 시간 상관으로 표출.",
        safety="manual",
        expected_root_cause_ids=("RECENT_CONFIG_CHANGE_REGRESSION",),
        confusion_root_cause_ids=("PIPELINE_CONFIG_INVALID", "CONNECTOR_TASK_FAILED"),
        grouping_key_pattern=f"{CDC_PRODUCTS_SINK}:CONFIG_CHANGE_REGRESSION",
        inject_steps=(
            "# 유효하지만 성능 저하를 부르는 설정(batch.size 축소 등) 적용 후 시점 기록",
        ),
        recover_steps=("# 직전 설정으로 롤백 후 connector restart",),
        notes="가역적이나 '변경 후 증상' 시간 상관을 결정적으로 만들기 어렵고 connector 설정을 직접 만지므로 manual.",
    ),
    # ── data_quality layer ────────────────────────────────────────────────────
    FaultSpec(
        fault_id="pipeline_freshness_delay",
        layer="data_quality",
        description="sink 처리 지연을 유발(예: sink 일시 다운)해 end-to-end freshness 지연 표출.",
        safety="manual",
        expected_root_cause_ids=("PIPELINE_FRESHNESS_DELAY",),
        confusion_root_cause_ids=("CONSUMER_LAG_SPIKE", "SINK_WRITE_LATENCY"),
        grouping_key_pattern=f"{CDC_PRODUCTS_SINK}:FRESHNESS_DELAY",
        inject_steps=("# sink 처리를 일시 지연시켜 watermark delay 유도",),
        recover_steps=("# 지연 원인 제거 후 freshness 회복 대기",),
        notes="freshness 지연 자체는 가역적이나 data_quality 신호가 lag/latency 와 분리되어 표출되는지가 "
        "비결정적 → manual.",
    ),
)


def _validate() -> None:
    """모든 expected/confusion root_cause_id 가 카탈로그에 존재하는지 검증."""
    seen_ids: set[str] = set()
    for spec in FAULT_SPECS:
        if spec.fault_id in seen_ids:
            raise ValueError(f"duplicate fault_id: {spec.fault_id}")
        seen_ids.add(spec.fault_id)
        if not spec.expected_root_cause_ids:
            raise ValueError(f"{spec.fault_id}: expected_root_cause_ids 비어있음")
        for rc in (*spec.expected_root_cause_ids, *spec.confusion_root_cause_ids):
            if rc not in ROOT_CAUSE_INDEX:
                raise ValueError(f"{spec.fault_id}: unknown root_cause_id {rc!r}")
        if spec.safety == "auto" and not (spec.inject_steps and spec.recover_steps):
            raise ValueError(f"{spec.fault_id}: auto fault 는 inject/recover steps 필수")


_validate()

FAULT_SPEC_INDEX: dict[str, FaultSpec] = {s.fault_id: s for s in FAULT_SPECS}


def list_fault_specs(safety: Safety | None = None) -> tuple[FaultSpec, ...]:
    """fault spec 목록. safety 로 필터(예: 'auto' 만 추려 --live 자동 주입)."""
    if safety is None:
        return FAULT_SPECS
    return tuple(s for s in FAULT_SPECS if s.safety == safety)


def get_fault_spec(fault_id: str) -> FaultSpec | None:
    return FAULT_SPEC_INDEX.get(fault_id)


def spec_layer_map() -> dict[str, str]:
    """primary root_cause_id → catalog layer (metrics layer breakdown 용)."""
    out: dict[str, str] = {}
    for spec in FAULT_SPECS:
        for rc in spec.expected_root_cause_ids:
            entry = get_root_cause(rc)
            out[rc] = entry.layer if entry else "unknown"
    return out
