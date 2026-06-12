package com.bifrost.ops.incident;

import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.incident.dto.IncidentReportResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class IncidentReportServiceTest {

    private static final String AI_URL = "http://ai-service";

    @Test
    void listParsesIncidentReportsFromAiEnvelope() {
        UUID incidentId = UUID.randomUUID();
        TestContext context = context();
        context.server.expect(requestTo(AI_URL + "/api/v1/incidents/" + incidentId + "/reports"))
                .andRespond(withSuccess("""
                        {
                          "ok": true,
                          "data": {
                            "incident_id": "%s",
                            "reports": [{
                              "id": "rep-001",
                              "run_id": "run-001",
                              "incident_id": "%s",
                              "root_cause_id": "rc-001",
                              "confidence": 0.82,
                              "verified": true,
                              "created_at": "2026-06-01T00:00:00Z",
                              "body": {"answer": "root cause summary"}
                            }]
                          }
                        }
                        """.formatted(incidentId, incidentId), MediaType.APPLICATION_JSON));

        List<IncidentReportResponse> reports = context.service.list(incidentId);

        assertThat(reports).hasSize(1);
        IncidentReportResponse report = reports.getFirst();
        assertThat(report.id()).isEqualTo("rep-001");
        assertThat(report.runId()).isEqualTo("run-001");
        assertThat(report.incidentId()).isEqualTo(incidentId.toString());
        assertThat(report.rootCauseId()).isEqualTo("rc-001");
        assertThat(report.confidence()).isEqualTo(0.82);
        assertThat(report.verified()).isTrue();
        assertThat(report.body().path("answer").asText()).isEqualTo("root cause summary");
        assertThat(report.createdAt()).isNotNull();
        context.server.verify();
    }

    @Test
    void listRejectsMalformedEnvelope() {
        UUID incidentId = UUID.randomUUID();
        TestContext context = context();
        context.server.expect(requestTo(AI_URL + "/api/v1/incidents/" + incidentId + "/reports"))
                .andRespond(withSuccess("""
                        {"ok": true, "data": {"reports": {"id": "rep-001"}}}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> context.service.list(incidentId))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.INTERNAL_ERROR));
        context.server.verify();
    }

    @Test
    void listRejectsFailedAiEnvelope() {
        UUID incidentId = UUID.randomUUID();
        TestContext context = context();
        context.server.expect(requestTo(AI_URL + "/api/v1/incidents/" + incidentId + "/reports"))
                .andRespond(withSuccess("""
                        {"ok": false, "error": {"message": "lookup failed"}}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> context.service.list(incidentId))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.INTERNAL_ERROR));
        context.server.verify();
    }

    @Test
    void getThrowsWhenReportMissing() {
        UUID incidentId = UUID.randomUUID();
        TestContext context = context();
        context.server.expect(requestTo(AI_URL + "/api/v1/incidents/" + incidentId + "/reports"))
                .andRespond(withSuccess("""
                        {"ok": true, "data": {"reports": []}}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> context.service.get(incidentId, "missing"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        context.server.verify();
    }

    @Test
    void listRejectsBlankAiServiceUrlWithoutHttpCall() {
        TestContext context = context(" ");

        assertThatThrownBy(() -> context.service.list(UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.INTERNAL_ERROR));
        context.server.verify();
    }

    @Test
    void listRejectsLocalhostAiServiceUrlWithoutHttpCall() {
        TestContext context = context("http://localhost:8082");

        assertThatThrownBy(() -> context.service.list(UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.INTERNAL_ERROR));
        context.server.verify();
    }

    private static TestContext context() {
        return context(AI_URL);
    }

    private static TestContext context(String aiServiceUrl) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        IncidentReportService service = new IncidentReportService(aiServiceUrl, builder, new ObjectMapper());
        return new TestContext(service, server);
    }

    private record TestContext(IncidentReportService service, MockRestServiceServer server) {}
}
