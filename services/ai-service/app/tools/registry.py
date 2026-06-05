"""Tool Client Registry for Spring Boot `/internal/ops` delegation."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import httpx
from pydantic import BaseModel, ConfigDict, ValidationError

from app.core.config import settings
from app.schemas.state import RiskLevel
from app.schemas.tools import (
    ConnectorStatusData,
    ConsumerLagData,
    IncidentSummaryData,
    ListProjectPipelinesData,
    LogSearchData,
    LogSearchRequest,
    PipelineTopologyData,
    SpringErrorCode,
    ToolContext,
    ToolResult,
    ToolStatus,
)
from app.tools.result import failed_tool_result, result_from_spring_response
from app.tools.spring_client import SpringOpsClient


class ToolParams(BaseModel):
    model_config = ConfigDict(extra="forbid")


class ListProjectPipelinesParams(ToolParams):
    status: str | None = None


class PipelineTopologyParams(ToolParams):
    pipeline_id: str


class ConnectorStatusParams(ToolParams):
    connector_name: str


class ConsumerLagParams(ToolParams):
    consumer_group: str


class IncidentSummaryParams(ToolParams):
    incident_id: str


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
            path_template="/internal/ops/projects/{project_id}/pipelines/{pipeline_id}",
            risk=RiskLevel.READ_ONLY,
            params_model=PipelineTopologyParams,
            result_model=PipelineTopologyData,
            path_params=("pipeline_id",),
        ),
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
            name="search_logs",
            operation="search_logs",
            method="POST",
            path_template="/internal/ops/projects/{project_id}/observability/logs/search",
            risk=RiskLevel.READ_ONLY,
            params_model=LogSearchRequest,
            result_model=LogSearchData,
            sends_body=True,
        ),
        ToolDefinition(
            name="get_incident_summary",
            operation="get_incident_summary",
            method="GET",
            path_template="/internal/ops/projects/{project_id}/incidents/{incident_id}/summary",
            risk=RiskLevel.READ_ONLY,
            params_model=IncidentSummaryParams,
            result_model=IncidentSummaryData,
            path_params=("incident_id",),
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
    ]
    return {definition.name: definition for definition in definitions}


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
        definition = self.get_definition(tool_name)
        if definition is None:
            return failed_tool_result(
                tool_name=tool_name,
                risk=RiskLevel.FORBIDDEN,
                code=SpringErrorCode.POLICY_DENIED,
                message=f"Tool is not registered: {tool_name}",
                status=ToolStatus.BLOCKED,
            )

        try:
            validated_params = definition.validate_params(params)
        except ValidationError as exc:
            return failed_tool_result(
                tool_name=tool_name,
                risk=definition.risk,
                code=SpringErrorCode.VALIDATION_FAILED,
                message=f"Invalid tool parameters: {exc.errors()[0]['msg']}",
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
            return failed_tool_result(
                tool_name=tool_name,
                risk=definition.risk,
                code=SpringErrorCode.TIMEOUT,
                message=str(exc) or "Spring operation timed out",
                status=ToolStatus.TIMEOUT,
                retryable=True,
            )
        except httpx.HTTPError as exc:
            return failed_tool_result(
                tool_name=tool_name,
                risk=definition.risk,
                code=SpringErrorCode.TRANSIENT_ERROR,
                message=str(exc) or "Spring operation failed",
                retryable=True,
            )

        result = result_from_spring_response(
            tool_name=tool_name,
            risk=definition.risk,
            response=response,
            requires_approval=definition.requires_approval,
        )
        if result.status != ToolStatus.SUCCESS:
            return result

        try:
            definition.validate_result(response.result)
        except ValidationError as exc:
            return failed_tool_result(
                tool_name=tool_name,
                risk=definition.risk,
                code=SpringErrorCode.VALIDATION_FAILED,
                message=f"Invalid Spring operation result: {exc.errors()[0]['msg']}",
            )
        return result
