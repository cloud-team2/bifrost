package com.bifrost.ops.incident;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Starts ai-service incident_analysis runs for newly opened incidents. */
@Service
public class IncidentAnalysisTrigger {

    private static final Logger log = LoggerFactory.getLogger(IncidentAnalysisTrigger.class);

    private final RestClient restClient;

    public IncidentAnalysisTrigger(@Value("${ai-service.url:http://localhost:8082}") String aiServiceUrl,
                                   RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.baseUrl(stripTrailingSlash(aiServiceUrl)).build();
    }

    public void startAfterCommit(UUID tenantId, UUID incidentId, String title, String eventMessage) {
        Runnable start = () -> start(tenantId, incidentId, title, eventMessage);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            start.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                start.run();
            }
        });
    }

    void start(UUID tenantId, UUID incidentId, String title, String eventMessage) {
        try {
            restClient.post()
                    .uri("/api/v1/agent/runs")
                    .body(requestBody(tenantId, incidentId, title, eventMessage))
                    .retrieve()
                    .toBodilessEntity();
            log.info("[incident] ai incident_analysis 시작: incidentId={}", incidentId);
        } catch (RestClientException e) {
            log.warn("[incident] ai incident_analysis 시작 실패: incidentId={} cause={}", incidentId, e.getMessage());
        }
    }

    private static Map<String, Object> requestBody(UUID tenantId, UUID incidentId,
                                                   String title, String eventMessage) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("project_id", tenantId.toString());
        body.put("mode", "incident_analysis");
        body.put("incident_id", incidentId.toString());
        body.put("message", analysisMessage(title, eventMessage));
        body.put("remediation_requested", false);
        body.put("stream", false);
        return body;
    }

    private static String analysisMessage(String title, String eventMessage) {
        if (eventMessage == null || eventMessage.isBlank()) {
            return title == null ? "" : title;
        }
        if (title == null || title.isBlank()) {
            return eventMessage;
        }
        return title + "\n" + eventMessage;
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:8082";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
