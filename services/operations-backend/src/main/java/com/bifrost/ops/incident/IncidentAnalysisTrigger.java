package com.bifrost.ops.incident;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starts ai-service incident_analysis runs for newly opened incidents.
 *
 * <p>전송은 커밋 스레드(폴러/요청 스레드)를 막지 않도록 {@code executor} 로 비동기 처리한다.
 * ai-service 가 일시적으로 불가할 때 RCA 가 영구 누락되지 않도록 유한 재시도(backoff)를 둔다(#923).
 */
@Service
public class IncidentAnalysisTrigger {

    private static final Logger log = LoggerFactory.getLogger(IncidentAnalysisTrigger.class);
    private static final int MAX_ATTEMPTS = 3;

    private final RestClient restClient;
    private final AiServiceEndpoint aiServiceEndpoint;
    private final Executor executor;
    private final long retryBackoffMs;
    private final long initialDelayMs;
    private final AtomicBoolean disabledWarningLogged = new AtomicBoolean(false);

    public IncidentAnalysisTrigger(@Value("${ai-service.url:}") String aiServiceUrl,
                                   RestClient.Builder restClientBuilder,
                                   @Qualifier("applicationTaskExecutor") Executor executor,
                                   @Value("${ai-service.incident-analysis.retry-backoff-ms:1000}") long retryBackoffMs,
                                   @Value("${ai-service.incident-analysis.initial-delay-ms:0}") long initialDelayMs) {
        this.aiServiceEndpoint = AiServiceEndpoint.from(aiServiceUrl);
        this.restClient = aiServiceEndpoint.configured()
                ? restClientBuilder.baseUrl(aiServiceEndpoint.baseUrl()).build()
                : restClientBuilder.build();
        this.executor = executor;
        this.retryBackoffMs = retryBackoffMs;
        this.initialDelayMs = initialDelayMs;
    }

    public void startAfterCommit(UUID tenantId, UUID incidentId, String title, String eventMessage) {
        // 전송은 비동기로 — 재시도 backoff 가 커밋/폴러 스레드를 막지 않게 한다.
        Runnable submit = () -> executor.execute(() -> start(tenantId, incidentId, title, eventMessage));
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            submit.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submit.run();
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
        // #963 증거 누적 대기: 인시던트 생성 직후엔 커넥터 task FAILED 등 실패 증거가 아직
        // 안 쌓여 자동 RCA 가 UNKNOWN 으로 빠지기 쉽다. 설정된 초기 지연만큼 기다린 뒤 1차 분석을
        // 시작해 증거가 쌓일 시간을 준다(기본 0=즉시; executor 스레드에서 대기). 비동기 경로라
        // 커밋/폴러 스레드는 막지 않는다. (완전한 해법은 UNKNOWN 시 재분석 — 별도 후속)
        sleepInitialDelay();
        Map<String, Object> body = requestBody(tenantId, incidentId, title, eventMessage);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                restClient.post()
                        .uri("/api/v1/agent/runs")
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
                log.info("[incident] ai incident_analysis 시작: incidentId={} (attempt {}/{})",
                        incidentId, attempt, MAX_ATTEMPTS);
                return;
            } catch (RestClientException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    log.warn("[incident] ai incident_analysis 시작 실패({}회 시도): incidentId={} cause={}",
                            MAX_ATTEMPTS, incidentId, e.getMessage());
                    return;
                }
                log.warn("[incident] ai incident_analysis 재시도 {}/{}: incidentId={} cause={}",
                        attempt, MAX_ATTEMPTS, incidentId, e.getMessage());
                sleepBackoff(attempt);
            }
        }
    }

    private void sleepInitialDelay() {
        if (initialDelayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(initialDelayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void sleepBackoff(int attempt) {
        if (retryBackoffMs <= 0) {
            return;
        }
        try {
            Thread.sleep(retryBackoffMs * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static Map<String, Object> requestBody(UUID tenantId, UUID incidentId,
                                                   String title, String eventMessage) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("project_id", tenantId.toString());
        body.put("mode", "incident_analysis");
        body.put("incident_id", incidentId.toString());
        body.put("message", analysisMessage(title, eventMessage));
        // 자동 인시던트 분석에도 조치 추천을 포함한다(#692 평가). remediation_requested=true면
        // no_progress 게이트에서 얕은 폴백 대신 rca→remediation으로 진행해 RCA 깊이도 함께 개선된다.
        body.put("remediation_requested", true);
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
