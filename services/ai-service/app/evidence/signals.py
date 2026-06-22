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
    r"access\s*denied|permission\s*denied|unauthori[sz]ed|authentication|"
    r"\bauth\b|auth(?:entication)?\s*error|token\s*expired|credential\s*expired|"
    r"password authentication failed|invalid password|invalid credential|"
    r"credential invalid|credential expired|sasl|인증\s*실패|권한\s*거부|"
    r"credential\s*만료|credential\s*미적용|토큰\s*만료",
    re.IGNORECASE,
)
_SCHEMA_RE = re.compile(
    r"schema|serialization|deserialization|incompatible|compatibility|converter|"
    r"avro|serde|type mismatch|schema registry|스키마|역직렬화|호환성",
    re.IGNORECASE,
)
_CONFIG_RE = re.compile(
    r"invalid config|unknown config|config validation|configuration error|"
    r"invalid option|required configuration|bad config|config diff|"
    r"config\s*변경|설정\s*오류|설정\s*오타|잘못된\s*설정",
    re.IGNORECASE,
)
_CONSTRAINT_RE = re.compile(
    r"duplicate key|unique constraint|constraint violation|foreign key|not null|"
    r"data integrity|sqlintegrity|record rejected|duplicate records?|"
    r"중복\s*레코드|제약\s*위반|"
    r"(?:record|row|sink|data)\s+validation fail",
    re.IGNORECASE,
)
_TIMEOUT_RE = re.compile(r"timeout|timed out|read timed out|write timed out|타임아웃", re.IGNORECASE)
_NETWORK_RE = re.compile(
    r"dns|unknownhost|no route|network unreachable|connection refused|"
    r"connect exception|tcp connect|endpoint unreachable|host unreachable|i/o error|"
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
    r"no error logs|normal|healthy|정상|없음",
    re.IGNORECASE,
)

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
    side = _side_hint(lower, tool_name)
    auth_side = _auth_side_hint(pieces, tool_name)

    failed = bool(_TASK_FAILURE_RE.search(text))
    trace = bool(_TRACE_RE.search(text))
    auth = bool(_AUTH_RE.search(text))
    schema = bool(_SCHEMA_RE.search(text))
    config = bool(_CONFIG_RE.search(text))
    constraint = bool(_CONSTRAINT_RE.search(text))
    timeout = bool(_TIMEOUT_RE.search(text))
    network = bool(_NETWORK_RE.search(text))
    lag = bool(_LAG_RE.search(text))
    deployment = bool(_DEPLOYMENT_RE.search(text))
    retry = bool(_RETRY_RE.search(text))
    idempotency = bool(_IDEMPOTENCY_RE.search(text))

    if failed and "connector" in lower:
        _add(tags, "connector task status FAILED")
    if trace and failed:
        _add(tags, "task trace 또는 worker log")

    if auth and not _is_normal_only(text) and not _has_auth_negation(text):
        if auth_side == "source":
            _add(tags, "source auth/permission error log")
        elif auth_side == "sink":
            _add(tags, "sink auth/permission error log")
        else:
            _add(tags, "auth/permission error log")

    if schema and not _is_normal_only(text):
        _add(tags, "serialization/deserialization/schema error")
    if "schema version" in lower or "subject version" in lower or "schema registry" in lower:
        _add(tags, "schema version 변경 이력")
    if "type mismatch" in lower or "field type" in lower or "필드 타입" in lower:
        _add(tags, "데이터 샘플 구조 변화")

    if config and not _is_normal_only(text):
        _add(tags, "config validation error 또는 invalid option log")
    if "config change" in lower or "config diff" in lower or "config 변경" in lower:
        _add(tags, "최근 pipeline/connector config 변경")

    if (
        deployment
        and _DEGRADATION_RE.search(text)
        and _TEMPORAL_DEPLOYMENT_RE.search(text)
        and not _is_normal_only(text)
    ):
        _add(tags, "image rollout 이후 error/latency/restart 증가")
    if deployment and _DIFF_RE.search(text) and not _is_normal_only(text):
        _add(tags, "image version update")

    if constraint:
        _add(tags, "sink constraint 또는 duplicate key error")
        _add(tags, "동일 record 반복 실패")
    if "duplicate count" in lower or "중복 레코드" in lower:
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

    if "read_latency" in lower or "read latency" in lower or "extract duration" in lower or "extract 단계 p95" in lower:
        _add(tags, "source read latency 증가")
        _add(tags, "extract task duration 증가")
    if "full scan" in lower:
        _add(tags, "source read latency 증가")
    if "write_latency" in lower or "write latency" in lower or "write duration" in lower or "write p95" in lower:
        _add(tags, "sink write latency 증가")
    if "source_healthy" in lower or "source 정상" in lower or "upstream normal" in lower:
        _add(tags, "source read 정상")
    if "sink_healthy" in lower or "sink 정상" in lower:
        _add(tags, "sink write 단계 정상")
    if "credential_rotation" in lower or "secret rotation" in lower or "rotate" in lower or "credential rotation" in lower:
        _add(tags, "credential rotation 또는 secret 변경 이력")

    lag_is_normal = any(token in lower for token in ("consumer lag 정상", "lag within threshold", "lag normal"))
    if lag and not lag_is_normal:
        _add(tags, "consumer lag 급증")
    if "offset progression" in lower or "commit rate" in lower or "offset_progression" in lower:
        _add(tags, "offset progression 둔화")
    if "topic ingress" in lower or "incoming messages" in lower or "bytes-in" in lower:
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
        if _has_auth_negation(piece) or _is_normal_only(piece):
            continue
        for match in _AUTH_RE.finditer(piece):
            side = _nearest_side_for_match(piece, match.start(), tool_name)
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
    fault_text = re.sub(
        r"\bno\s+(?:open\s+)?incidents?\b|\bno\s+error\s+logs?\b|"
        r"\bno\s+(?:schema|serialization|deserialization|auth|authentication|permission|credential|token)\s+"
        r"(?:error|failure|issue)\b",
        " ",
        text,
        flags=re.IGNORECASE,
    )
    fault_observed = bool(
        _FAILED_RE.search(fault_text)
        or _SCHEMA_RE.search(fault_text)
        or _CONSTRAINT_RE.search(fault_text)
        or _TIMEOUT_RE.search(fault_text)
        or _NETWORK_RE.search(fault_text)
        or _DEGRADATION_RE.search(fault_text)
    )
    return bool(_NORMAL_RE.search(text)) and not fault_observed


def _has_auth_negation(text: str) -> bool:
    return bool(
        re.search(
            r"\b(?:no|not|without)\s+(?:\w+\s+){0,3}"
            r"(?:auth|authentication|permission|credential|token)\s+(?:error|failure|issue)\b",
            text,
            re.IGNORECASE,
        )
        or re.search(
            r"\b(?:auth|authentication|credential|token)\s+(?:status\s+)?(?:normal|valid|healthy)\b",
            text,
            re.IGNORECASE,
        )
        or re.search(r"(?:인증|권한|토큰|credential).*(?:없음|정상|유효)", text, re.IGNORECASE)
    )


def _add(tags: list[str], tag: str) -> None:
    if tag not in tags:
        tags.append(tag)
