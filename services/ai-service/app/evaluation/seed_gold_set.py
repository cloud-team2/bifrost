"""#887 초기 평가셋 시드 데이터 — 35건 resolved incident gold set.

카탈로그 root_cause_id 별 2~3건씩 대표 시나리오를 커버한다.
실행: python -m app.evaluation.seed_gold_set
"""
from __future__ import annotations

from app.schemas.gold_set import GoldSetEntry, GoldSetLabel, LabelCategory, ReviewStatus

SEED_ENTRIES: list[dict] = [
    # ── source layer ─────────────────────────────────────
    {
        "entry_id": "gs_seed_001",
        "incident_id": "inc_resolved_001",
        "accepted_root_cause_id": "SOURCE_AUTH_EXPIRED",
        "trigger": "source DB credential 만료 (90일 rotation policy)",
        "symptom": "connector task FAILED, extract stage auth error",
        "contributing_factors": ["credential rotation 미알림"],
        "human_verdict": "source credential 만료로 extract 실패. 갱신 후 정상화.",
    },
    {
        "entry_id": "gs_seed_002",
        "incident_id": "inc_resolved_002",
        "accepted_root_cause_id": "SOURCE_AUTH_EXPIRED",
        "trigger": "IAM 정책 변경으로 source read 권한 박탈",
        "symptom": "permission denied, task 반복 재시작",
        "contributing_factors": [],
        "human_verdict": "IAM 정책 복구 후 정상화.",
    },
    {
        "entry_id": "gs_seed_003",
        "incident_id": "inc_resolved_003",
        "accepted_root_cause_id": "SOURCE_NETWORK_REACHABILITY",
        "trigger": "source DB VPC peering 끊김",
        "symptom": "connection refused, extract timeout",
        "contributing_factors": ["네트워크 변경 공지 누락"],
        "human_verdict": "VPC peering 복구 후 정상화.",
    },
    {
        "entry_id": "gs_seed_004",
        "incident_id": "inc_resolved_004",
        "accepted_root_cause_id": "SOURCE_DB_CONNECTION_TIMEOUT",
        "trigger": "source DB 과부하로 connection pool 고갈",
        "symptom": "extract latency 급증, timeout error",
        "contributing_factors": ["동시 배치 작업 실행"],
        "human_verdict": "source DB 부하 경감 후 정상화.",
    },
    {
        "entry_id": "gs_seed_005",
        "incident_id": "inc_resolved_005",
        "accepted_root_cause_id": "SOURCE_READ_LATENCY",
        "trigger": "source 테이블 full scan 발생",
        "symptom": "pipeline freshness 지연, extract 단계 p95 증가",
        "contributing_factors": ["인덱스 미생성"],
        "human_verdict": "인덱스 생성 후 정상화.",
    },
    # ── pipeline layer ───────────────────────────────────
    {
        "entry_id": "gs_seed_006",
        "incident_id": "inc_resolved_006",
        "accepted_root_cause_id": "CONNECTOR_TASK_FAILED",
        "trigger": "connector config 오타로 task 시작 실패",
        "symptom": "task status FAILED, connector restart 무효",
        "contributing_factors": [],
        "human_verdict": "config 수정 후 task 정상 시작.",
    },
    {
        "entry_id": "gs_seed_007",
        "incident_id": "inc_resolved_007",
        "accepted_root_cause_id": "CONNECTOR_TASK_FAILED",
        "trigger": "Kafka Connect worker OOM으로 task 비정상 종료",
        "symptom": "task FAILED, worker log OOM killed",
        "contributing_factors": ["메모리 limit 과소 설정"],
        "human_verdict": "worker 메모리 limit 증설 후 정상화.",
    },
    {
        "entry_id": "gs_seed_008",
        "incident_id": "inc_resolved_008",
        "accepted_root_cause_id": "PIPELINE_CONFIG_INVALID",
        "trigger": "connector 설정에 잘못된 transforms 지정",
        "symptom": "task 시작 즉시 config validation error",
        "contributing_factors": [],
        "human_verdict": "transforms 설정 수정 후 정상화.",
    },
    {
        "entry_id": "gs_seed_009",
        "incident_id": "inc_resolved_009",
        "accepted_root_cause_id": "SCHEMA_MISMATCH",
        "trigger": "source 스키마 변경 (컬럼 타입 int→string)",
        "symptom": "deserialization error, task 반복 실패",
        "contributing_factors": ["스키마 변경 사전 공지 없음"],
        "human_verdict": "schema registry 호환성 설정 변경 후 정상화.",
    },
    {
        "entry_id": "gs_seed_010",
        "incident_id": "inc_resolved_010",
        "accepted_root_cause_id": "SCHEMA_MISMATCH",
        "trigger": "Avro schema evolution 호환성 위반",
        "symptom": "serialization exception, record skip 급증",
        "contributing_factors": [],
        "human_verdict": "BACKWARD 호환 스키마 재등록 후 정상화.",
    },
    {
        "entry_id": "gs_seed_011",
        "incident_id": "inc_resolved_011",
        "accepted_root_cause_id": "CONNECTOR_WORKER_REBALANCE_LOOP",
        "trigger": "worker 노드 불안정으로 반복 rebalance",
        "symptom": "task 할당 불안정, lag 증가",
        "contributing_factors": ["노드 disk pressure"],
        "human_verdict": "노드 교체 후 rebalance 안정화.",
    },
    # ── kafka layer ──────────────────────────────────────
    {
        "entry_id": "gs_seed_012",
        "incident_id": "inc_resolved_012",
        "accepted_root_cause_id": "CONSUMER_LAG_SPIKE",
        "trigger": "upstream 이벤트 폭증 (프로모션 트래픽)",
        "symptom": "consumer lag 급증, freshness 지연",
        "contributing_factors": ["consumer 파티션 수 부족"],
        "human_verdict": "파티션 수 증가 + consumer 스케일아웃 후 정상화.",
    },
    {
        "entry_id": "gs_seed_013",
        "incident_id": "inc_resolved_013",
        "accepted_root_cause_id": "CONSUMER_LAG_SPIKE",
        "trigger": "consumer 처리 로직 버그로 처리량 저하",
        "symptom": "lag 증가, offset commit rate 감소",
        "contributing_factors": [],
        "human_verdict": "버그 핫픽스 배포 후 lag 해소.",
    },
    {
        "entry_id": "gs_seed_014",
        "incident_id": "inc_resolved_014",
        "accepted_root_cause_id": "BROKER_RESOURCE_PRESSURE",
        "trigger": "broker disk 사용량 90% 초과",
        "symptom": "produce latency 증가, ISR shrink",
        "contributing_factors": ["retention 설정 과다"],
        "human_verdict": "retention 조정 + disk 확장 후 정상화.",
    },
    {
        "entry_id": "gs_seed_015",
        "incident_id": "inc_resolved_015",
        "accepted_root_cause_id": "PARTITION_IMBALANCE",
        "trigger": "leader 편중으로 특정 broker 과부하",
        "symptom": "일부 파티션 produce/consume latency 급증",
        "contributing_factors": [],
        "human_verdict": "preferred leader election 실행 후 정상화.",
    },
    {
        "entry_id": "gs_seed_016",
        "incident_id": "inc_resolved_016",
        "accepted_root_cause_id": "TOPIC_INGRESS_SPIKE",
        "trigger": "upstream 배치 job이 대량 이벤트 발행",
        "symptom": "topic bytes-in 급증, downstream lag 증가",
        "contributing_factors": ["rate limit 미설정"],
        "human_verdict": "upstream rate limit 적용 후 안정화.",
    },
    {
        "entry_id": "gs_seed_017",
        "incident_id": "inc_resolved_017",
        "accepted_root_cause_id": "CONSUMER_REBALANCE_LOOP",
        "trigger": "consumer heartbeat timeout 설정 과소",
        "symptom": "consumer group 반복 rebalance, 처리 중단",
        "contributing_factors": ["GC pause 빈발"],
        "human_verdict": "heartbeat/session timeout 조정 후 안정화.",
    },
    # ── sink layer ───────────────────────────────────────
    {
        "entry_id": "gs_seed_018",
        "incident_id": "inc_resolved_018",
        "accepted_root_cause_id": "SINK_AUTH_EXPIRED",
        "trigger": "sink DB credential 만료",
        "symptom": "write stage auth error, task FAILED",
        "contributing_factors": [],
        "human_verdict": "sink credential 갱신 후 정상화.",
    },
    {
        "entry_id": "gs_seed_019",
        "incident_id": "inc_resolved_019",
        "accepted_root_cause_id": "SINK_WRITE_LATENCY",
        "trigger": "sink DB lock contention 증가",
        "symptom": "write p95 증가, connector task 지연",
        "contributing_factors": ["동시 대용량 UPDATE 쿼리"],
        "human_verdict": "경합 쿼리 최적화 후 정상화.",
    },
    {
        "entry_id": "gs_seed_020",
        "incident_id": "inc_resolved_020",
        "accepted_root_cause_id": "SINK_CONSTRAINT_VIOLATION",
        "trigger": "sink 테이블 unique constraint 위반",
        "symptom": "duplicate key error, record write 실패",
        "contributing_factors": ["upsert 미적용"],
        "human_verdict": "sink connector upsert 모드 전환 후 정상화.",
    },
    {
        "entry_id": "gs_seed_021",
        "incident_id": "inc_resolved_021",
        "accepted_root_cause_id": "SINK_DB_CONNECTION_TIMEOUT",
        "trigger": "sink DB 네트워크 단절",
        "symptom": "write timeout, connection refused",
        "contributing_factors": [],
        "human_verdict": "네트워크 복구 후 정상화.",
    },
    # ── infra layer ──────────────────────────────────────
    {
        "entry_id": "gs_seed_022",
        "incident_id": "inc_resolved_022",
        "accepted_root_cause_id": "POD_OOM_KILLED",
        "trigger": "대용량 레코드 처리 시 메모리 초과",
        "symptom": "pod OOMKilled, container restart",
        "contributing_factors": ["memory limit 과소"],
        "human_verdict": "memory limit 증설 후 정상화.",
    },
    {
        "entry_id": "gs_seed_023",
        "incident_id": "inc_resolved_023",
        "accepted_root_cause_id": "POD_CRASH_LOOP",
        "trigger": "잘못된 환경변수로 앱 시작 실패",
        "symptom": "CrashLoopBackOff, pod 반복 재시작",
        "contributing_factors": [],
        "human_verdict": "환경변수 수정 후 정상화.",
    },
    {
        "entry_id": "gs_seed_024",
        "incident_id": "inc_resolved_024",
        "accepted_root_cause_id": "NODE_PRESSURE",
        "trigger": "노드 CPU 사용량 95% 초과",
        "symptom": "pod eviction, scheduling 지연",
        "contributing_factors": ["noisy neighbor 워크로드"],
        "human_verdict": "노드 스케일아웃 후 정상화.",
    },
    {
        "entry_id": "gs_seed_025",
        "incident_id": "inc_resolved_025",
        "accepted_root_cause_id": "PVC_PRESSURE",
        "trigger": "Kafka data PVC 사용량 95% 초과",
        "symptom": "broker write 실패, topic create 불가",
        "contributing_factors": ["retention 과다"],
        "human_verdict": "PVC 확장 + retention 조정 후 정상화.",
    },
    {
        "entry_id": "gs_seed_026",
        "incident_id": "inc_resolved_026",
        "accepted_root_cause_id": "DEPLOYMENT_REGRESSION",
        "trigger": "신규 connector 이미지 배포 후 에러 급증",
        "symptom": "error rate 급증, task 반복 실패",
        "contributing_factors": [],
        "human_verdict": "이전 이미지로 롤백 후 정상화.",
    },
    # ── change layer ─────────────────────────────────────
    {
        "entry_id": "gs_seed_027",
        "incident_id": "inc_resolved_027",
        "accepted_root_cause_id": "RECENT_CONFIG_CHANGE_REGRESSION",
        "trigger": "connector config 변경 직후 장애",
        "symptom": "task FAILED, config validation error",
        "contributing_factors": [],
        "human_verdict": "config 롤백 후 정상화.",
    },
    {
        "entry_id": "gs_seed_028",
        "incident_id": "inc_resolved_028",
        "accepted_root_cause_id": "RECENT_SCHEMA_CHANGE_REGRESSION",
        "trigger": "schema registry subject 버전 업데이트 직후",
        "symptom": "deserialization 실패, 호환성 에러",
        "contributing_factors": [],
        "human_verdict": "이전 스키마 버전 복원 후 정상화.",
    },
    {
        "entry_id": "gs_seed_029",
        "incident_id": "inc_resolved_029",
        "accepted_root_cause_id": "RECENT_IMAGE_DEPLOYMENT_REGRESSION",
        "trigger": "Connect worker 이미지 업데이트 후",
        "symptom": "worker 반복 재시작, task 할당 실패",
        "contributing_factors": ["canary 배포 미적용"],
        "human_verdict": "이전 이미지 롤백 후 정상화.",
    },
    {
        "entry_id": "gs_seed_030",
        "incident_id": "inc_resolved_030",
        "accepted_root_cause_id": "CREDENTIAL_ROTATION_REGRESSION",
        "trigger": "credential rotation 후 새 credential 미적용",
        "symptom": "auth failure, connector task FAILED",
        "contributing_factors": ["rotation 자동화 미완"],
        "human_verdict": "새 credential 적용 후 정상화.",
    },
    # ── data_quality layer ───────────────────────────────
    {
        "entry_id": "gs_seed_031",
        "incident_id": "inc_resolved_031",
        "accepted_root_cause_id": "UPSTREAM_DATA_VOLUME_ANOMALY",
        "trigger": "upstream 시스템 장애로 이벤트 유입 급감",
        "symptom": "topic throughput 급감, freshness alert",
        "contributing_factors": [],
        "human_verdict": "upstream 복구 후 유입량 정상화.",
    },
    {
        "entry_id": "gs_seed_032",
        "incident_id": "inc_resolved_032",
        "accepted_root_cause_id": "PIPELINE_DUPLICATE_SPIKE",
        "trigger": "connector exactly-once 설정 누락",
        "symptom": "sink 테이블 중복 레코드 급증",
        "contributing_factors": ["consumer offset reset 발생"],
        "human_verdict": "exactly-once 설정 적용 후 중복 해소.",
    },
    {
        "entry_id": "gs_seed_033",
        "incident_id": "inc_resolved_033",
        "accepted_root_cause_id": "PIPELINE_FRESHNESS_DELAY",
        "trigger": "connector task 처리량 부족",
        "symptom": "end-to-end freshness SLI 위반",
        "contributing_factors": ["파티션 수 부족", "consumer lag 누적"],
        "human_verdict": "task 수 증설 후 freshness 복구.",
    },
    {
        "entry_id": "gs_seed_034",
        "incident_id": "inc_resolved_034",
        "accepted_root_cause_id": "SCHEMA_NULL_RATE_SPIKE",
        "trigger": "upstream 앱 버그로 필수 필드 null 전송",
        "symptom": "특정 컬럼 null rate 30% → 90%",
        "contributing_factors": [],
        "human_verdict": "upstream 앱 버그 수정 후 null rate 정상화.",
    },
    {
        "entry_id": "gs_seed_035",
        "incident_id": "inc_resolved_035",
        "accepted_root_cause_id": "PIPELINE_TASK_RETRY_EXHAUSTED",
        "trigger": "일시적 네트워크 장애로 retry 소진",
        "symptom": "task FAILED, max retries exceeded",
        "contributing_factors": ["retry 횟수 과소 설정"],
        "human_verdict": "네트워크 복구 + retry 설정 조정 후 정상화.",
    },
]


def build_seed_entries() -> list[GoldSetEntry]:
    entries = []
    for d in SEED_ENTRIES:
        entry = GoldSetEntry(
            entry_id=d["entry_id"],
            incident_id=d["incident_id"],
            accepted_root_cause_id=d["accepted_root_cause_id"],
            trigger=d.get("trigger"),
            symptom=d.get("symptom"),
            contributing_factors=d.get("contributing_factors", []),
            evidence_ids=d.get("evidence_ids", []),
            human_verdict=d.get("human_verdict"),
            labels=[
                GoldSetLabel(
                    label_id=f"lbl_{d['entry_id']}_{cat.value}",
                    category=cat,
                    value=d.get(cat.value) or d.get("accepted_root_cause_id", ""),
                )
                for cat in [LabelCategory.ROOT_CAUSE]
            ] + ([
                GoldSetLabel(
                    label_id=f"lbl_{d['entry_id']}_trigger",
                    category=LabelCategory.TRIGGER,
                    value=d["trigger"],
                )
            ] if d.get("trigger") else []) + ([
                GoldSetLabel(
                    label_id=f"lbl_{d['entry_id']}_symptom",
                    category=LabelCategory.SYMPTOM,
                    value=d["symptom"],
                )
            ] if d.get("symptom") else []),
            review_status=ReviewStatus.REVIEWED,
            reviewed_by="seed",
        )
        entries.append(entry)
    return entries


async def seed_gold_set_async() -> int:
    from app.persistence.gold_set_repository import get_gold_set_repo

    repo = get_gold_set_repo()
    entries = build_seed_entries()
    count = 0
    for entry in entries:
        existing = await repo.get(entry.entry_id)
        if existing is None:
            await repo.create(entry)
            count += 1
    return count


if __name__ == "__main__":
    import asyncio

    created = asyncio.run(seed_gold_set_async())
    print(f"Seeded {created} gold set entries.")
