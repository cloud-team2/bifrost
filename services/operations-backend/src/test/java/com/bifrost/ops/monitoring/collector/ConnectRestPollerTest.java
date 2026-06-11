package com.bifrost.ops.monitoring.collector;

import com.bifrost.ops.event.EventLevel;
import com.bifrost.ops.event.EventService;
import com.bifrost.ops.incident.IncidentGroupingKeys;
import com.bifrost.ops.incident.IncidentService;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class ConnectRestPollerTest {

    private static final String CONNECT_URL = "http://connect";

    @Mock private PipelineRepository pipelineRepository;
    @Mock private EventService eventService;
    @Mock private IncidentService incidentService;

    @Test
    void failedTaskOpensIncidentWithConnectorGrouping() {
        TestContext context = newContext();
        PipelineEntity pipeline = pipeline("ACTIVE");
        when(pipelineRepository.findAll()).thenReturn(List.of(pipeline));
        expectConnectors(context.server);
        expectStatus(context.server, "FAILED", "trace boom");

        context.poller.poll();

        verify(incidentService).onThresholdViolation(
                eq(pipeline.getTenantId()),
                eq(IncidentGroupingKeys.connectorWorker("orders-source")),
                eq("CONNECTOR"),
                isNull(),
                eq(EventLevel.ERROR),
                eq("Pipeline 'orders-eda' connector task failed"),
                eq("CONNECTOR_TASK_FAILED"),
                org.mockito.ArgumentMatchers.contains("trace boom"),
                eq(pipeline.getId()));
        verify(eventService, never()).record(eq(pipeline.getTenantId()), eq(pipeline.getId()),
                eq(EventLevel.ERROR), eq("CONNECTOR_TASK_FAILED"), any());
        context.server.verify();
    }

    @Test
    void recoveredTaskFallsBackToPlainEventWhenNoIncidentWasOpen() {
        TestContext context = newContext();
        PipelineEntity pipeline = pipeline("ACTIVE");
        when(pipelineRepository.findAll()).thenReturn(List.of(pipeline));
        expectConnectors(context.server);
        expectStatus(context.server, "FAILED", "trace boom");
        expectConnectors(context.server);
        expectStatus(context.server, "RUNNING", null);

        context.poller.poll();
        context.poller.poll();

        verify(eventService).record(eq(pipeline.getTenantId()), eq(pipeline.getId()),
                eq(EventLevel.INFO), eq("CONNECTOR_TASK_RECOVERED"),
                org.mockito.ArgumentMatchers.contains("orders-source:0"));
        context.server.verify();
    }

    @Test
    void recoveredTaskResolvesOpenIncidentEvenBeforePipelineIsActive() {
        TestContext context = newContext();
        PipelineEntity pipeline = pipeline("ERROR");
        when(pipelineRepository.findAll()).thenReturn(List.of(pipeline));
        when(incidentService.onRecovery(eq(pipeline.getTenantId()),
                eq(IncidentGroupingKeys.connectorWorker("orders-source")),
                eq("CONNECTOR_TASK_RECOVERED"), any(), eq(pipeline.getId()))).thenReturn(true);
        expectConnectors(context.server);
        expectStatus(context.server, "FAILED", "trace boom");
        expectConnectors(context.server);
        expectStatus(context.server, "RUNNING", null);

        context.poller.poll();
        context.poller.poll();

        verify(incidentService).onRecovery(eq(pipeline.getTenantId()),
                eq(IncidentGroupingKeys.connectorWorker("orders-source")),
                eq("CONNECTOR_TASK_RECOVERED"),
                org.mockito.ArgumentMatchers.contains("orders-source:0"),
                eq(pipeline.getId()));
        verify(eventService, never()).record(eq(pipeline.getTenantId()), eq(pipeline.getId()),
                eq(EventLevel.INFO), eq("CONNECTOR_TASK_RECOVERED"), any());
        context.server.verify();
    }

    @Test
    void recoveredTaskDoesNotResolveConnectorIncidentUntilAllTasksRecover() {
        TestContext context = newContext();
        PipelineEntity pipeline = pipeline("ERROR");
        when(pipelineRepository.findAll()).thenReturn(List.of(pipeline));
        when(incidentService.onRecovery(eq(pipeline.getTenantId()),
                eq(IncidentGroupingKeys.connectorWorker("orders-source")),
                eq("CONNECTOR_TASK_RECOVERED"), any(), eq(pipeline.getId()))).thenReturn(true);
        expectConnectors(context.server);
        expectTasks(context.server, task("0", "FAILED", "trace zero"), task("1", "FAILED", "trace one"));
        expectConnectors(context.server);
        expectTasks(context.server, task("0", "RUNNING", null), task("1", "FAILED", "trace one"));
        expectConnectors(context.server);
        expectTasks(context.server, task("0", "RUNNING", null), task("1", "RUNNING", null));

        context.poller.poll();
        context.poller.poll();
        context.poller.poll();

        verify(incidentService, never()).onRecovery(eq(pipeline.getTenantId()),
                eq(IncidentGroupingKeys.connectorWorker("orders-source")),
                eq("CONNECTOR_TASK_RECOVERED"),
                org.mockito.ArgumentMatchers.contains("orders-source:0"),
                eq(pipeline.getId()));
        verify(incidentService).onRecovery(eq(pipeline.getTenantId()),
                eq(IncidentGroupingKeys.connectorWorker("orders-source")),
                eq("CONNECTOR_TASK_RECOVERED"),
                org.mockito.ArgumentMatchers.contains("orders-source:1"),
                eq(pipeline.getId()));
        verify(eventService).record(eq(pipeline.getTenantId()), eq(pipeline.getId()),
                eq(EventLevel.INFO), eq("CONNECTOR_TASK_RECOVERED"),
                org.mockito.ArgumentMatchers.contains("orders-source:0"));
        context.server.verify();
    }

    private TestContext newContext() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CONNECT_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new TestContext(new ConnectRestPoller(builder.build(), pipelineRepository, eventService, incidentService),
                server);
    }

    private static void expectConnectors(MockRestServiceServer server) {
        server.expect(requestTo(CONNECT_URL + "/connectors"))
                .andRespond(withSuccess("[\"orders-source\"]", MediaType.APPLICATION_JSON));
    }

    private static void expectStatus(MockRestServiceServer server, String state, String trace) {
        expectTasks(server, task("0", state, trace));
    }

    private static void expectTasks(MockRestServiceServer server, String... tasks) {
        server.expect(requestTo(CONNECT_URL + "/connectors/orders-source/status"))
                .andRespond(withSuccess("""
                        {"tasks": [%s]}
                        """.formatted(String.join(",", tasks)), MediaType.APPLICATION_JSON));
    }

    private static String task(String id, String state, String trace) {
        String traceField = trace == null ? "" : ", \"trace\": \"" + trace + "\"";
        return "{\"id\": %s, \"state\": \"%s\"%s}".formatted(id, state, traceField);
    }

    private static PipelineEntity pipeline(String status) {
        PipelineEntity pipeline = new PipelineEntity();
        pipeline.setId(UUID.randomUUID());
        pipeline.setTenantId(UUID.randomUUID());
        pipeline.setName("orders-eda");
        pipeline.setSourceConnectorName("orders-source");
        pipeline.setStatus(com.bifrost.ops.pipeline.PipelineLifecycle.valueOf(status));
        return pipeline;
    }

    private record TestContext(ConnectRestPoller poller, MockRestServiceServer server) {
    }
}
