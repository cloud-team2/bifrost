from __future__ import annotations

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
