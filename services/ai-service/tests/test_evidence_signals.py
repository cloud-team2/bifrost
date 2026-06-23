from __future__ import annotations

import pytest

from app.catalogs.root_causes import root_cause_ids
from app.evidence.signals import evidence_signal_summary


def test_signal_summary_extracts_catalog_signals_without_raw_secrets() -> None:
    payload = {
        "operation": "get_connector_task_trace",
        "result": {
            "connector": "orders-sink",
            "traces": [
                {
                    "taskId": 0,
                    "state": "FAILED",
                    "trace": "sink authentication failed: token expired for password=secret",
                }
            ],
        },
    }

    summary = evidence_signal_summary("get_connector_task_trace", payload)

    assert "connector task status FAILED" in summary
    assert "task trace 또는 worker log" in summary
    assert "sink auth/permission error log" in summary
    assert "secret" not in summary
    assert "orders-sink" not in summary


def test_signal_summary_ignores_control_metadata_labels() -> None:
    payload = {
        "case_id": "C001",
        "expected_root_cause": "SINK_AUTH_EXPIRED",
        "result": {"message": "no auth error; connector status RUNNING"},
    }

    summary = evidence_signal_summary("get_connector_task_trace", payload)

    assert "SINK_AUTH_EXPIRED" not in summary
    assert "auth/permission error log" not in summary


def test_signal_summary_strips_control_metadata_inside_strings() -> None:
    payload = {
        "message": (
            "accepted_root_cause_id=SINK_AUTH_EXPIRED "
            "corrected_root_cause_id=SOURCE_AUTH_EXPIRED connector status RUNNING"
        )
    }

    summary = evidence_signal_summary("get_connector_task_trace", payload)

    assert summary == ""


def test_signal_summary_keeps_fault_when_normal_status_is_mixed() -> None:
    payload = {
        "sink": {"status": "normal"},
        "logs": ["deserialization error: incompatible schema"],
    }

    summary = evidence_signal_summary("search_logs", payload)

    assert "serialization/deserialization/schema error" in summary


def test_signal_summary_scopes_auth_to_fault_fragment_not_benign_source_status() -> None:
    payload = {
        "source": {"status": "normal"},
        "sink": {"trace": "authentication failed token expired"},
    }

    summary = evidence_signal_summary("get_connector_task_trace", payload)

    assert "sink auth/permission error log" in summary
    assert "source auth/permission error log" not in summary


def test_signal_summary_uses_nearest_auth_scope_in_single_text() -> None:
    payload = {"message": "source status normal; sink authentication failed token expired"}

    summary = evidence_signal_summary("get_connector_task_trace", payload)

    assert "sink auth/permission error log" in summary
    assert "source auth/permission error log" not in summary


def test_signal_summary_keeps_auth_fault_when_normal_auth_status_is_mixed() -> None:
    payload = {"message": "source auth status normal. sink authentication failed token expired"}

    summary = evidence_signal_summary("get_connector_task_trace", payload)

    assert "sink auth/permission error log" in summary
    assert "source auth/permission error log" not in summary


def test_signal_summary_does_not_synthesize_deployment_regression_from_single_observation() -> None:
    payload = {"deployment": {"image": "orders-worker:v2", "status": "rolled out"}}

    summary = evidence_signal_summary("get_deployments", payload)

    assert "image rollout 이후 error/latency/restart 증가" not in summary
    assert "image version update" not in summary


def test_signal_summary_requires_temporal_deployment_degradation() -> None:
    payload = {"message": "new image failure"}

    summary = evidence_signal_summary("get_deployments", payload)

    assert "image rollout 이후 error/latency/restart 증가" not in summary


def test_signal_summary_requires_structured_image_change_for_version_signal() -> None:
    payload = {"deployment": {"image": "orders-worker", "status": "image pull error"}}

    summary = evidence_signal_summary("get_deployments", payload)

    assert "image version update" not in summary


def test_signal_summary_does_not_synthesize_no_fault_from_single_normal_observation() -> None:
    payload = {
        "openIncidents": 0,
        "connectors": [{"name": "orders", "state": "RUNNING"}],
        "summary": "no open incidents; all connectors running; no error logs",
    }

    summary = evidence_signal_summary("analyze_event_log", payload)

    assert summary == ""
    assert "NO_FAULT" not in root_cause_ids()


@pytest.mark.parametrize(
    "message",
    [
        "스키마 오류 없음 connector status RUNNING",
        "schema registry subject unchanged; schema valid; no schema error",
        "설정 오류 없음",
        "config valid; no config error",
        "중복 레코드 없음",
        "duplicate count 0; no duplicate records",
        "lag 정상",
        "consumer lag within threshold; offset progression normal",
        "connector status RUNNING; no failed task",
        "healthy valid no error",
    ],
)
def test_signal_summary_does_not_emit_fault_signals_for_normal_negative_observations(
    message: str,
) -> None:
    summary = evidence_signal_summary("get_connector_task_trace", {"message": message})

    assert summary == ""


def test_signal_summary_keeps_fault_when_negative_and_fault_fragments_are_distinct() -> None:
    payload = {
        "status": "스키마 오류 없음",
        "logs": ["deserialization error: incompatible schema"],
    }

    summary = evidence_signal_summary("search_logs", payload)

    assert "serialization/deserialization/schema error" in summary


@pytest.mark.parametrize(
    "payload",
    [
        {"datasource": {"role": "source", "connectionStatus": "UP", "port": 2002}},
        {"metric": {"name": "records_processed", "value": 2002}},
        {"searchResult": {"matchCount": 1045, "logs": []}},
        {"summary": {"total": 23000, "taskId": 4025, "count": 1366, "year": 22007}},
        {"message": "codes observed in inventory text: 1045 2002 1366 22007 4025 23000"},
    ],
)
def test_vendor_numeric_codes_in_metadata_or_code_only_text_do_not_emit_signals(payload: dict) -> None:
    summary = evidence_signal_summary("list_datasources", payload)

    assert summary == ""


@pytest.mark.parametrize(
    "payload",
    [
        {
            "datasources": [
                {
                    "connectionStatus": "DOWN",
                    "lastError": "ERROR 2002 (HY000): Can't connect to server on '10.255.255.1' (110)",
                }
            ]
        },
        {
            "datasources": [
                {
                    "role": "source+sink",
                    "connectionStatus": "DOWN",
                    "lastError": "ERROR 2002 (HY000): Can't connect to server on '10.255.255.1' (110)",
                }
            ]
        },
    ],
)
def test_unknown_or_dual_role_timeout_evidence_stays_side_neutral(payload: dict) -> None:
    summary = evidence_signal_summary("list_datasources", payload)

    assert "connection timeout log" in summary
    assert "endpoint reachability failure log" in summary
    assert "Bifrost에서 source endpoint reachability 실패" not in summary
    assert "pipeline extract/read 단계 timeout log" not in summary
    assert "source connection timeout 증가" not in summary
    assert "sink write timeout 증가" not in summary
    assert "sink dependency 연결 실패 또는 connection timeout" not in summary


def test_vendor_auth_code_uses_datasource_role_for_sink_auth() -> None:
    payload = {
        "logs": [
            {
                "datasourceRole": "sink",
                "engine": "mariadb",
                "exit_code": 1,
                "stderr": "ERROR 1045 (28000): Access denied for user '[USER]'@'localhost' (using password: YES)",
            }
        ]
    }

    summary = evidence_signal_summary("search_logs", payload)

    assert "sink auth/permission error log" in summary
    assert "source auth/permission error log" not in summary


def test_vendor_timeout_codes_use_failed_item_side_for_source_and_sink() -> None:
    source = evidence_signal_summary(
        "search_logs",
        {
            "logs": [
                {
                    "datasourceRole": "source",
                    "engine": "postgresql",
                    "exit_code": 2,
                    "stderr": 'psql: error: connection to server at "10.255.255.1", port 5432 failed: timeout expired',
                }
            ]
        },
    )
    sink = evidence_signal_summary(
        "search_logs",
        {
            "logs": [
                {
                    "datasourceRole": "sink",
                    "engine": "mariadb",
                    "exit_code": 1,
                    "stderr": "ERROR 2002 (HY000): Can't connect to server on '10.255.255.1' (110)",
                }
            ]
        },
    )

    assert "source connection timeout 증가" in source
    assert "pipeline extract/read 단계 timeout log" in source
    assert "sink write timeout 증가" in sink
    assert "sink dependency 연결 실패 또는 connection timeout" in sink


def test_mixed_datasource_payload_uses_failed_item_side_for_sink_timeout() -> None:
    summary = evidence_signal_summary(
        "list_datasources",
        {
            "datasources": [
                {
                    "id": "source-db",
                    "role": "source",
                    "connectionStatus": "UP",
                },
                {
                    "id": "sink-db",
                    "role": "sink",
                    "connectionStatus": "DOWN",
                    "lastError": "ERROR 2002 (HY000): Can't connect to server on '10.255.255.1' (110)",
                },
            ]
        },
    )

    assert "sink write timeout 증가" in summary
    assert "sink dependency 연결 실패 또는 connection timeout" in summary
    assert "Bifrost에서 source endpoint reachability 실패" not in summary
    assert "source connection timeout 증가" not in summary


def test_missing_database_validation_emits_connector_failure_signal() -> None:
    summary = evidence_signal_summary(
        "search_logs",
        {
            "logs": [
                {
                    "datasourceRole": "source",
                    "engine": "postgresql",
                    "exit_code": 2,
                    "stderr": 'psql: error: FATAL: database "missingdb" does not exist',
                }
            ]
        },
    )

    assert "connector task status FAILED" in summary
    assert "task trace 또는 worker log" in summary
    assert "config validation error 또는 invalid option log" not in summary


def test_vendor_schema_and_constraint_codes_emit_specific_catalog_signals() -> None:
    schema = evidence_signal_summary(
        "search_logs",
        {"logs": [{"stderr": "ERROR 1366 (22007): Incorrect integer value: 'not-a-number' for column amount"}]},
    )
    constraint = evidence_signal_summary(
        "search_logs",
        {"logs": [{"stderr": "ERROR 4025 (23000): CONSTRAINT `chk_orders` failed for `testdb`.`orders`"}]},
    )

    assert "serialization/deserialization/schema error" in schema
    assert "데이터 샘플 구조 변화" in schema
    assert "sink constraint 또는 duplicate key error" in constraint
    assert "동일 record 반복 실패" in constraint


def test_vendor_datetime_parse_failure_emits_schema_signal() -> None:
    summary = evidence_signal_summary(
        "search_logs",
        {
            "logs": [
                {
                    "engine": "postgresql",
                    "exit_code": 1,
                    "stderr": 'ERROR: invalid input syntax for type timestamp: "not-a-timestamp"',
                }
            ]
        },
    )

    assert "serialization/deserialization/schema error" in summary
    assert "데이터 샘플 구조 변화" in summary
    assert "config validation error 또는 invalid option log" not in summary
