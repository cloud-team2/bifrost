"""Spring 레거시 GlobalException 에러 응답 → SpringErrorCode 매핑 회귀 (#643).

Spring 파이프라인 컨트롤러는 존재하지 않는 project 에 대해 표준 OpsEnvelope
({ok, operation, error{code,message}}) 가 아니라 레거시 GlobalException 형식
{"code":"20003","message":"프로젝트를 찾을 수 없습니다: ...","details":[]} 를 404 로 반환한다.
이 dict 는 envelope 필드가 없어 SpringOpsResponse.model_validate 가 ValidationError →
코드가 이를 VALIDATION_FAILED("Invalid Spring response envelope") 로 오변환했다.
본 테스트는 레거시 에러가 적절한 SpringErrorCode 로 매핑되고, 정상 envelope·non-JSON
경로가 회귀 없는지 보호한다.
"""
from __future__ import annotations

from app.schemas.tools import SpringErrorCode
from app.tools.spring_client import SpringOpsClient


class _FakeResponse:
    def __init__(self, payload, status_code=200, *, raise_json=False):
        self._payload = payload
        self.status_code = status_code
        self._raise_json = raise_json

    def json(self):
        if self._raise_json:
            raise ValueError("not json")
        return self._payload


def test_legacy_project_not_found_maps_to_resource_not_found():
    """레거시 {"code":"20003",...} 404 → RESOURCE_NOT_FOUND, message 보존."""
    client = SpringOpsClient()
    legacy_body = {
        "code": "20003",
        "message": "프로젝트를 찾을 수 없습니다: 00000000-0000-0000-0000-000000000000",
        "details": [],
    }
    envelope = client._parse_response(
        _FakeResponse(legacy_body, status_code=404),
        operation="list_project_pipelines",
        request_id="req_legacy_001",
    )
    assert envelope.ok is False
    assert envelope.operation == "list_project_pipelines"
    assert envelope.request_id == "req_legacy_001"
    assert envelope.error is not None
    assert envelope.error.code == SpringErrorCode.RESOURCE_NOT_FOUND
    assert envelope.error.message == legacy_body["message"]  # message 보존
    assert envelope.error.retryable is False


def test_legacy_unknown_code_falls_back_to_http_status_404():
    """미지의 code + 404 → RESOURCE_NOT_FOUND 폴백."""
    client = SpringOpsClient()
    envelope = client._parse_response(
        _FakeResponse({"code": "29999", "message": "unknown not found", "details": []}, status_code=404),
        operation="get_pipeline_topology",
        request_id="req_legacy_002",
    )
    assert envelope.ok is False
    assert envelope.error is not None
    assert envelope.error.code == SpringErrorCode.RESOURCE_NOT_FOUND
    assert envelope.error.message == "unknown not found"
    assert envelope.error.retryable is False


def test_legacy_unknown_code_falls_back_to_http_status_400():
    """미지의 code + 400 → VALIDATION_FAILED 폴백."""
    client = SpringOpsClient()
    envelope = client._parse_response(
        _FakeResponse({"code": "10001", "message": "bad request"}, status_code=400),
        operation="get_consumer_lag",
        request_id="req_legacy_003",
    )
    assert envelope.error is not None
    assert envelope.error.code == SpringErrorCode.VALIDATION_FAILED
    assert envelope.error.retryable is False


def test_legacy_unknown_code_falls_back_to_http_status_403():
    """미지의 code + 403 → PERMISSION_DENIED 폴백."""
    client = SpringOpsClient()
    envelope = client._parse_response(
        _FakeResponse({"code": "10100", "message": "forbidden"}, status_code=403),
        operation="get_connector_status",
        request_id="req_legacy_004",
    )
    assert envelope.error is not None
    assert envelope.error.code == SpringErrorCode.PERMISSION_DENIED


def test_legacy_unknown_code_falls_back_to_http_status_5xx_retryable():
    """미지의 code + 5xx → UPSTREAM_UNAVAILABLE (retryable=True)."""
    client = SpringOpsClient()
    envelope = client._parse_response(
        _FakeResponse({"code": "50000", "message": "upstream boom"}, status_code=503),
        operation="get_connector_status",
        request_id="req_legacy_005",
    )
    assert envelope.error is not None
    assert envelope.error.code == SpringErrorCode.UPSTREAM_UNAVAILABLE
    assert envelope.error.retryable is True


def test_legacy_unknown_code_other_status_internal_error():
    """미지의 code + 분류 외 status(예: 200) → INTERNAL_ERROR 폴백."""
    client = SpringOpsClient()
    envelope = client._parse_response(
        _FakeResponse({"code": "30000", "message": "weird"}, status_code=418),
        operation="get_metrics",
        request_id="req_legacy_006",
    )
    assert envelope.error is not None
    assert envelope.error.code == SpringErrorCode.INTERNAL_ERROR


def test_normal_envelope_not_treated_as_legacy_error():
    """정상 envelope 은 회귀 없이 통과 (ok=True 보존)."""
    client = SpringOpsClient()
    envelope = client._parse_response(
        _FakeResponse(
            {
                "ok": True,
                "request_id": "req_ok",
                "operation": "get_consumer_lag",
                "result": {"consumerGroup": "g", "totalLag": 1},
            },
            status_code=200,
        ),
        operation="get_consumer_lag",
        request_id="req_ok",
    )
    assert envelope.ok is True
    assert envelope.error is None
    assert envelope.result == {"consumerGroup": "g", "totalLag": 1}


def test_standard_error_envelope_not_treated_as_legacy():
    """표준 error envelope(ok=False + error{}) 은 레거시 분기 타지 않고 그대로 검증."""
    client = SpringOpsClient()
    envelope = client._parse_response(
        _FakeResponse(
            {
                "ok": False,
                "request_id": "req_std_err",
                "operation": "restart_connector",
                "error": {"code": "POLICY_DENIED", "message": "blocked"},
            },
            status_code=403,
        ),
        operation="restart_connector",
        request_id="req_std_err",
    )
    assert envelope.ok is False
    assert envelope.error is not None
    assert envelope.error.code == SpringErrorCode.POLICY_DENIED
    assert envelope.error.message == "blocked"


def test_non_json_response_regression():
    """non-JSON 응답은 기존대로 INTERNAL_ERROR 로 처리 (회귀 없음)."""
    client = SpringOpsClient()
    envelope = client._parse_response(
        _FakeResponse(None, status_code=502, raise_json=True),
        operation="get_metrics",
        request_id="req_nonjson",
    )
    assert envelope.ok is False
    assert envelope.error is not None
    assert envelope.error.code == SpringErrorCode.INTERNAL_ERROR
    assert envelope.error.retryable is True  # status >= 500
