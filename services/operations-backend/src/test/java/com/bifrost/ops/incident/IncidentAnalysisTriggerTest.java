package com.bifrost.ops.incident;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class IncidentAnalysisTriggerTest {

    private static final String AI_URL = "http://ai-service";

    @Test
    void startPostsIncidentAnalysisRunToAiService() {
        UUID tenantId = UUID.randomUUID();
        UUID incidentId = UUID.randomUUID();
        TestContext context = context();
        context.server.expect(requestTo(AI_URL + "/api/v1/agent/runs"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "project_id": "%s",
                          "mode": "incident_analysis",
                          "incident_id": "%s",
                          "message": "Pipeline failed\\nboom",
                          "remediation_requested": true,
                          "stream": false
                        }
                        """.formatted(tenantId, incidentId)))
                .andRespond(withSuccess("""
                        {"ok": true, "data": {"run_id": "run_001", "status": "running"}}
                        """, MediaType.APPLICATION_JSON));

        context.trigger.start(tenantId, incidentId, "Pipeline failed", "boom");

        context.server.verify();
    }

    @Test
    void startRetriesUpToThreeTimesWhenAiServiceFails() {
        TestContext context = context();
        // #923: 모든 시도가 실패해도 예외를 던지지 않고 3회까지 재시도한다.
        context.server.expect(ExpectedCount.times(3), requestTo(AI_URL + "/api/v1/agent/runs"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        assertThatNoException().isThrownBy(() ->
                context.trigger.start(UUID.randomUUID(), UUID.randomUUID(), "Pipeline failed", "boom"));
        context.server.verify();
    }

    @Test
    void startRetriesAndSucceedsAfterTransientError() {
        UUID tenantId = UUID.randomUUID();
        UUID incidentId = UUID.randomUUID();
        TestContext context = context();
        // #923: 일시 실패(첫 시도 500) 후 재시도에서 성공하면 RCA 가 누락되지 않는다.
        context.server.expect(ExpectedCount.once(), requestTo(AI_URL + "/api/v1/agent/runs"))
                .andRespond(withServerError());
        context.server.expect(ExpectedCount.once(), requestTo(AI_URL + "/api/v1/agent/runs"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"ok": true, "data": {"run_id": "run_002", "status": "running"}}
                        """, MediaType.APPLICATION_JSON));

        assertThatNoException().isThrownBy(() ->
                context.trigger.start(tenantId, incidentId, "Pipeline failed", "boom"));
        context.server.verify();
    }

    @Test
    void startSkipsWhenAiServiceUrlIsBlank() {
        TestContext context = context(" ");

        assertThatNoException().isThrownBy(() ->
                context.trigger.start(UUID.randomUUID(), UUID.randomUUID(), "Pipeline failed", "boom"));
        context.server.verify();
    }

    @Test
    void startSkipsWhenAiServiceUrlPointsToLocalhost() {
        TestContext context = context("http://localhost:8082");

        assertThatNoException().isThrownBy(() ->
                context.trigger.start(UUID.randomUUID(), UUID.randomUUID(), "Pipeline failed", "boom"));
        context.server.verify();
    }

    private static TestContext context() {
        return context(AI_URL);
    }

    private static TestContext context(String aiServiceUrl) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // 동기 executor + backoff 0 으로 테스트를 빠르게 — 프로덕션은 applicationTaskExecutor + backoff.
        return new TestContext(new IncidentAnalysisTrigger(aiServiceUrl, builder, Runnable::run, 0L), server);
    }

    private record TestContext(IncidentAnalysisTrigger trigger, MockRestServiceServer server) {}
}
