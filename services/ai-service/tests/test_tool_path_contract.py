"""Tool 요청 경로 ↔ Spring internal-ops 카탈로그 계약 전수 검증 (#633).

기존 test_tools_contract_drift 는 *응답 스키마* 만 검증했고 요청 URL 은 MockTransport 가
URL 무관하게 응답해 경로 드리프트(get_incident_summary 가 비-project-scoped 라 Spring 이
VALIDATION_FAILED 로 거부 → 에이전트가 인시던트 맥락 못 받음)를 못 잡았다.

본 테스트는 레지스트리의 모든 도구 (method, 정규화 경로) 가 백엔드가 광고하는
InternalOpsController tool-catalog 에 존재하는지 전수 대조한다.

SSOT = services/operations-backend/.../internalops/controller/InternalOpsController.java
       (`@GetMapping("/admin/tool-catalog")` 의 tool(...) 목록). 아래 BACKEND_CATALOG 는
       그 목록을 {placeholder}->{} 정규화한 스냅샷이며, Java 카탈로그가 바뀌면 함께 갱신한다.
"""
from __future__ import annotations

import re

from app.tools.registry import default_tool_definitions

# InternalOpsController tool-catalog 스냅샷 (method, 정규화 경로). 경로 placeholder 는 {} 로 통일.
BACKEND_CATALOG: set[tuple[str, str]] = {
    ("GET", "/internal/ops/projects/{}/kafka/consumer-groups/{}/lag"),
    ("GET", "/internal/ops/projects/{}/kafka/consumer-groups"),
    ("POST", "/internal/ops/projects/{}/observability/logs/search"),
    ("GET", "/internal/ops/projects/{}/observability/metrics"),
    ("GET", "/internal/ops/projects/{}/connectors/{}/traces"),
    ("GET", "/internal/ops/projects/{}/connectors/{}/task-trace"),
    ("GET", "/internal/ops/projects/{}/observability/alerts"),
    ("GET", "/internal/ops/projects/{}/observability/events/summary"),
    ("GET", "/internal/ops/projects/{}/incidents/{}/summary"),
    ("GET", "/internal/ops/projects/{}/pipelines"),
    ("GET", "/internal/ops/projects/{}/pipelines/status"),
    ("GET", "/internal/ops/projects/{}/pipelines/changes"),
    ("GET", "/internal/ops/projects/{}/pipelines/{}/topology"),
    ("GET", "/internal/ops/projects/{}/kafka/connectors/{}/status"),
    ("GET", "/internal/ops/projects/{}/kafka/connectors/status"),
    ("POST", "/internal/ops/projects/{}/connectors/{}/restart"),
    ("POST", "/internal/ops/projects/{}/connectors/{}/pause"),
    ("POST", "/internal/ops/projects/{}/connectors/{}/resume"),
    ("POST", "/internal/ops/projects/{}/kafka/consumer-groups/{}/restart"),
}


def _normalize(path: str) -> str:
    return re.sub(r"\{[^}]+\}", "{}", path)


def test_every_registry_tool_path_exists_in_backend_catalog():
    """레지스트리의 모든 도구 경로가 백엔드 광고 카탈로그에 존재해야 한다(드리프트 차단)."""
    missing = []
    for name, definition in default_tool_definitions().items():
        key = (definition.method.upper(), _normalize(definition.path_template))
        if key not in BACKEND_CATALOG:
            missing.append((name, definition.method, definition.path_template))
    assert not missing, (
        "백엔드 카탈로그에 없는 도구 경로(계약 드리프트). "
        "InternalOpsController 와 registry 를 정합시키세요: " + repr(missing)
    )


def test_get_incident_summary_is_project_scoped():
    """회귀: get_incident_summary 는 project-scoped 경로여야 한다(#633 드리프트 재발 방지)."""
    definition = default_tool_definitions()["get_incident_summary"]
    assert definition.path_template == (
        "/internal/ops/projects/{project_id}/incidents/{incident_id}/summary"
    )


def test_all_read_write_tools_are_project_scoped():
    """모든 internal-ops 도구는 project-scoped(/projects/{project_id}/) 여야 한다."""
    non_scoped = [
        name
        for name, d in default_tool_definitions().items()
        if "/internal/ops/projects/{project_id}/" not in d.path_template
    ]
    assert not non_scoped, f"project-scope 누락 도구: {non_scoped}"
