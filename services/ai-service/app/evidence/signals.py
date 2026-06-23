"""Structured evidence signal extraction for RCA matching.

The extractor emits catalog-level evidence phrases only. It intentionally
does not copy raw values, connector names, credentials, case identifiers, gold
labels, or normal-state summaries into the generated signal text.
"""
from __future__ import annotations

import re
from collections.abc import Iterable
from typing import Any

from app.evidence.metadata import is_control_id, is_control_metadata_key, strip_control_metadata

_FAILED_RE = re.compile(r"\bFAILED\b|failed|failure|exception|\berror\b|오류|에러|실패", re.IGNORECASE)
_TASK_FAILURE_RE = re.compile(r"\bFAILED\b|failed|failure|exception|오류|에러|실패", re.IGNORECASE)
_TRACE_RE = re.compile(r"trace|stack|exception|worker log|stacktrace|로그|트레이스", re.IGNORECASE)
_AUTH_RE = re.compile(
    r"\b1045\b|\b28000\b|access\s*denied|permission\s*denied|unauthori[sz]ed|authentication|"
    r"\bauth\b|auth(?:entication)?\s*error|token\s*expired|credential\s*expired|"
    r"password authentication failed|invalid password|invalid credential|"
    r"credential invalid|credential expired|sasl|using password|인증\s*실패|권한\s*거부|"
    r"credential\s*만료|credential\s*미적용|토큰\s*만료",
    re.IGNORECASE,
)
_SCHEMA_RE = re.compile(
    r"\b1366\b|\b22007\b|schema|serialization|deserialization|deserialize|"
    r"incompatible|compatibility|converter|avro|serde|type mismatch|schema registry|"
    r"incorrect\s+\w+\s+value|incorrect .* value|field type|dataexception|"
    r"invalid input syntax|date/time field value out of range|invalid datetime format|"
    r"(?:date|time|timestamp|datetime)\s+(?:parse|parsing)\s+(?:error|fail)|"
    r"parse\s+(?:error|fail).*(?:date|time|timestamp|datetime)|"
    r"cannot parse .*?(?:date|time|timestamp|datetime)|스키마|역직렬화|호환성",
    re.IGNORECASE,
)
_CONFIG_RE = re.compile(
    r"invalid config|unknown config|config validation|configuration error|"
    r"configuration value is invalid|"
    r"(?:invalid|bad) value for configuration|value is invalid|integer is expected|"
    r"invalid option|required configuration|missing required config(?:uration)?|"
    r"required config(?:uration)?.*(?:not present|missing)|"
    r"configuration .*?(?:not present|missing|required)|"
    r"invalid/empty configuration value|empty configuration value|"
    r"bad config|config diff|transform|class not found|classnotfound|noclassdeffound|"
    r"failed to find any class|"
    r"config\s*변경|설정\s*오류|설정\s*오타|잘못된\s*설정",
    re.IGNORECASE,
)
_CONSTRAINT_RE = re.compile(
    r"\b4025\b|\b23000\b|duplicate key|unique constraint|constraint violation|foreign key|not null|"
    r"check constraint|constraint .* failed|data integrity|sqlintegrity|record rejected|duplicate records?|"
    r"중복\s*레코드|제약\s*위반|"
    r"(?:record|row|sink|data)\s+validation fail",
    re.IGNORECASE,
)
_TIMEOUT_RE = re.compile(
    r"\b2002\b|timeout|timed out|timeout expired|connection timed out|"
    r"read timed out|write timed out|can't connect to server|could not connect|타임아웃",
    re.IGNORECASE,
)
_NETWORK_RE = re.compile(
    r"dns|unknownhost|no route|network unreachable|connection refused|"
    r"connect exception|tcp connect|endpoint unreachable|host unreachable|i/o error|"
    r"can't connect to server|could not connect|name or service not known|temporary failure in name resolution|"
    r"네트워크\s*단절|연결\s*실패|호스트.*포트",
    re.IGNORECASE,
)
_LAG_RE = re.compile(
    r"consumer lag|lag p95|offset progression|commit rate|total lag|"
    r"lag_total|lag high|offset commit|lag\s*증가|lag\s*급증",
    re.IGNORECASE,
)
_DEPLOYMENT_RE = re.compile(
    r"rollout|deployment|deploy|image|이미지|배포|신규\s*connector",
    re.IGNORECASE,
)
_DEGRADATION_RE = re.compile(
    r"error|failure|failed|exception|latency|restart|crash|oom|unhealthy|"
    r"할당\s*실패|재시작|에러|오류|실패|급증|증가|악화",
    re.IGNORECASE,
)
_DIFF_RE = re.compile(
    r"diff|tag|version|runtime|config|changed|change record|previous|"
    r"이전|대비|차이|변경|버전|설정",
    re.IGNORECASE,
)
_TEMPORAL_DEPLOYMENT_RE = re.compile(
    r"after|since|following|followed|post[- ]?rollout|post[- ]?deploy|"
    r"이후|직후|뒤이어|후",
    re.IGNORECASE,
)
_RETRY_RE = re.compile(
    r"retry|retries|max retries exceeded|max retry reached|retry exhausted|"
    r"재시도|retry\s*소진|반복\s*실패",
    re.IGNORECASE,
)
_IDEMPOTENCY_RE = re.compile(
    r"exactly[- ]once|idempotency|offset reset|replay|backfill|중복\s*해소|"
    r"멱등|offset\s*reset",
    re.IGNORECASE,
)
_NORMAL_RE = re.compile(
    r"no\s+(?:open\s+)?incidents?|all connectors running|tasks running|"
    r"no error logs|normal|healthy|valid|running|정상|없음|유효",
    re.IGNORECASE,
)
_CONNECTOR_FAILED_RE = re.compile(
    r"(?:connector|커넥터|task|태스크)[^.;,\n]{0,80}"
    r"(?:status|state|상태)?\s*[:=]?\s*(?:FAILED|failed|failure|error|실패|오류|에러)"
    r"|(?:status|state|상태)\s*[:=]?\s*(?:FAILED|failed|failure|error|실패|오류|에러)"
    r"[^.;,\n]{0,80}(?:connector|커넥터|task|태스크)",
    re.IGNORECASE,
)
_CONNECTOR_STATE_FAILED_RE = re.compile(
    r"\b(?:status|state)\s*[:=]?\s*(?:FAILED|failed|failure|error)\b"
    r"|상태\s*[:=]?\s*(?:FAILED|실패|오류|에러)",
    re.IGNORECASE,
)
_DATABASE_DOES_NOT_EXIST_RE = re.compile(
    r"database\s+[\"'][^\"']+[\"']\s+does not exist|database\s+\S+\s+does not exist",
    re.IGNORECASE,
)
_SCHEMA_VERSION_RE = re.compile(r"schema version|subject version|schema registry", re.IGNORECASE)
_SCHEMA_STRUCTURE_RE = re.compile(
    r"\b1366\b|\b22007\b|incorrect\s+\w+\s+value|incorrect .* value|"
    r"type mismatch|field type|invalid input syntax|date/time field value out of range|"
    r"invalid datetime format|(?:date|time|timestamp|datetime)\s+(?:parse|parsing)\s+(?:error|fail)|"
    r"parse\s+(?:error|fail).*(?:date|time|timestamp|datetime)|"
    r"cannot parse .*?(?:date|time|timestamp|datetime)|필드 타입",
    re.IGNORECASE,
)
_CONFIG_CHANGE_RE = re.compile(r"config change|config diff|config 변경", re.IGNORECASE)
_DUPLICATE_COUNT_RE = re.compile(r"duplicate count|중복\s*레코드", re.IGNORECASE)
_READ_LATENCY_RE = re.compile(
    r"read_latency|read latency|extract duration|extract 단계 p95|full scan",
    re.IGNORECASE,
)
_WRITE_LATENCY_RE = re.compile(r"write_latency|write latency|write duration|write p95", re.IGNORECASE)
_LAG_SPIKE_RE = re.compile(
    r"consumer lag|lag p95|total lag|lag_total|lag high|lag\s*증가|lag\s*급증",
    re.IGNORECASE,
)
_OFFSET_PROGRESS_RE = re.compile(r"offset progression|commit rate|offset_progression", re.IGNORECASE)
_INGRESS_RE = re.compile(r"topic ingress|incoming messages|bytes-in", re.IGNORECASE)

_NEGATED_SIGNAL_PATTERNS = {
    "global": (
        re.compile(r"\bno\s+(?:open\s+)?incidents?\b", re.IGNORECASE),
        re.compile(r"\bno\s+error\s+logs?\b", re.IGNORECASE),
        re.compile(r"\ball\s+connectors\s+running\b|\btasks\s+running\b", re.IGNORECASE),
        re.compile(r"\b(?:status|state)\s*[:=]?\s*(?:running|ok|healthy|normal|valid)\b", re.IGNORECASE),
        re.compile(r"\b(?:status|state)\s*[:=]?\s*(?:RUNNING|정상|유효)\b|상태\s*[:=]?\s*(?:RUNNING|정상|유효)", re.IGNORECASE),
    ),
    "failure": (
        re.compile(
            r"\b(?:no|not|without)\s+(?:(?!\bbut\b|[.;,\n]).){0,80}"
            r"(?:failed|failure|error|exception|issue|problem)s?\b",
            re.IGNORECASE,
        ),
        re.compile(r"(?:오류|에러|실패|예외|문제)\s*(?:없음|없다|아님|아닌|미확인)", re.IGNORECASE),
    ),
    "connector": (
        re.compile(
            r"\b(?:connector|task)s?\s+(?:status|state)?\s*[:=]?\s*"
            r"(?:running|ok|healthy|normal|valid)\b",
            re.IGNORECASE,
        ),
        re.compile(r"\b(?:no|not|without)\s+(?:(?!\bbut\b|[.;,\n]).){0,80}(?:failed|failure)\s+tasks?\b", re.IGNORECASE),
        re.compile(r"\bno\s+(?:task\s+)?failures?\b", re.IGNORECASE),
        re.compile(r"(?:connector|커넥터|task|태스크).{0,80}(?:RUNNING|정상|유효)", re.IGNORECASE),
        re.compile(r"(?:task|태스크)?\s*(?:실패|오류|에러)\s*(?:없음|없다|아님|아닌)", re.IGNORECASE),
    ),
    "trace": (
        re.compile(r"\bno\s+(?:task\s+)?(?:trace|worker log)s?\b", re.IGNORECASE),
        re.compile(r"\b(?:trace|worker log)s?\s+(?:not found|missing|unavailable)\b", re.IGNORECASE),
        re.compile(r"(?:trace|worker log|트레이스|로그)\s*(?:없음|없다|미확인|누락)", re.IGNORECASE),
    ),
    "auth": (
        re.compile(
            r"\b(?:no|without)\s+(?:(?!\bbut\b|[.;,\n]).){0,80}"
            r"(?:auth|authentication|permission|credential|token|password|sasl)"
            r"(?:(?!\bbut\b|[.;,\n]).){0,80}(?:error|failure|issue|problem|expired|denied)s?\b",
            re.IGNORECASE,
        ),
        re.compile(
            r"\bnot\s+(?:an?\s+)?(?:auth|authentication|permission|credential|token|password|sasl)"
            r"(?:(?!\bbut\b|[.;,\n]).){0,80}(?:error|failure|issue|problem|expired|denied)s?\b",
            re.IGNORECASE,
        ),
        re.compile(
            r"\b(?:auth|authentication|permission|credential|token|password|sasl)"
            r"(?:(?!\bbut\b|[.;,\n]).){0,32}(?:normal|valid|healthy|unchanged|ok)\b",
            re.IGNORECASE,
        ),
        re.compile(
            r"\b(?:auth|authentication|permission|credential|token|password|sasl)"
            r".{0,80}(?:change|rotation|변경|문제)?\s*"
            r"(?:없음|없다|아님|아닌|정상|유효|normal|valid|healthy|unchanged)\b",
            re.IGNORECASE,
        ),
        re.compile(r"(?:인증|권한|토큰|credential).{0,80}(?:오류|실패|문제|만료)?\s*(?:없음|없다|아님|아닌|정상|유효)", re.IGNORECASE),
    ),
    "schema": (
        re.compile(
            r"\b(?:no|not|without)\s+(?:(?!\bbut\b|[.;,\n]).){0,80}"
            r"(?:schema|serialization|deserialization|compatibility|converter|avro|serde)"
            r"(?:(?!\bbut\b|[.;,\n]).){0,80}(?:error|failure|issue|problem|mismatch)s?\b",
            re.IGNORECASE,
        ),
        re.compile(
            r"\b(?:schema|serialization|deserialization|compatibility|converter|subject)"
            r"(?:(?!\bbut\b|[.;,\n]).){0,80}(?:normal|healthy|valid|compatible|unchanged|ok)\b",
            re.IGNORECASE,
        ),
        re.compile(r"(?:스키마|역직렬화|직렬화|호환성).{0,80}(?:오류|에러|실패|문제|변경)?\s*(?:없음|없다|아님|아닌|정상|유효|호환)", re.IGNORECASE),
    ),
    "schema_change": (
        re.compile(r"\b(?:no|not|without)\s+(?:(?!\bbut\b|[.;,\n]).){0,80}(?:schema|subject)\s+(?:change|diff|update)s?\b", re.IGNORECASE),
        re.compile(r"\b(?:schema|subject|schema registry).{0,80}(?:unchanged|same|stable|valid|healthy)\b", re.IGNORECASE),
        re.compile(r"(?:스키마|subject).{0,80}(?:변경|차이|업데이트)?\s*(?:없음|없다|아님|아닌|정상|유효)", re.IGNORECASE),
    ),
    "config": (
        re.compile(
            r"\b(?:no|not|without)\s+(?:(?!\bbut\b|[.;,\n]).){0,80}"
            r"(?:config|configuration|option|setting)"
            r"(?:(?!\bbut\b|[.;,\n]).){0,80}(?:error|failure|issue|problem|validation|diff|change)s?\b",
            re.IGNORECASE,
        ),
        re.compile(
            r"\b(?:config|configuration|option|setting)"
            r"(?:(?!\bbut\b|[.;,\n]).){0,80}(?:normal|healthy|valid|unchanged|same|ok|snapshot)\b",
            re.IGNORECASE,
        ),
        re.compile(r"(?:config|configuration|설정).{0,80}(?:오류|에러|실패|문제|변경|차이|오타)?\s*(?:없음|없다|아님|아닌|정상|유효)", re.IGNORECASE),
    ),
    "config_change": (
        re.compile(r"\b(?:no|not|without)\s+(?:(?!\bbut\b|[.;,\n]).){0,80}(?:config|configuration)\s+(?:change|diff|update)s?\b", re.IGNORECASE),
        re.compile(r"\b(?:config|configuration).{0,80}(?:unchanged|same|stable|snapshot)\b", re.IGNORECASE),
        re.compile(r"(?:config|configuration|설정).{0,80}(?:변경|차이|diff)?\s*(?:없음|없다|아님|아닌|정상|유효)", re.IGNORECASE),
    ),
    "duplicate": (
        re.compile(
            r"\b(?:no|not|without)\s+(?:(?!\bbut\b|[.;,\n]).){0,80}"
            r"(?:duplicate|constraint|unique|foreign key|not null|data integrity)"
            r"(?:(?!\bbut\b|[.;,\n]).){0,80}(?:error|failure|issue|problem|violation|records?|keys?)s?\b",
            re.IGNORECASE,
        ),
        re.compile(r"\bduplicate\s+count\s*[:=]?\s*0\b", re.IGNORECASE),
        re.compile(r"\b(?:duplicate|constraint).{0,80}(?:normal|healthy|valid|unchanged|zero)\b", re.IGNORECASE),
        re.compile(r"(?:중복|제약).{0,80}(?:레코드|키|위반|오류|에러|문제)?\s*(?:없음|없다|아님|아닌|정상|해소)", re.IGNORECASE),
    ),
    "lag": (
        re.compile(
            r"\b(?:consumer\s+)?lag(?:(?!\bbut\b|[.;,\n]).){0,80}"
            r"(?:normal|healthy|stable|within\s+threshold|below\s+threshold|ok)\b",
            re.IGNORECASE,
        ),
        re.compile(r"\b(?:offset progression|commit rate).{0,80}(?:normal|healthy|stable|ok)\b", re.IGNORECASE),
        re.compile(r"\bno\s+(?:consumer\s+)?lag\s+(?:spike|increase|surge|issue|problem)\b", re.IGNORECASE),
        re.compile(r"(?:consumer\s*)?lag.{0,80}(?:정상|안정|임계.*이내|증가\s*없음|급증\s*없음)", re.IGNORECASE),
        re.compile(r"(?:offset progression|commit rate).{0,80}(?:정상|안정|둔화\s*없음|감소\s*없음)", re.IGNORECASE),
    ),
    "timeout": (
        re.compile(r"\b(?:no|not|without)\s+(?:(?!\bbut\b|[.;,\n]).){0,80}(?:timeout|timed out)\b", re.IGNORECASE),
        re.compile(r"(?:timeout|타임아웃).{0,80}(?:없음|없다|아님|아닌|정상)", re.IGNORECASE),
    ),
    "network": (
        re.compile(
            r"\b(?:no|not|without)\s+(?:(?!\bbut\b|[.;,\n]).){0,80}"
            r"(?:network|reachability|connection|endpoint|dns)\s+(?:error|failure|issue|problem)\b",
            re.IGNORECASE,
        ),
        re.compile(r"\b(?:endpoint|connection|network|dns).{0,80}(?:reachable|healthy|normal|ok|available)\b", re.IGNORECASE),
        re.compile(r"(?:연결|네트워크|endpoint|호스트).{0,80}(?:정상|성공|가능|문제\s*없음)", re.IGNORECASE),
    ),
    "deployment": (
        re.compile(r"\b(?:deployment|rollout|deploy|image).{0,80}(?:healthy|normal|successful|completed|ok)\b", re.IGNORECASE),
        re.compile(r"(?:배포|이미지).{0,80}(?:정상|성공|완료|문제\s*없음)", re.IGNORECASE),
    ),
    "retry": (
        re.compile(r"\bno\s+(?:(?!\bbut\b|[.;,\n]).){0,80}(?:retry|retries).{0,80}(?:exhausted|failure|issue|problem)\b", re.IGNORECASE),
        re.compile(r"\b(?:retry|retries).{0,80}(?:normal|healthy|available|remaining|ok)\b", re.IGNORECASE),
        re.compile(r"(?:retry|재시도).{0,80}(?:소진|실패|문제)?\s*(?:없음|없다|아님|아닌|정상|잔여)", re.IGNORECASE),
    ),
    "idempotency": (
        re.compile(r"\b(?:idempotency|replay|backfill|duplicate).{0,80}(?:normal|healthy|gap\s*closed|ok)\b", re.IGNORECASE),
        re.compile(r"(?:멱등|중복|replay|backfill).{0,80}(?:정상|해소|gap\s*없음|문제\s*없음)", re.IGNORECASE),
    ),
    "latency": (
        re.compile(r"\b(?:read|write|extract|sink|source)?\s*latency.{0,80}(?:normal|healthy|stable|within\s+threshold|ok)\b", re.IGNORECASE),
        re.compile(r"(?:read|write|extract|sink|source|읽기|쓰기)?\s*latency.{0,80}(?:정상|안정|임계.*이내)", re.IGNORECASE),
    ),
    "ingress": (
        re.compile(r"\b(?:topic ingress|incoming messages|bytes-in).{0,80}(?:normal|healthy|stable|within\s+threshold|ok)\b", re.IGNORECASE),
        re.compile(r"(?:topic ingress|incoming messages|bytes-in|유입).{0,80}(?:정상|안정|임계.*이내)", re.IGNORECASE),
    ),
}

_SOURCE_HINT_RE = re.compile(r"\bsource\b|extract|read stage|source_reader|source connector|소스", re.IGNORECASE)
_SINK_HINT_RE = re.compile(r"\bsink\b|write stage|sink_writer|sink connector|flush|batch|jdbc sink", re.IGNORECASE)
def evidence_signal_summary(tool_name: str, raw_payload: Any) -> str:
    """Return semicolon-separated general RCA evidence signals."""
    pieces = [strip_control_metadata(item) for item in _flatten_text(raw_payload)]
    text = " ".join(pieces)
    if not text:
        return ""

    tags: list[str] = []
    lower = text.casefold()
    structured_side = _structured_fault_side(raw_payload)
    side = structured_side or _side_hint(lower, tool_name)
    auth_side = _auth_side_hint(pieces, tool_name)
    if auth_side == "unknown" and structured_side in {"source", "sink"}:
        auth_side = structured_side

    failed = _has_signal(pieces, _TASK_FAILURE_RE, "failure")
    connector_failed = _has_signal(pieces, _CONNECTOR_FAILED_RE, "connector") or (
        "connector" in lower and _has_signal(pieces, _CONNECTOR_STATE_FAILED_RE, "connector")
    )
    trace = _has_signal(pieces, _TRACE_RE, "trace")
    auth = _has_signal(pieces, _AUTH_RE, "auth")
    schema = _has_signal(pieces, _SCHEMA_RE, "schema")
    config = _has_signal(pieces, _CONFIG_RE, "config")
    constraint = _has_signal(pieces, _CONSTRAINT_RE, "duplicate")
    timeout = _has_signal(pieces, _TIMEOUT_RE, "timeout")
    network = _has_signal(pieces, _NETWORK_RE, "network")
    missing_database = _has_signal(pieces, _DATABASE_DOES_NOT_EXIST_RE, "failure")
    lag_spike = _has_signal(pieces, _LAG_SPIKE_RE, "lag")
    offset_slow = _has_signal(pieces, _OFFSET_PROGRESS_RE, "lag")
    deployment = _has_signal(pieces, _DEPLOYMENT_RE, "deployment")
    degradation = _has_signal(pieces, _DEGRADATION_RE, "failure")
    retry = _has_signal(pieces, _RETRY_RE, "retry")
    idempotency = _has_signal(pieces, _IDEMPOTENCY_RE, "idempotency")

    if connector_failed or missing_database:
        _add(tags, "connector task status FAILED")
    if (trace and failed) or missing_database:
        _add(tags, "task trace 또는 worker log")

    if auth:
        if auth_side == "source":
            _add(tags, "source auth/permission error log")
        elif auth_side == "sink":
            _add(tags, "sink auth/permission error log")
        else:
            _add(tags, "auth/permission error log")

    if schema:
        _add(tags, "serialization/deserialization/schema error")
    if _has_signal(pieces, _SCHEMA_VERSION_RE, "schema_change"):
        _add(tags, "schema version 변경 이력")
    if _has_signal(pieces, _SCHEMA_STRUCTURE_RE, "schema"):
        _add(tags, "데이터 샘플 구조 변화")

    if config:
        _add(tags, "config validation error 또는 invalid option log")
    if _has_signal(pieces, _CONFIG_CHANGE_RE, "config_change"):
        _add(tags, "최근 pipeline/connector config 변경")

    if (
        deployment
        and degradation
        and _TEMPORAL_DEPLOYMENT_RE.search(text)
        and not _is_normal_only(text)
    ):
        _add(tags, "image rollout 이후 error/latency/restart 증가")
    if deployment and _DIFF_RE.search(text) and not _is_normal_only(text):
        _add(tags, "image version update")

    if constraint:
        _add(tags, "sink constraint 또는 duplicate key error")
        _add(tags, "동일 record 반복 실패")
    if _has_signal(pieces, _DUPLICATE_COUNT_RE, "duplicate"):
        _add(tags, "duplicate count 또는 duplicate key error 증가")
    if idempotency:
        _add(tags, "retry/replay/backfill 또는 idempotency gap")

    if network:
        if side == "source":
            _add(tags, "Bifrost에서 source endpoint reachability 실패")
        elif side == "sink":
            _add(tags, "sink dependency 연결 실패 또는 connection timeout")
        else:
            _add(tags, "Bifrost에서 source endpoint reachability 실패")

    if timeout:
        if side == "source":
            _add(tags, "source connection timeout 증가")
            _add(tags, "pipeline extract/read 단계 timeout log")
        elif side == "sink":
            _add(tags, "sink write timeout 증가")
            _add(tags, "sink dependency 연결 실패 또는 connection timeout")
        else:
            _add(tags, "pipeline extract/read 단계 timeout log")

    if _has_signal(pieces, _READ_LATENCY_RE, "latency"):
        _add(tags, "source read latency 증가")
        _add(tags, "extract task duration 증가")
    if _has_signal(pieces, _WRITE_LATENCY_RE, "latency"):
        _add(tags, "sink write latency 증가")
    if "source_healthy" in lower or "source 정상" in lower or "upstream normal" in lower:
        _add(tags, "source read 정상")
    if "sink_healthy" in lower or "sink 정상" in lower:
        _add(tags, "sink write 단계 정상")
    if "credential_rotation" in lower or "secret rotation" in lower or "rotate" in lower or "credential rotation" in lower:
        _add(tags, "credential rotation 또는 secret 변경 이력")

    if lag_spike:
        _add(tags, "consumer lag 급증")
    if offset_slow:
        _add(tags, "offset progression 둔화")
    if _has_signal(pieces, _INGRESS_RE, "ingress"):
        _add(tags, "topic ingress rate 급증")

    if retry:
        _add(tags, "retry count exhausted")
        if failed or "repeat" in lower or "반복" in lower:
            _add(tags, "동일 task 반복 실패")

    return "; ".join(tags)


def _flatten_text(value: Any, context: str = "") -> Iterable[str]:
    if value is None:
        return
    if isinstance(value, str):
        stripped = value.strip()
        if stripped and not is_control_id(stripped):
            yield f"{context} {stripped}".strip()
        return
    if isinstance(value, (bool, int, float)):
        yield f"{context} {value}".strip()
        return
    if isinstance(value, dict):
        for key, item in value.items():
            key_text = str(key)
            if is_control_metadata_key(key_text):
                continue
            yield key_text
            next_context = f"{context} {key_text}".strip()
            yield from _flatten_text(item, next_context)
        return
    if isinstance(value, (list, tuple, set)):
        for item in value:
            yield from _flatten_text(item, context)


def _structured_fault_side(value: Any) -> str | None:
    sides: list[str] = []
    for item in _walk(value):
        if not isinstance(item, dict) or not _has_structured_fault_indicator(item):
            continue
        side = _side_from_mapping(item)
        if side and side not in sides:
            sides.append(side)
    return sides[0] if len(sides) == 1 else None


def _has_structured_fault_indicator(item: dict[Any, Any]) -> bool:
    for key, raw in item.items():
        key_text = _key_name(key)
        raw_text = str(raw).casefold()
        if key_text in {"state", "connectorstate", "taskstate", "status"} and raw_text == "failed":
            return True
        if key_text == "connectionstatus" and raw_text in {"down", "failed"}:
            return True
        if key_text in {"failedtasks", "tasksfailed", "errorcount", "matchcount"} and _positive_number(raw):
            return True
        if key_text == "exitcode" and _nonzero_number(raw):
            return True
        if key_text in {"trace", "stack", "stacktrace"} and str(raw or "").strip():
            return True

    text = " ".join(_flatten_text(item))
    if _is_normal_only(text):
        return False
    return bool(
        _TASK_FAILURE_RE.search(text)
        or _AUTH_RE.search(text)
        or _SCHEMA_RE.search(text)
        or _CONFIG_RE.search(text)
        or _CONSTRAINT_RE.search(text)
        or _TIMEOUT_RE.search(text)
        or _NETWORK_RE.search(text)
        or _RETRY_RE.search(text)
        or _DATABASE_DOES_NOT_EXIST_RE.search(text)
    )


def _side_from_mapping(item: dict[Any, Any]) -> str | None:
    for key, raw in item.items():
        if _key_name(key) not in {"datasourcerole", "role", "type", "kind"}:
            continue
        value = str(raw or "").casefold()
        if value in {"source", "sink"}:
            return value
    return None


def _walk(value: Any) -> Iterable[Any]:
    yield value
    if isinstance(value, dict):
        for item in value.values():
            yield from _walk(item)
    elif isinstance(value, (list, tuple, set)):
        for item in value:
            yield from _walk(item)


def _key_name(value: Any) -> str:
    return str(value).casefold().replace("_", "").replace("-", "")


def _side_hint(text: str, tool_name: str) -> str:
    has_source = bool(_SOURCE_HINT_RE.search(text))
    has_sink = bool(_SINK_HINT_RE.search(text))
    if has_source and not has_sink:
        return "source"
    if has_sink and not has_source:
        return "sink"
    if tool_name in {"get_consumer_lag", "get_consumer_groups"}:
        return "sink"
    return "unknown"


def _auth_side_hint(pieces: list[str], tool_name: str) -> str:
    sides: set[str] = set()
    for piece in pieces:
        observed = _without_normal_fragments(piece, "auth", "failure")
        if not _AUTH_RE.search(observed) or _is_normal_only(observed):
            continue
        for match in _AUTH_RE.finditer(observed):
            side = _nearest_side_for_match(observed, match.start(), tool_name)
            if side in {"source", "sink"}:
                sides.add(side)
    if len(sides) == 1:
        return next(iter(sides))
    return "unknown"


def _nearest_side_for_match(piece: str, index: int, tool_name: str) -> str:
    candidates: list[tuple[int, str]] = []
    for match in _SOURCE_HINT_RE.finditer(piece):
        candidates.append((abs(match.start() - index), "source"))
    for match in _SINK_HINT_RE.finditer(piece):
        candidates.append((abs(match.start() - index), "sink"))
    if not candidates:
        return "sink" if tool_name in {"get_consumer_lag", "get_consumer_groups"} else "unknown"
    candidates.sort(key=lambda item: item[0])
    nearest_distance, nearest_side = candidates[0]
    tied_sides = {side for distance, side in candidates if distance == nearest_distance}
    return nearest_side if len(tied_sides) == 1 else "unknown"


def _is_normal_only(text: str) -> bool:
    fault_text = _without_normal_fragments(text, *_NEGATED_SIGNAL_PATTERNS.keys())
    fault_observed = bool(
        _FAILED_RE.search(fault_text)
        or _SCHEMA_RE.search(fault_text)
        or _CONFIG_RE.search(fault_text)
        or _CONSTRAINT_RE.search(fault_text)
        or _TIMEOUT_RE.search(fault_text)
        or _NETWORK_RE.search(fault_text)
        or _LAG_RE.search(fault_text)
        or _DEGRADATION_RE.search(fault_text)
    )
    return _has_normal_fragment(text) and not fault_observed


def _has_auth_negation(text: str) -> bool:
    return _has_fault_negation(text, "auth")


def _has_signal(pieces: list[str], pattern: re.Pattern[str], fault: str) -> bool:
    for piece in pieces:
        observed = _without_normal_fragments(piece, "failure", fault)
        if pattern.search(observed):
            return True
    return False


def _without_normal_fragments(text: str, *faults: str) -> str:
    cleaned = text
    seen: set[str] = set()
    ordered_faults = [fault for fault in faults if fault not in {"global", "failure"}]
    ordered_faults.append("global")
    if "failure" in faults:
        ordered_faults.append("failure")
    for fault in ordered_faults:
        if fault in seen:
            continue
        seen.add(fault)
        for pattern in _NEGATED_SIGNAL_PATTERNS.get(fault, ()):
            cleaned = pattern.sub(" ", cleaned)
    return cleaned


def _has_normal_fragment(text: str) -> bool:
    return bool(_NORMAL_RE.search(text)) or any(
        pattern.search(text)
        for patterns in _NEGATED_SIGNAL_PATTERNS.values()
        for pattern in patterns
    )


def _has_fault_negation(text: str, fault: str) -> bool:
    return any(pattern.search(text) for pattern in _NEGATED_SIGNAL_PATTERNS.get(fault, ()))


def _add(tags: list[str], tag: str) -> None:
    if tag not in tags:
        tags.append(tag)


def _positive_number(value: Any) -> bool:
    try:
        return float(value) > 0
    except (TypeError, ValueError):
        return False


def _nonzero_number(value: Any) -> bool:
    try:
        return float(value) != 0
    except (TypeError, ValueError):
        return False
