package com.bifrost.ops.monitoring.collector;

import com.bifrost.ops.event.EventLevel;
import com.bifrost.ops.event.EventService;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kafka Connect REST API를 10초마다 폴링해 connector/task 실패를 event로 발행한다(S1).
 */
@Component
public class ConnectRestPoller {

    private static final Logger log = LoggerFactory.getLogger(ConnectRestPoller.class);
    private static final int MSG_MAX = 200;

    private final RestClient restClient;
    private final PipelineRepository pipelineRepository;
    private final EventService eventService;

    // "connectorName:taskId" → 직전 실패 여부
    private final ConcurrentHashMap<String, Boolean> failedTaskState = new ConcurrentHashMap<>();

    public ConnectRestPoller(
            @Value("${kafka-connect.rest-url:http://platform-connect-connect-api.platform-kafka.svc:8083}")
            String connectRestUrl,
            PipelineRepository pipelineRepository,
            EventService eventService) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(3000);
        this.restClient = RestClient.builder()
                .baseUrl(connectRestUrl)
                .requestFactory(factory)
                .build();
        this.pipelineRepository = pipelineRepository;
        this.eventService = eventService;
    }

    @Observed(name = "pipeline.connect.poll")
    @Scheduled(fixedRate = 10_000, initialDelay = 20_000)
    public void poll() {
        List<?> connectors;
        try {
            connectors = restClient.get().uri("/connectors").retrieve().body(List.class);
        } catch (RestClientException e) {
            log.debug("Connect REST 접근 실패(무시): {}", e.getMessage());
            return;
        }
        if (connectors == null || connectors.isEmpty()) return;

        Map<String, PipelineEntity> byConnector = buildConnectorMap();
        Set<String> currentlyFailed = new HashSet<>();

        for (Object nameObj : connectors) {
            String name = String.valueOf(nameObj);
            try {
                pollOne(name, byConnector, currentlyFailed);
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
                    eventService.record(p.getTenantId(), p.getId(), EventLevel.INFO,
                            "CONNECTOR_TASK_RECOVERED", "connector task 복구: " + key);
                }
                failedTaskState.remove(key);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void pollOne(String name, Map<String, PipelineEntity> byConnector, Set<String> currentlyFailed) {
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
                if (!Boolean.TRUE.equals(failedTaskState.get(key))) {
                    String trace = task.containsKey("trace") ? truncate(String.valueOf(task.get("trace"))) : "";
                    PipelineEntity p = byConnector.get(name);
                    if (p != null) {
                        eventService.record(p.getTenantId(), p.getId(), EventLevel.ERROR,
                                "CONNECTOR_TASK_FAILED",
                                "connector task 실패: " + key + " trace=" + trace);
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

    private static String truncate(String s) {
        if (s == null || s.length() <= MSG_MAX) return s;
        return s.substring(0, MSG_MAX) + "...";
    }
}
