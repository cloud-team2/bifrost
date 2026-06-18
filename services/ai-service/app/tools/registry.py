"""Tool Client Registry for Spring Boot `/internal/ops` delegation."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Literal

import httpx
from pydantic import BaseModel, ConfigDict, ValidationError

from app.core.config import settings
from app.schemas.state import RiskLevel
from app.schemas.tools import (
    AlertsData,
    ConnectorActionData,
    ClusterInfoData,
    ConnectorStatusListData,
    DatasourceListData,
    SqlReadData,
    ConnectorStatusData,
    ConnectorTaskTraceData,
    ConsumerGroupActionData,
    ConsumerGroupsData,
    ConsumerLagData,
    DeploymentsData,
    EventIncidentSummaryData,
    GetAlertsParams,
    GetConnectorTaskTraceParams,
    GetTracesParams,
    IncidentSummaryData,
    PipelineStatusListData,
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
    metric: Literal[
        "pipeline_lag_seconds",
        "consumer_lag_p95",
        "consumer_commit_rate_per_sec",
        "topic_ingress_messages_per_sec",
        "source_freshness_delay_ms",
        "source_watermark_delay_ms",
        "source_event_rate_per_sec",
        "broker_cpu_cores",
        "broker_memory_working_set_bytes",
        "broker_network_receive_bytes_per_sec",
        "broker_network_transmit_bytes_per_sec",
        "broker_fs_read_bytes_per_sec",
        "broker_fs_write_bytes_per_sec",
    ]
    time_range: str | None = None


class GetDeploymentsParams(ToolParams):
    limit: int | None = None


class ConnectorStatusParams(ToolParams):
    connector_name: str


class ListConnectorsParams(ToolParams):
    pass


class ListDatasourcesParams(ToolParams):
    pass


class GetClusterInfoParams(ToolParams):
    pass


class SqlReadParams(ToolParams):
    datasource_id: str  # 대상 datasource id (list_datasources 결과에서 확보)
    sql: str  # SELECT/WITH 단일 statement(read-only)


class ConsumerLagParams(ToolParams):
    consumer_group: str


class GetConsumerGroupsParams(ToolParams):
    pass


class ListProjectPipelinesParams(ToolParams):
    pass


class ListPipelinesParams(ToolParams):
    pass


class AnalyzeEventLogParams(ToolParams):
    window: str = "2h"
    level: str = "warn+"
    pipeline_id: str | None = None
    connector_name: str | None = None


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
    structured_result: bool = False
    # 사용자 노출용 한 문장 설명 — 카탈로그 API·슬래시 커맨드 드롭다운(#599).
    description: str = ""
    # 그룹형 명령 팔레트(한국어) 메타 — group이 빈 문자열이면 팔레트에 노출하지 않는다.
    # group ∈ {pipeline, cluster, incident}, label_ko = 팔레트 칩에 보일 기능명.
    group: str = ""
    label_ko: str = ""

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
        dumped = params.model_dump(by_alias=True, exclude_none=True)
        # path param(예: datasource_id)은 URL에 들어가므로 body에서 제외한다.
        return {key: value for key, value in dumped.items() if key not in self.path_params}

    def validate_result(self, result: dict[str, Any] | None) -> None:
        self.result_model.model_validate(result or {})


def default_tool_definitions() -> dict[str, ToolDefinition]:
    definitions = [
        # ── catalog §8.1 Observability ──────────────────────────────────────
        ToolDefinition(
            name="search_logs",
            description="파이프라인 로그를 키워드·기간 조건으로 검색합니다.",
            group="incident",
            label_ko="로그 검색",
            operation="search_logs",
            method="POST",
            path_template="/internal/ops/projects/{project_id}/observability/logs/search",
            risk=RiskLevel.READ_ONLY,
            params_model=GetPipelineLogsParams,
            result_model=LogSearchData,
            sends_body=True,
            structured_result=True,
        ),
        ToolDefinition(
            name="get_metrics",
            description="프로젝트 운영 메트릭을 조회합니다.",
            group="incident",
            label_ko="지표 조회",
            operation="query_metrics",
            method="GET",
            path_template="/internal/ops/projects/{project_id}/observability/metrics",
            risk=RiskLevel.READ_ONLY,
            params_model=GetMetricsParams,
            result_model=MetricsData,
            structured_result=True,
        ),
        # ── catalog §8.2 Pipeline / Change ──────────────────────────────────
        ToolDefinition(
            name="get_deployments",
            description="최근 파이프라인 배포·설정 변경 이력을 조회합니다.",
            group="pipeline",
            label_ko="배포·변경 이력",
            operation="get_recent_changes",
            method="GET",
            path_template="/internal/ops/projects/{project_id}/pipelines/changes",
            risk=RiskLevel.READ_ONLY,
            params_model=GetDeploymentsParams,
            result_model=DeploymentsData,
            structured_result=True,
        ),
        # ── catalog §8.3 Kafka / Kafka Connect ──────────────────────────────
        ToolDefinition(
            name="get_connector_status",
            description="지정 Connector의 상태와 Task 정보를 조회합니다.",
            group="cluster",
            label_ko="커넥터 상태",
            operation="get_connector_status",
            method="GET",
            path_template="/internal/ops/projects/{project_id}/kafka/connectors/{connector_name}/status",
            risk=RiskLevel.READ_ONLY,
            params_model=ConnectorStatusParams,
            result_model=ConnectorStatusData,
            path_params=("connector_name",),
        ),
        ToolDefinition(
            name="list_connectors",
            description="Kafka Connector 상태 및 Task 정보를 조회합니다.",
            operation="list_connectors",
            method="GET",
            path_template="/internal/ops/projects/{project_id}/kafka/connectors/status",
            risk=RiskLevel.READ_ONLY,
            params_model=ListConnectorsParams,
            result_model=ConnectorStatusListData,
            structured_result=True,
        ),
        # list_datasources — 프로젝트 DB 목록·헬스(#633). 'DB 현황' 질의에 답할 도구.
        ToolDefinition(
            name="list_datasources",
            description="프로젝트의 데이터베이스(소스/싱크) 목록과 연결·준비 상태를 조회합니다.",
            operation="list_datasources",
            method="GET",
            path_template="/internal/ops/projects/{project_id}/datasources",
            risk=RiskLevel.READ_ONLY,
            params_model=ListDatasourcesParams,
            result_model=DatasourceListData,
            structured_result=True,
        ),
        # get_cluster_info — 브로커/컨트롤러 + 토픽 파티션 상세(#633 범용 read 프리미티브).
        ToolDefinition(
            name="get_cluster_info",
            description="Kafka 클러스터의 브로커·컨트롤러와 토픽 파티션(ISR/leader) 상세를 조회합니다.",
            group="cluster",
            label_ko="클러스터 상태",
            operation="get_cluster_info",
            method="GET",
            path_template="/internal/ops/projects/{project_id}/kafka/cluster",
            risk=RiskLevel.READ_ONLY,
            params_model=GetClusterInfoParams,
            result_model=ClusterInfoData,
            structured_result=True,
        ),
        # sql_read — datasource에 read-only SELECT(#633 범용 프리미티브). 'DB 상세' 질의.
        ToolDefinition(
            name="sql_read",
            description="데이터소스에 read-only SELECT를 실행해 테이블 데이터·집계를 조회합니다.",
            operation="sql_read",
            method="POST",
            path_template="/internal/ops/projects/{project_id}/datasources/{datasource_id}/query",
            risk=RiskLevel.READ_ONLY,
            params_model=SqlReadParams,
            result_model=SqlReadData,
            path_params=("datasource_id",),
            sends_body=True,
            structured_result=True,
        ),
        ToolDefinition(
            name="get_consumer_lag",
            description="지정 Consumer Group의 파티션별 lag을 조회합니다.",
            group="cluster",
            label_ko="컨슈머 lag",
            operation="get_consumer_lag",
            method="GET",
            path_template="/internal/ops/projects/{project_id}/kafka/consumer-groups/{consumer_group}/lag",
            risk=RiskLevel.READ_ONLY,
            params_model=ConsumerLagParams,
            result_model=ConsumerLagData,
            path_params=("consumer_group",),
            structured_result=True,
        ),
        ToolDefinition(
            name="get_consumer_groups",
            description="Consumer Group의 lag 현황과 상태를 조회합니다.",
            operation="get_consumer_groups",
            method="GET",
            path_template="/internal/ops/projects/{project_id}/kafka/consumer-groups",
            risk=RiskLevel.READ_ONLY,
            params_model=GetConsumerGroupsParams,
            result_model=ConsumerGroupsData,
            structured_result=True,
        ),
        ToolDefinition(
            name="get_kafka_lag",
            description="지정 Consumer Group의 파티션별 lag을 조회합니다.",
            operation="get_consumer_lag",
            method="GET",
            path_template="/internal/ops/projects/{project_id}/kafka/consumer-groups/{consumer_group}/lag",
            risk=RiskLevel.READ_ONLY,
            params_model=ConsumerLagParams,
            result_model=ConsumerLagData,
            path_params=("consumer_group",),
            alias_for="get_consumer_lag",
            structured_result=True,
        ),
        # ── catalog §8.4 Pipeline read (Spring PR #154) ──────────────────────
        ToolDefinition(
            name="list_project_pipelines",
            description="프로젝트의 파이프라인 목록을 조회합니다.",
            group="pipeline",
            label_ko="파이프라인 목록",
            operation="list_project_pipelines",
            method="GET",
            path_template="/internal/ops/projects/{project_id}/pipelines",
            risk=RiskLevel.READ_ONLY,
            params_model=ListProjectPipelinesParams,
            result_model=ListProjectPipelinesData,
        ),
        ToolDefinition(
            name="list_pipelines",
            description="현재 프로젝트의 파이프라인 상태를 조회합니다.",
            operation="list_pipelines",
            method="GET",
            path_template="/internal/ops/projects/{project_id}/pipelines/status",
            risk=RiskLevel.READ_ONLY,
            params_model=ListPipelinesParams,
            result_model=PipelineStatusListData,
            structured_result=True,
        ),
        ToolDefinition(
            name="get_pipeline_topology",
            description="지정 파이프라인의 토폴로지(source→topic→sink 구성)를 조회합니다.",
            group="pipeline",
            label_ko="토폴로지",
            operation="get_pipeline_topology",
            method="GET",
            path_template="/internal/ops/projects/{project_id}/pipelines/{pipeline_id}/topology",
            risk=RiskLevel.READ_ONLY,
            params_model=PipelineTopologyParams,
            result_model=PipelineTopologyData,
            path_params=("pipeline_id",),
        ),
        # ── catalog §8.5 Incident summary ────────────────────────────────────
        # 백엔드는 project-scoped 경로만 허용한다(비-scoped는 VALIDATION_FAILED).
        # {project_id}는 ToolContext에서 자동 주입되므로 path_params엔 incident_id만 둔다.
        ToolDefinition(
            name="get_incident_summary",
            description="지정 인시던트의 요약 정보를 조회합니다.",
            group="incident",
            label_ko="인시던트 요약",
            operation="get_incident_summary",
            method="GET",
            path_template="/internal/ops/projects/{project_id}/incidents/{incident_id}/summary",
            risk=RiskLevel.READ_ONLY,
            params_model=GetIncidentSummaryParams,
            result_model=IncidentSummaryData,
            path_params=("incident_id",),
        ),
        # ── catalog §8.6 Mutation — Kafka Connect 운영 조치 ──────────────────
        ToolDefinition(
            name="restart_connector",
            description="지정 Connector를 재시작합니다. (승인 필요)",
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
            description="지정 Connector를 일시 중지합니다. (승인 필요)",
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
            description="일시 중지된 Connector를 재개합니다. (승인 필요)",
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
            description="지정 Consumer Group을 재시작합니다. (승인 필요)",
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
            description="지정 Connector의 최근 trace 이벤트를 조회합니다.",
            group="cluster",
            label_ko="트레이스",
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
            description="지정 Connector의 Task 단위 trace를 조회합니다.",
            operation="get_connector_task_trace",
            method="GET",
            path_template="/internal/ops/projects/{project_id}/connectors/{connector_name}/task-trace",
            risk=RiskLevel.READ_ONLY,
            params_model=GetConnectorTaskTraceParams,
            result_model=ConnectorTaskTraceData,
            path_params=("connector_name",),
            structured_result=True,
        ),
        ToolDefinition(
            name="get_alerts",
            description="프로젝트의 최근 알림(alert) 목록을 조회합니다.",
            group="incident",
            label_ko="인시던트 목록",
            operation="list_alerts",
            method="GET",
            path_template="/internal/ops/projects/{project_id}/observability/alerts",
            risk=RiskLevel.READ_ONLY,
            params_model=GetAlertsParams,
            result_model=AlertsData,
        ),
        ToolDefinition(
            name="analyze_event_log",
            description="최근 2시간 이벤트 로그와 인시던트 현황을 분석합니다.",
            operation="analyze_event_log",
            method="GET",
            path_template="/internal/ops/projects/{project_id}/observability/events/summary",
            risk=RiskLevel.READ_ONLY,
            params_model=AnalyzeEventLogParams,
            result_model=EventIncidentSummaryData,
            structured_result=True,
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

        params = _params_with_context_scope(tool_name, params, context)

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

        sanitized_result: dict[str, Any] | None = None
        try:
            validated_result = definition.result_model.model_validate(response.result or {})
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
        if definition.structured_result:
            exclude = {"logs"} if definition.name == "search_logs" else None
            sanitized_result = validated_result.model_dump(
                mode="json",
                by_alias=True,
                exclude_none=True,
                exclude=exclude,
            )

            result = result_from_spring_response(
                tool_name=tool_name,
                risk=definition.risk,
                response=response,
                requires_approval=definition.requires_approval,
                result=sanitized_result,
            )
        return result, response.result


_OBSERVABILITY_SCOPE_TOOLS = frozenset({"get_alerts", "analyze_event_log"})


def _params_with_context_scope(
    tool_name: str,
    params: dict[str, Any],
    context: ToolContext,
) -> dict[str, Any]:
    if tool_name not in _OBSERVABILITY_SCOPE_TOOLS:
        return params
    if params.get("pipeline_id") or params.get("connector_name") or not context.pipeline_id:
        return params
    scoped = dict(params)
    scoped["pipeline_id"] = context.pipeline_id
    return scoped
