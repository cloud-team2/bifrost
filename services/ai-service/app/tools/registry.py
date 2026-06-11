"""Tool Client Registry for Spring Boot `/internal/ops` delegation."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import httpx
from pydantic import BaseModel, ConfigDict, ValidationError

from app.core.config import settings
from app.schemas.state import RiskLevel
from app.schemas.tools import (
    AlertsData,
    ConnectorActionData,
    ConnectorStatusData,
    ConnectorTaskTraceData,
    ConsumerGroupActionData,
    ConsumerLagData,
    DeploymentsData,
    GetAlertsParams,
    GetConnectorTaskTraceParams,
    GetTracesParams,
    IncidentSummaryData,
    ListProjectPipelinesData,
    LogSearchData,
    MetricsData,
    PipelineTopologyData,
    SpringErrorCode,
    ToolContext,
    ToolResult,
    ToolStatus,
    TracesData,
)
from app.tools.result import failed_tool_result, result_from_spring_response
from app.tools.spring_client import SpringOpsClient


class ToolParams(BaseModel):
    model_config = ConfigDict(extra="forbid")


class GetPipelineLogsParams(ToolParams):
    query: str
    time_range: dict | None = None
    pipeline_id: str | None = None
    limit: int | None = None


class GetMetricsParams(ToolParams):
    metric: str
    time_range: str | None = None


class GetDeploymentsParams(ToolParams):
    pass


class ConnectorStatusParams(ToolParams):
    connector_name: str


class ConsumerLagParams(ToolParams):
    consumer_group: str


class ListProjectPipelinesParams(ToolParams):
    pass


class PipelineTopologyParams(ToolParams):
    pipeline_id: str


class GetIncidentSummaryParams(ToolParams):
    incident_id: str


class RestartConnectorParams(ToolParams):
    connector_name: str


class PauseConnectorParams(ToolParams):
    connector_name: str


class ResumeConnectorParams(ToolParams):
    connector_name: str


class RestartConsumerGroupParams(ToolParams):
    consumer_group: str


@dataclass(frozen=True)
class ToolDefinition:
    name: str
    operation: str
    method: str
    path_template: str
    risk: RiskLevel
    params_model: type[BaseModel]
    result_model: type[BaseModel]
    path_params: tuple[str, ...] = ()
    sends_body: bool = False
    requires_approval: bool = False
    alias_for: str | None = None

    def validate_params(self, params: dict[str, Any]) -> BaseModel:
        return self.params_model.model_validate(params)

    def build_path(self, context: ToolContext, params: BaseModel) -> str:
        values = params.model_dump(by_alias=True)
        values["project_id"] = context.project_id
        return self.path_template.format(**values)

    def query_params(self, params: BaseModel) -> dict[str, Any] | None:
        if self.sends_body:
            return None
        dumped = params.model_dump(by_alias=True, exclude_none=True)
        query = {key: value for key, value in dumped.items() if key not in self.path_params}
        return query or None

    def json_body(self, params: BaseModel) -> dict[str, Any] | None:
        if not self.sends_body:
            return None
        return params.model_dump(by_alias=True, exclude_none=True)

    def validate_result(self, result: dict[str, Any] | None) -> None:
        self.result_model.model_validate(result or {})


def default_tool_definitions() -> dict[str, ToolDefinition]:
    definitions = [
        # ── catalog §8.1 Observability ──────────────────────────────────────
        ToolDefinition(
            name="search_logs",
            operation="search_logs",
            method="POST",
            path_template="/internal/ops/projects/{project_id}/observability/logs/search",
            risk=RiskLevel.READ_ONLY,
            params_model=GetPipelineLogsParams,
            result_model=LogSearchData,
            sends_body=True,
        ),
        ToolDefinition(
            name="get_metrics",
            operation="query_metrics",
            method="GET",
            path_template="/internal/ops/projects/{project_id}/observability/metrics",
            risk=RiskLevel.READ_ONLY,
            params_model=GetMetricsParams,
            result_model=MetricsData,
        ),
        # ── catalog §8.2 Pipeline / Change ──────────────────────────────────
        ToolDefinition(
            name="get_deployments",
            operation="get_recent_changes",
            method="GET",
            path_template="/internal/ops/projects/{project_id}/pipelines/changes",
            risk=RiskLevel.READ_ONLY,
            params_model=GetDeploymentsParams,
            result_model=DeploymentsData,
        ),
        # ── catalog §8.3 Kafka / Kafka Connect ──────────────────────────────
        ToolDefinition(
            name="get_connector_status",
            operation="get_connector_status",
            method="GET",
            path_template="/internal/ops/projects/{project_id}/kafka/connectors/{connector_name}/status",
            risk=RiskLevel.READ_ONLY,
            params_model=ConnectorStatusParams,
            result_model=ConnectorStatusData,
            path_params=("connector_name",),
        ),
        ToolDefinition(
            name="get_consumer_lag",
            operation="get_consumer_lag",
            method="GET",
            path_template="/internal/ops/projects/{project_id}/kafka/consumer-groups/{consumer_group}/lag",
            risk=RiskLevel.READ_ONLY,
            params_model=ConsumerLagParams,
            result_model=ConsumerLagData,
            path_params=("consumer_group",),
        ),
        ToolDefinition(
            name="get_kafka_lag",
            operation="get_consumer_lag",
            method="GET",
            path_template="/internal/ops/projects/{project_id}/kafka/consumer-groups/{consumer_group}/lag",
            risk=RiskLevel.READ_ONLY,
            params_model=ConsumerLagParams,
            result_model=ConsumerLagData,
            path_params=("consumer_group",),
            alias_for="get_consumer_lag",
        ),
        # ── catalog §8.4 Pipeline read (Spring PR #154) ──────────────────────
        ToolDefinition(
            name="list_project_pipelines",
            operation="list_project_pipelines",
            method="GET",
            path_template="/internal/ops/projects/{project_id}/pipelines",
            risk=RiskLevel.READ_ONLY,
            params_model=ListProjectPipelinesParams,
            result_model=ListProjectPipelinesData,
        ),
        ToolDefinition(
            name="get_pipeline_topology",
            operation="get_pipeline_topology",
            method="GET",
            path_template="/internal/ops/projects/{project_id}/pipelines/{pipeline_id}/topology",
            risk=RiskLevel.READ_ONLY,
            params_model=PipelineTopologyParams,
            result_model=PipelineTopologyData,
            path_params=("pipeline_id",),
        ),
        # ── catalog §8.5 Incident summary (Spring PR #157) ───────────────────
        ToolDefinition(
            name="get_incident_summary",
            operation="get_incident_summary",
            method="GET",
            path_template="/internal/ops/incidents/{incident_id}/summary",
            risk=RiskLevel.READ_ONLY,
            params_model=GetIncidentSummaryParams,
            result_model=IncidentSummaryData,
            path_params=("incident_id",),
        ),
        # ── catalog §8.6 Mutation — Kafka Connect 운영 조치 ──────────────────
        ToolDefinition(
            name="restart_connector",
            operation="restart_connector",
            method="POST",
            path_template="/internal/ops/projects/{project_id}/connectors/{connector_name}/restart",
            risk=RiskLevel.HIGH,
            params_model=RestartConnectorParams,
            result_model=ConnectorActionData,
            path_params=("connector_name",),
            requires_approval=True,
        ),
        ToolDefinition(
            name="pause_connector",
            operation="pause_connector",
            method="POST",
            path_template="/internal/ops/projects/{project_id}/connectors/{connector_name}/pause",
            risk=RiskLevel.MEDIUM,
            params_model=PauseConnectorParams,
            result_model=ConnectorActionData,
            path_params=("connector_name",),
            requires_approval=True,
        ),
        ToolDefinition(
            name="resume_connector",
            operation="resume_connector",
            method="POST",
            path_template="/internal/ops/projects/{project_id}/connectors/{connector_name}/resume",
            risk=RiskLevel.MEDIUM,
            params_model=ResumeConnectorParams,
            result_model=ConnectorActionData,
            path_params=("connector_name",),
            requires_approval=True,
        ),
        ToolDefinition(
            name="restart_consumer_group",
            operation="restart_consumer_group",
            method="POST",
            path_template="/internal/ops/projects/{project_id}/kafka/consumer-groups/{consumer_group}/restart",
            risk=RiskLevel.HIGH,
            params_model=RestartConsumerGroupParams,
            result_model=ConsumerGroupActionData,
            path_params=("consumer_group",),
            requires_approval=True,
        ),
        ToolDefinition(
            name="get_traces",
            operation="query_traces",
            method="GET",
            path_template="/internal/ops/projects/{project_id}/connectors/{connector_name}/traces",
            risk=RiskLevel.READ_ONLY,
            params_model=GetTracesParams,
            result_model=TracesData,
            path_params=("connector_name",),
        ),
        # catalog §8.1 Observability — get_connector_task_trace (operation: get_connector_task_trace, #368/#373)
        # 에러 근거(Connect task 예외 stack trace)를 분산 trace(get_traces, Tempo)와 분리한다.
        ToolDefinition(
            name="get_connector_task_trace",
            operation="get_connector_task_trace",
            method="GET",
            path_template="/internal/ops/projects/{project_id}/connectors/{connector_name}/task-trace",
            risk=RiskLevel.READ_ONLY,
            params_model=GetConnectorTaskTraceParams,
            result_model=ConnectorTaskTraceData,
            path_params=("connector_name",),
        ),
        ToolDefinition(
            name="get_alerts",
            operation="list_alerts",
            method="GET",
            path_template="/internal/ops/projects/{project_id}/observability/alerts",
            risk=RiskLevel.READ_ONLY,
            params_model=GetAlertsParams,
            result_model=AlertsData,
        ),
    ]
    return {definition.name: definition for definition in definitions}


_registry: "ToolClientRegistry | None" = None


def get_tool_registry() -> "ToolClientRegistry":
    global _registry
    if _registry is None:
        _registry = ToolClientRegistry()
    return _registry


class ToolClientRegistry:
    def __init__(
        self,
        *,
        base_url: str | None = None,
        timeout: float | None = None,
        transport: httpx.AsyncBaseTransport | None = None,
        client: SpringOpsClient | None = None,
        definitions: dict[str, ToolDefinition] | None = None,
    ) -> None:
        self._definitions = definitions or default_tool_definitions()
        self._client = client or SpringOpsClient(
            base_url=base_url or settings.spring_ops_base_url,
            timeout=timeout,
            transport=transport,
        )

    def list_tools(self) -> list[ToolDefinition]:
        return list(self._definitions.values())

    def get_definition(self, tool_name: str) -> ToolDefinition | None:
        return self._definitions.get(tool_name)

    async def health(self) -> bool:
        return await self._client.health()

    async def call_tool(
        self,
        tool_name: str,
        params: dict[str, Any],
        context: ToolContext,
    ) -> ToolResult:
        result, _ = await self.call_tool_with_data(tool_name, params, context)
        return result

    async def call_tool_with_data(
        self,
        tool_name: str,
        params: dict[str, Any],
        context: ToolContext,
    ) -> tuple[ToolResult, dict[str, Any] | list[Any] | None]:
        definition = self.get_definition(tool_name)
        if definition is None:
            return (
                failed_tool_result(
                    tool_name=tool_name,
                    risk=RiskLevel.FORBIDDEN,
                    code=SpringErrorCode.POLICY_DENIED,
                    message=f"Tool is not registered: {tool_name}",
                    status=ToolStatus.BLOCKED,
                ),
                None,
            )

        if definition.requires_approval and not context.idempotency_key:
            return (
                failed_tool_result(
                    tool_name=tool_name,
                    risk=definition.risk,
                    code=SpringErrorCode.APPROVAL_REQUIRED,
                    message=f"Tool '{tool_name}' requires approval. 멱등키 없이 직접 호출할 수 없습니다.",
                    status=ToolStatus.BLOCKED,
                ),
                None,
            )

        try:
            validated_params = definition.validate_params(params)
        except ValidationError as exc:
            return (
                failed_tool_result(
                    tool_name=tool_name,
                    risk=definition.risk,
                    code=SpringErrorCode.VALIDATION_FAILED,
                    message=f"Invalid tool parameters: {exc.errors()[0]['msg']}",
                ),
                None,
            )

        try:
            response = await self._client.request(
                method=definition.method,
                path=definition.build_path(context, validated_params),
                operation=definition.operation,
                context=context,
                params=definition.query_params(validated_params),
                json_body=definition.json_body(validated_params),
            )
        except httpx.TimeoutException as exc:
            return (
                failed_tool_result(
                    tool_name=tool_name,
                    risk=definition.risk,
                    code=SpringErrorCode.TIMEOUT,
                    message=str(exc) or "Spring operation timed out",
                    status=ToolStatus.TIMEOUT,
                    retryable=True,
                ),
                None,
            )
        except httpx.HTTPError as exc:
            return (
                failed_tool_result(
                    tool_name=tool_name,
                    risk=definition.risk,
                    code=SpringErrorCode.TRANSIENT_ERROR,
                    message=str(exc) or "Spring operation failed",
                    retryable=True,
                ),
                None,
            )

        result = result_from_spring_response(
            tool_name=tool_name,
            risk=definition.risk,
            response=response,
            requires_approval=definition.requires_approval,
        )
        if result.status != ToolStatus.SUCCESS:
            return result, None

        try:
            definition.validate_result(response.result)
        except ValidationError as exc:
            return (
                failed_tool_result(
                    tool_name=tool_name,
                    risk=definition.risk,
                    code=SpringErrorCode.VALIDATION_FAILED,
                    message=f"Invalid Spring operation result: {exc.errors()[0]['msg']}",
                ),
                None,
            )
        return result, response.result
