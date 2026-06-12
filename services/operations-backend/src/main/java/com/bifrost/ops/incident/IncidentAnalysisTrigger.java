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
import java.util.concurrent.atomic.AtomicBoolean;

/** Starts ai-service incident_analysis runs for newly opened incidents. */
@Service
public class IncidentAnalysisTrigger {

    private static final Logger log = LoggerFactory.getLogger(IncidentAnalysisTrigger.class);

    private final RestClient restClient;
    private final AiServiceEndpoint aiServiceEndpoint;
    private final AtomicBoolean disabledWarningLogged = new AtomicBoolean(false);

    public IncidentAnalysisTrigger(@Value("${ai-service.url:}") String aiServiceUrl,
                                   RestClient.Builder restClientBuilder) {
        this.aiServiceEndpoint = AiServiceEndpoint.from(aiServiceUrl);
        this.restClient = aiServiceEndpoint.configured()
                ? restClientBuilder.baseUrl(aiServiceEndpoint.baseUrl()).build()
                : restClientBuilder.build();
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
        if (!aiServiceEndpoint.configured()) {
            if (disabledWarningLogged.compareAndSet(false, true)) {
                log.warn("[incident] ai incident_analysis disabled: {}", aiServiceEndpoint.disabledReason());
            }
            return;
        }
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
}
