package com.bifrost.ops.internalops.controller;

import com.bifrost.ops.adapters.logstore.LokiClient;
import com.bifrost.ops.internalops.dto.OpsEnvelope;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.provisioning.PipelineProvisioningService;
import com.bifrost.ops.provisioning.impl.strimzi.TenantProvisioner;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.kafka.clients.admin.AdminClient;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class InternalOpsToolCatalogTest {

    private final InternalOpsController controller =
            new InternalOpsController(mock(JdbcTemplate.class), "operations-backend");

    @Test
    void toolCatalogMatchesImplementedInternalOpsToolsOnly() {
        ResponseEntityBody catalogResponse = catalog();
        List<Map<String, String>> catalog = catalogResponse.catalog();

        assertThat(catalog)
                .extracting(entry -> entry.get("name"))
                .containsExactly(
                        "get_consumer_lag",
                        "get_consumer_groups",
                        "search_logs",
                        "query_metrics",
                        "query_traces",
                        "get_connector_task_trace",
                        "list_alerts",
                        "analyze_event_log",
                        "get_incident_summary",
                        "list_project_pipelines",
                        "list_pipelines",
                        "get_recent_changes",
                        "get_pipeline_topology",
                        "get_connector_status",
                        "list_connectors",
                        "list_datasources",
                        "get_cluster_info",
                        "sql_read",
                        "restart_connector",
                        "pause_connector",
                        "resume_connector",
                        "restart_consumer_group");

        assertThat(catalog).containsExactly(
                tool("get_consumer_lag", "GET", "/internal/ops/projects/{projectId}/kafka/consumer-groups/{consumerGroup}/lag"),
                tool("get_consumer_groups", "GET", "/internal/ops/projects/{projectId}/kafka/consumer-groups"),
                tool("search_logs", "POST", "/internal/ops/projects/{projectId}/observability/logs/search"),
                tool("query_metrics", "GET", "/internal/ops/projects/{projectId}/observability/metrics"),
                tool("query_traces", "GET", "/internal/ops/projects/{projectId}/connectors/{connectorName}/traces"),
                tool("get_connector_task_trace", "GET", "/internal/ops/projects/{projectId}/connectors/{connectorName}/task-trace"),
                tool("list_alerts", "GET", "/internal/ops/projects/{projectId}/observability/alerts"),
                tool("analyze_event_log", "GET", "/internal/ops/projects/{projectId}/observability/events/summary"),
                tool("get_incident_summary", "GET", "/internal/ops/projects/{projectId}/incidents/{incidentId}/summary"),
                tool("list_project_pipelines", "GET", "/internal/ops/projects/{projectId}/pipelines"),
                tool("list_pipelines", "GET", "/internal/ops/projects/{projectId}/pipelines/status"),
                tool("get_recent_changes", "GET", "/internal/ops/projects/{projectId}/pipelines/changes"),
                tool("get_pipeline_topology", "GET", "/internal/ops/projects/{projectId}/pipelines/{pipelineId}/topology"),
                tool("get_connector_status", "GET", "/internal/ops/projects/{projectId}/kafka/connectors/{connectorName}/status"),
                tool("list_connectors", "GET", "/internal/ops/projects/{projectId}/kafka/connectors/status"),
                tool("list_datasources", "GET", "/internal/ops/projects/{projectId}/datasources"),
                tool("get_cluster_info", "GET", "/internal/ops/projects/{projectId}/kafka/cluster"),
                tool("sql_read", "POST", "/internal/ops/projects/{projectId}/datasources/{datasourceId}/query"),
                tool("restart_connector", "POST", "/internal/ops/projects/{projectId}/connectors/{connectorName}/restart"),
                tool("pause_connector", "POST", "/internal/ops/projects/{projectId}/connectors/{connectorName}/pause"),
                tool("resume_connector", "POST", "/internal/ops/projects/{projectId}/connectors/{connectorName}/resume"),
                tool("restart_consumer_group", "POST", "/internal/ops/projects/{projectId}/kafka/consumer-groups/{consumerGroup}/restart"));

        assertThat(catalog)
                .extracting(entry -> entry.get("name"))
                .doesNotContain("get_metrics", "get_deployments", "get_kafka_lag");
    }

    @Test
    void everyCatalogEntryResolvesToARegisteredSpringHandler() throws Exception {
        Set<MappingKey> mappings = registeredInternalOpsMappings();
        Set<MappingKey> catalogMappings = catalog().catalog().stream()
                .map(entry -> new MappingKey(entry.get("method"), entry.get("path")))
                .collect(Collectors.toSet());

        assertThat(mappings).containsAll(catalogMappings);
    }

    private ResponseEntityBody catalog() {
        var response = controller.toolCatalog(request());
        assertThat(response.getBody()).isNotNull();
        return new ResponseEntityBody(response.getBody().result());
    }

    private Set<MappingKey> registeredInternalOpsMappings() throws Exception {
        StaticWebApplicationContext context = new StaticWebApplicationContext();
        context.getBeanFactory().registerSingleton("internalOpsController", controller);
        context.getBeanFactory().registerSingleton("internalOpsObservabilityController", observabilityController());
        context.getBeanFactory().registerSingleton("internalOpsPipelineController", pipelineController());
        context.getBeanFactory().registerSingleton("internalOpsDatasourceController", datasourceController());
        context.getBeanFactory().registerSingleton("internalOpsKafkaController", kafkaController());
        context.getBeanFactory().registerSingleton("internalOpsSqlController", sqlController());
        context.getBeanFactory().registerSingleton("internalOpsMutationController", mutationController());
        context.getBeanFactory().registerSingleton("internalController", internalController());
        context.refresh();

        RequestMappingHandlerMapping handlerMapping = new RequestMappingHandlerMapping();
        handlerMapping.setApplicationContext(context);
        handlerMapping.afterPropertiesSet();

        return handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(this::mappingKeys)
                .filter(mapping -> mapping.path().startsWith("/internal/ops/"))
                .collect(Collectors.toSet());
    }

    private Stream<MappingKey> mappingKeys(RequestMappingInfo info) {
        Set<String> paths = info.getPathPatternsCondition() != null
                ? info.getPathPatternsCondition().getPatternValues()
                : info.getPatternsCondition().getPatterns();
        Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
        return paths.stream()
                .flatMap(path -> methods.stream().map(method -> new MappingKey(method.name(), path)));
    }

    private InternalOpsObservabilityController observabilityController() {
        return new InternalOpsObservabilityController(
                mock(AdminClient.class),
                mock(LokiClient.class),
                mock(WorkspaceRepository.class),
                mock(PipelineRepository.class),
                mock(ConnectorRepository.class),
                mock(com.bifrost.ops.incident.persistence.repository.IncidentRepository.class),
                mock(com.bifrost.ops.event.persistence.repository.EventRepository.class),
                mock(com.bifrost.ops.monitoring.query.ObservabilityMetricsQuery.class),
                mock(com.bifrost.ops.monitoring.query.TraceQuery.class),
                "platform-kafka",
                "http://connect.invalid");
    }

    private InternalOpsPipelineController pipelineController() {
        return new InternalOpsPipelineController(
                mock(WorkspaceRepository.class),
                mock(PipelineRepository.class),
                mock(ConnectorRepository.class),
                mock(AdminClient.class));
    }

    private InternalOpsDatasourceController datasourceController() {
        return new InternalOpsDatasourceController(
                mock(WorkspaceRepository.class),
                mock(com.bifrost.ops.database.persistence.repository.DatasourceRepository.class));
    }

    private InternalOpsKafkaController kafkaController() {
        return new InternalOpsKafkaController(
                mock(AdminClient.class),
                mock(WorkspaceRepository.class),
                mock(PipelineRepository.class));
    }

    private InternalOpsSqlController sqlController() {
        return new InternalOpsSqlController(
                mock(WorkspaceRepository.class),
                mock(com.bifrost.ops.database.persistence.repository.DatasourceRepository.class),
                mock(com.bifrost.ops.secret.SecretStore.class),
                mock(com.bifrost.ops.database.connection.DynamicDataSourceFactory.class));
    }

    private InternalOpsMutationController mutationController() {
        return new InternalOpsMutationController(
                mock(WorkspaceRepository.class),
                mock(PipelineRepository.class),
                mock(ConnectorRepository.class),
                mock(com.bifrost.ops.governance.MutationGate.class),
                mock(com.bifrost.ops.governance.idempotency.IdempotencyGuard.class),
                mock(com.bifrost.ops.adapters.connect.ConnectRestClient.class),
                mock(com.bifrost.ops.internalops.operations.kafka.ConsumerGroupVerifier.class),
                mock(com.fasterxml.jackson.databind.ObjectMapper.class));
    }

    private InternalController internalController() {
        return new InternalController(
                mock(TenantProvisioner.class),
                mock(PipelineProvisioningService.class),
                mock(WorkspaceRepository.class),
                mock(PipelineRepository.class),
                mock(ConnectorRepository.class));
    }

    private static Map<String, String> tool(String name, String method, String path) {
        return Map.of("name", name, "method", method, "path", path);
    }

    private static HttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "req-catalog-001");
        return request;
    }

    private record MappingKey(String method, String path) {}

    private record ResponseEntityBody(List<Map<String, String>> catalog) {}
}
