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
                        "search_logs",
                        "query_traces",
                        "list_alerts",
                        "get_incident_summary",
                        "list_project_pipelines",
                        "get_pipeline_topology",
                        "get_connector_status");

        assertThat(catalog).containsExactly(
                tool("get_consumer_lag", "GET", "/internal/ops/projects/{projectId}/kafka/consumer-groups/{consumerGroup}/lag"),
                tool("search_logs", "POST", "/internal/ops/projects/{projectId}/observability/logs/search"),
                tool("query_traces", "GET", "/internal/ops/projects/{projectId}/connectors/{connectorName}/traces"),
                tool("list_alerts", "GET", "/internal/ops/projects/{projectId}/observability/alerts"),
                tool("get_incident_summary", "GET", "/internal/ops/projects/{projectId}/incidents/{incidentId}/summary"),
                tool("list_project_pipelines", "GET", "/internal/ops/projects/{projectId}/pipelines"),
                tool("get_pipeline_topology", "GET", "/internal/ops/projects/{projectId}/pipelines/{pipelineId}/topology"),
                tool("get_connector_status", "GET", "/internal/ops/projects/{projectId}/kafka/connectors/{connectorName}/status"));

        assertThat(catalog)
                .extracting(entry -> entry.get("name"))
                .doesNotContain("get_metrics", "get_deployments", "get_kafka_lag", "restart_connector");
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
                "http://connect.invalid");
    }

    private InternalOpsPipelineController pipelineController() {
        return new InternalOpsPipelineController(
                mock(WorkspaceRepository.class),
                mock(PipelineRepository.class),
                mock(ConnectorRepository.class));
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
