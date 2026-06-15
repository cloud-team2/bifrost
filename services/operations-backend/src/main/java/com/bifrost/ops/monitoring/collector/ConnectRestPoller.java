package com.bifrost.ops.monitoring.collector;

import com.bifrost.ops.event.EventLevel;
import com.bifrost.ops.event.EventService;
import com.bifrost.ops.incident.IncidentGroupingKeys;
import com.bifrost.ops.incident.IncidentService;
import com.bifrost.ops.pipeline.PipelineLifecycle;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Kafka Connect REST API를 10초마다 폴링해 connector/task 실패를 event로 발행한다(S1).
 */
@Component
public class ConnectRestPoller {

    private static final Logger log = LoggerFactory.getLogger(ConnectRestPoller.class);

    private final RestClient restClient;
    private final PipelineRepository pipelineRepository;
    private final EventService eventService;
    private final IncidentService incidentService;
    private final boolean connectRestUrlConfigured;
    private final AtomicBoolean missingRestUrlWarned = new AtomicBoolean(false);
    private final AtomicBoolean invalidRestUrlWarned = new AtomicBoolean(false);

    // "connectorName:taskId" → 직전 실패 여부
    private final ConcurrentHashMap<String, Boolean> failedTaskState = new ConcurrentHashMap<>();

    @Autowired
    public ConnectRestPoller(
            @Value("${kafka-connect.rest-url:}")
            String connectRestUrl,
            PipelineRepository pipelineRepository,
            EventService eventService,
            IncidentService incidentService) {
        this(restClient(connectRestUrl), pipelineRepository, eventService, incidentService,
                hasText(connectRestUrl));
    }

    ConnectRestPoller(RestClient restClient, PipelineRepository pipelineRepository,
                      EventService eventService, IncidentService incidentService) {
        this(restClient, pipelineRepository, eventService, incidentService, true);
    }

    private ConnectRestPoller(RestClient restClient, PipelineRepository pipelineRepository,
                              EventService eventService, IncidentService incidentService,
                              boolean connectRestUrlConfigured) {
        this.restClient = restClient;
        this.pipelineRepository = pipelineRepository;
        this.eventService = eventService;
        this.incidentService = incidentService;
        this.connectRestUrlConfigured = connectRestUrlConfigured;
    }

    private static RestClient restClient(String connectRestUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(3000);
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(factory);
        if (!hasText(connectRestUrl)) {
            return builder.build();
        }
        try {
            return builder
                    .baseUrl(connectRestUrl.strip())
                    .build();
        } catch (IllegalArgumentException e) {
            return RestClient.builder()
                    .requestFactory(factory)
                    .build();
        }
    }

    @Observed(name = "pipeline.connect.poll")
    @Scheduled(fixedRate = 10_000, initialDelay = 20_000)
    public void poll() {
        if (!connectRestUrlConfigured) {
            if (missingRestUrlWarned.compareAndSet(false, true)) {
                log.warn("kafka-connect.rest-url is blank; skipping Connect REST polling. "
                        + "Set KAFKA_CONNECT_REST_URL in the deployment environment.");
            }
            return;
        }
        List<?> connectors;
        try {
            connectors = restClient.get().uri("/connectors").retrieve().body(List.class);
        } catch (IllegalArgumentException e) {
            if (invalidRestUrlWarned.compareAndSet(false, true)) {
                log.warn("kafka-connect.rest-url is invalid; skipping Connect REST polling. "
                        + "Set KAFKA_CONNECT_REST_URL to an absolute URL. cause={}", e.getMessage());
            }
            return;
        } catch (RestClientException e) {
            log.debug("Connect REST 접근 실패(무시): {}", e.getMessage());
            return;
        }
        if (connectors == null || connectors.isEmpty()) return;

        Map<String, PipelineEntity> byConnector = buildConnectorMap();
        Set<String> currentlyFailed = new HashSet<>();
        Map<String, Set<String>> currentFailuresByConnector = new HashMap<>();

        for (Object nameObj : connectors) {
            String name = String.valueOf(nameObj);
            try {
                pollOne(name, byConnector, currentlyFailed, currentFailuresByConnector);
            } catch (Exception e) {
                log.debug("connector status 조회 실패(무시): name={} cause={}", name, e.getMessage());
            }
        }

        // 회복된 task 이벤트
        for (String key : failedTaskState.keySet()) {
            if (!currentlyFailed.contains(key)) {
                String connectorName = key.split(":")[0];
                PipelineEntity p = byConnector.get(connectorName);
                if (p != null) {
                    String message = "connector task 복구: " + key;
                    if (currentFailuresByConnector.getOrDefault(connectorName, Set.of()).isEmpty()) {
                        boolean resolved = incidentService.onRecovery(p.getTenantId(),
                                IncidentGroupingKeys.connectorWorker(connectorName),
                                "CONNECTOR_TASK_RECOVERED", message, p.getId());
                        if (!resolved) {
                            eventService.record(p.getTenantId(), p.getId(), EventLevel.INFO,
                                    "CONNECTOR_TASK_RECOVERED", message);
                        }
                    } else {
                        eventService.record(p.getTenantId(), p.getId(), EventLevel.INFO,
                                "CONNECTOR_TASK_RECOVERED", message);
                    }
                }
                failedTaskState.remove(key);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void pollOne(String name, Map<String, PipelineEntity> byConnector, Set<String> currentlyFailed,
                         Map<String, Set<String>> currentFailuresByConnector) {
        Map<String, Object> status = restClient.get()
                .uri("/connectors/{name}/status", name)
                .retrieve()
                .body(Map.class);
        if (status == null) return;

        List<Map<String, Object>> tasks = (List<Map<String, Object>>) status.get("tasks");
        if (tasks == null) return;

        for (Map<String, Object> task : tasks) {
            String state = String.valueOf(task.get("state"));
            String key = name + ":" + task.get("id");

            if ("FAILED".equals(state)) {
                currentlyFailed.add(key);
                currentFailuresByConnector.computeIfAbsent(name, ignored -> new HashSet<>()).add(key);
                if (!Boolean.TRUE.equals(failedTaskState.get(key))) {
                    PipelineEntity p = byConnector.get(name);
                    if (p != null) {
                        // (#596) UUID·raw trace 대신 파이프라인명 + 역할 + 정제 요약.
                        boolean isSink = name.equals(p.getSinkConnectorName());
                        String role = isSink ? "싱크" : "소스";
                        String trace = task.containsKey("trace") ? String.valueOf(task.get("trace")) : null;
                        String summary = com.bifrost.ops.pipeline.status.ConnectorErrorMessages.summarize(trace);
                        String message = "'" + p.getName() + "' " + role + " 커넥터 task#"
                                + task.get("id") + " 실패: " + summary;

                        // (#692) DB 연결 실패가 원인인 커넥터 실패는 datasource 장애의 증상이므로
                        // datasource grouping 으로 묶어 DB 헬스 프로브 인시던트와 dedup(중복 방지).
                        // 그 외(컨버터·설정·CDC 권한 등)는 connector grouping 유지 → restart_connector 조치 보존.
                        UUID dsId = isSink ? p.getSinkDatasourceId() : p.getSourceDatasourceId();
                        if (com.bifrost.ops.pipeline.status.ConnectorErrorMessages.isDbConnectionFailure(trace)
                                && dsId != null) {
                            incidentService.onThresholdViolation(p.getTenantId(),
                                    IncidentGroupingKeys.datasource(dsId),
                                    "DATABASE", dsId, EventLevel.ERROR,
                                    "Pipeline '" + p.getName() + "' status ERROR",
                                    "CONNECTOR_TASK_FAILED", message, p.getId());
                        } else {
                            incidentService.onThresholdViolation(p.getTenantId(),
                                    IncidentGroupingKeys.connectorWorker(name),
                                    "CONNECTOR", null, EventLevel.ERROR,
                                    "Pipeline '" + p.getName() + "' connector task failed",
                                    "CONNECTOR_TASK_FAILED", message, p.getId());
                        }
                    } else {
                        log.warn("connector task 실패 (파이프라인 미매핑): {}", key);
                    }
                    failedTaskState.put(key, true);
                }
            }
        }
    }

    private Map<String, PipelineEntity> buildConnectorMap() {
        Map<String, PipelineEntity> map = new HashMap<>();
        for (PipelineEntity p : pipelineRepository.findAll()) {
            if (p.getSourceConnectorName() != null) map.put(p.getSourceConnectorName(), p);
            if (p.getSinkConnectorName() != null) map.put(p.getSinkConnectorName(), p);
        }
        return map;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

}
