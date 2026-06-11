package com.bifrost.ops.incident;

import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.incident.dto.IncidentReportResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

@Service
public class IncidentReportService {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public IncidentReportService(@Value("${ai-service.url:http://localhost:8082}") String aiServiceUrl,
                                 RestClient.Builder restClientBuilder,
                                 ObjectMapper objectMapper) {
        this.restClient = restClientBuilder.baseUrl(stripTrailingSlash(aiServiceUrl)).build();
        this.objectMapper = objectMapper;
    }

    public List<IncidentReportResponse> list(UUID incidentId) {
        JsonNode root = fetchReports(incidentId);
        JsonNode reports = root.path("data").path("reports");
        if (!reports.isArray()) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "incident reports response was malformed");
        }
        return StreamSupport.stream(reports.spliterator(), false)
                .map(this::toReport)
                .toList();
    }

    public IncidentReportResponse get(UUID incidentId, String reportId) {
        return list(incidentId).stream()
                .filter(report -> reportId.equals(report.id()))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "incident report not found: " + reportId));
    }

    private JsonNode fetchReports(UUID incidentId) {
        try {
            JsonNode root = restClient.get()
                    .uri("/api/v1/incidents/{incidentId}/reports", incidentId)
                    .retrieve()
                    .body(JsonNode.class);
            if (root == null) {
                throw new ApiException(ErrorCode.INTERNAL_ERROR, "incident reports response was empty");
            }
            if (root.has("ok") && !root.path("ok").asBoolean(false)) {
                String message = root.path("error").path("message").asText("incident reports lookup failed");
                throw new ApiException(ErrorCode.INTERNAL_ERROR, message);
            }
            return root;
        } catch (ApiException e) {
            throw e;
        } catch (RestClientException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "incident reports lookup failed");
        }
    }

    private IncidentReportResponse toReport(JsonNode node) {
        return new IncidentReportResponse(
                text(node, "id"),
                text(node, "run_id", "runId"),
                text(node, "incident_id", "incidentId"),
                text(node, "root_cause_id", "rootCauseId"),
                number(node, "confidence"),
                node.path("verified").asBoolean(false),
                node.path("body").isMissingNode() ? objectMapper.createObjectNode() : node.path("body"),
                instant(node, "created_at", "createdAt"));
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:8082";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (!value.isMissingNode() && !value.isNull()) {
                return value.asText();
            }
        }
        return null;
    }

    private static Double number(JsonNode node, String name) {
        JsonNode value = node.path(name);
        return value.isNumber() ? value.asDouble() : null;
    }

    private static Instant instant(JsonNode node, String... names) {
        String value = text(node, names);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
