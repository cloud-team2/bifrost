"""Spring /internal/ops/* 응답 ↔ ai-service 모델 contract 회귀 (#390).

라이브 audit (bifrost-test-results-and-todo.md A-3·A-4·D-1) 에서 ai-service `StrictModel(extra="forbid")` +
alias_generator 부재가 Spring camelCase 응답을 매번 ValidationError 로 거부 → tool_call_failed →
run fail → SSE payload 0 의 root cause 임이 확인됨. 본 테스트는 Spring 실응답 sample (Java DTO 기준
camelCase) 가 ai-service 모델로 정상 파싱되는지 회귀 보호한다.
"""
from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.schemas.tools import (
    ConsumerLagData,
    ListProjectPipelinesData,
    LogSearchData,
    LogSearchRequest,
    SpringOpsResponse,
    StrictModel,
)
from app.tools.spring_client import SpringOpsClient


def test_consumer_lag_camelcase_spring_response():
    """Spring ConsumerLagResult{consumerGroup, totalLag, source} → ConsumerLagData."""
    spring_payload = {
        "ok": True,
        "request_id": "req_001",
        "operation": "get_consumer_lag",
        "result": {
            "consumerGroup": "test-group",
            "totalLag": 12345,
            "source": "kafka-admin",
        },
    }
    envelope = SpringOpsResponse.model_validate(spring_payload)
    assert envelope.ok
    assert envelope.result is not None and not isinstance(envelope.result, list)

    data = ConsumerLagData.model_validate(envelope.result)
    assert data.consumer_group == "test-group"
    assert data.total_lag == 12345
    assert data.partitions == []  # 누락 시 default
    assert data.observed_at is None
    assert data.source == "kafka-admin"  # Spring 무관 필드 명시 수용


def test_consumer_lag_snake_case_also_supported():
    """snake_case 입력도 populate_by_name=True 로 그대로 수용 (기존 테스트 호환)."""
    data = ConsumerLagData.model_validate({
        "consumer_group": "g1",
        "total_lag": 99,
    })
    assert data.consumer_group == "g1"
    assert data.total_lag == 99


def test_consumer_lag_extra_field_ignored_not_forbidden():
    """SpringResponseModel 의 extra='ignore' — 무관 필드는 거부 안 함."""
    data = ConsumerLagData.model_validate({
        "consumerGroup": "g",
        "totalLag": 1,
        "unknownField": "should be ignored",
    })
    assert data.consumer_group == "g"


def test_search_logs_spring_native_response_normalize():
    """Spring LogSearchResult{logs, total, note} → LogSearchData (match_count/summary normalize)."""
    spring_payload = {
        "logs": [{"line": "ERROR connection timeout"}],
        "total": 5,
        "note": "5 occurrences of consumer lag p95 increase",
    }
    data = LogSearchData.model_validate(spring_payload)
    assert data.match_count == 5  # total → match_count normalize
    assert data.summary == "5 occurrences of consumer lag p95 increase"  # note → summary normalize
    assert data.logs == [{"line": "ERROR connection timeout"}]


def test_search_logs_explicit_match_count_wins():
    """match_count 가 명시되면 total normalize 가 덮어쓰지 않음."""
    data = LogSearchData.model_validate({
        "match_count": 10,
        "summary": "explicit summary",
        "total": 5,
        "note": "should not override",
    })
    assert data.match_count == 10
    assert data.summary == "explicit summary"


def test_list_project_pipelines_raw_list_wrap_via_client():
    """spring_client 의 LIST_RESULT_WRAPPER 가 raw list → {pipelines:[...]} wrap."""
    client = SpringOpsClient()
    # Spring 의 list_project_pipelines 가 raw list 를 result 로 반환
    raw_spring_payload = {
        "ok": True,
        "request_id": "req_002",
        "operation": "list_project_pipelines",
        "result": [],  # raw list
    }

    class _FakeResponse:
        status_code = 200

        def json(self):
            return raw_spring_payload

    envelope = client._parse_response(_FakeResponse(), operation="list_project_pipelines", request_id="req_002")
    assert envelope.ok
    assert isinstance(envelope.result, dict)
    assert envelope.result == {"pipelines": []}

    data = ListProjectPipelinesData.model_validate(envelope.result)
    assert data.pipelines == []


def test_strict_model_still_forbids_extra():
    """기존 StrictModel 의 extra='forbid' 보호 정책 회귀."""
    with pytest.raises(ValidationError):
        LogSearchRequest.model_validate({
            "query": "x",
            "time_range": {"from": "a", "to": "b"},
            "unknown_field": "rejected",
        })


def test_spring_ops_response_result_accepts_list_type():
    """SpringOpsResponse.result 가 dict | list | None 모두 수용."""
    # dict
    e1 = SpringOpsResponse.model_validate({
        "ok": True, "request_id": "r1", "operation": "op1", "result": {"k": "v"},
    })
    assert isinstance(e1.result, dict)
    # list
    e2 = SpringOpsResponse.model_validate({
        "ok": True, "request_id": "r2", "operation": "op2", "result": [1, 2, 3],
    })
    assert isinstance(e2.result, list)
    # None
    e3 = SpringOpsResponse.model_validate({
        "ok": True, "request_id": "r3", "operation": "op3",
    })
    assert e3.result is None
