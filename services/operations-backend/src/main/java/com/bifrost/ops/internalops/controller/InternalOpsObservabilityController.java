package com.bifrost.ops.internalops.controller;

import com.bifrost.ops.adapters.logstore.LokiClient;
import com.bifrost.ops.internalops.AgentHeaders;
import com.bifrost.ops.internalops.dto.AlertListResult;
import com.bifrost.ops.internalops.dto.AlertSummaryResult;
import com.bifrost.ops.internalops.dto.ConsumerLagResult;
import com.bifrost.ops.internalops.dto.IncidentSummaryResult;
import com.bifrost.ops.internalops.dto.LogSearchResult;
import com.bifrost.ops.internalops.dto.OpsEnvelope;
import com.bifrost.ops.incident.persistence.entity.IncidentEntity;
import com.bifrost.ops.incident.persistence.repository.IncidentRepository;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Agent read tool — consumer lag, logs(Loki), traces(Connect REST), incident summary(S4).
 */
@RestController
@RequestMapping("/internal/ops")
public class InternalOpsObservabilityController {

    private static final Logger log = LoggerFactory.getLogger(InternalOpsObservabilityController.class);
    private static final long ADMIN_TIMEOUT_SEC = 5L;
    private static final int DEFAULT_LOG_LIMIT = 100;
    private static final int DEFAULT_ALERT_LIMIT = 50;
    private static final int MAX_ALERT_LIMIT = 200;

    private final AdminClient adminClient;
    private final LokiClient lokiClient;
    private final JdbcTemplate jdbcTemplate;
    private final WorkspaceRepository workspaceRepository;
    private final IncidentRepository incidentRepository;
    private final RestClient connectRestClient;

    public InternalOpsObservabilityController(
            AdminClient adminClient,
            LokiClient lokiClient,
            JdbcTemplate jdbcTemplate,
            WorkspaceRepository workspaceRepository,
            IncidentRepository incidentRepository,
            @Value("${kafka-connect.rest-url:http://platform-connect-connect-api.platform-kafka.svc:8083}")
            String connectRestUrl) {
        this.adminClient = adminClient;
        this.lokiClient = lokiClient;
        this.jdbcTemplate = jdbcTemplate;
        this.workspaceRepository = workspaceRepository;
        this.incidentRepository = incidentRepository;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(3000);
        this.connectRestClient = RestClient.builder()
                .baseUrl(connectRestUrl)
                .requestFactory(factory)
                .build();
    }

    /** get_consumer_lag — consumer group 전체 lag 합계. Kafka 미연결 시 lag=0 반환. */
    @GetMapping("/projects/{projectId}/kafka/consumer-groups/{consumerGroup}/lag")
    public ResponseEntity<OpsEnvelope<ConsumerLagResult>> consumerLag(
            @PathVariable String projectId,
            @PathVariable String consumerGroup,
            HttpServletRequest request) {
        String requestId = AgentHeaders.requestId(request);
        long totalLag = fetchLag(consumerGroup);
        String source = totalLag >= 0 ? "kafka-admin" : "unavailable";
        ConsumerLagResult result = new ConsumerLagResult(consumerGroup, Math.max(0, totalLag), source);
        return ResponseEntity.ok(OpsEnvelope.ok(requestId, "get_consumer_lag", result));
    }

    /** search_logs — Loki HTTP API 실구현(S4). */
    @PostMapping("/projects/{projectId}/observability/logs/search")
    public ResponseEntity<OpsEnvelope<LogSearchResult>> searchLogs(
            @PathVariable String projectId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        String requestId = AgentHeaders.requestId(request);

        String query = body != null ? String.valueOf(body.getOrDefault("query", "{job=~\".+\"}")) : "{job=~\".+\"}";
        long endNs = Instant.now().toEpochMilli() * 1_000_000L;
        long startNs = endNs - 3_600_000_000_000L; // 기본 1시간
        int limit = body != null && body.containsKey("limit")
                ? ((Number) body.get("limit")).intValue() : DEFAULT_LOG_LIMIT;

        if (body != null && body.containsKey("start")) {
            startNs = parseNs(body.get("start"));
        }
        if (body != null && body.containsKey("end")) {
            endNs = parseNs(body.get("end"));
        }

        List<Map<String, Object>> logs = lokiClient.queryRange(query, startNs, endNs, limit);
        return ResponseEntity.ok(OpsEnvelope.ok(requestId, "search_logs", LogSearchResult.of(logs)));
    }

    /**
     * list_alerts — 별도 alert 테이블 없이 incident 저장소를 agent alert view로 노출한다.
     * FastAPI get_alerts tool의 Spring endpoint 계약이다.
     */
    @GetMapping("/projects/{projectId}/observability/alerts")
    public ResponseEntity<OpsEnvelope<AlertListResult>> listAlerts(
            @PathVariable String projectId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String limit,
            HttpServletRequest request) {
        String requestId = AgentHeaders.requestId(request);
        Optional<WorkspaceEntity> workspace = findWorkspace(projectId);
        if (workspace.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(OpsEnvelope.error(requestId, "list_alerts", "RESOURCE_NOT_FOUND",
                            "프로젝트를 찾을 수 없습니다: " + projectId, false));
        }

        Integer parsedLimit = parseAlertLimit(limit);
        if (parsedLimit != null && parsedLimit <= 0) {
            return ResponseEntity.badRequest()
                    .body(OpsEnvelope.error(requestId, "list_alerts", "VALIDATION_FAILED",
                            "limit은 1 이상이어야 합니다", false));
        }
        if (limit != null && parsedLimit == null) {
            return ResponseEntity.badRequest()
                    .body(OpsEnvelope.error(requestId, "list_alerts", "VALIDATION_FAILED",
                            "limit은 정수여야 합니다", false));
        }

        String normalizedStatus = normalizeFilter(status);
        String normalizedSeverity = normalizeFilter(severity);
        int max = effectiveAlertLimit(parsedLimit);

        List<AlertSummaryResult> alerts = listIncidents(workspace.get().getId(), normalizedStatus, normalizedSeverity, max)
                .stream()
                .map(AlertSummaryResult::fromIncident)
                .toList();

        return ResponseEntity.ok(OpsEnvelope.ok(requestId, "list_alerts", AlertListResult.of(alerts)));
    }

    /**
     * query_traces — Connect REST task exception trace(S4).
     *
     * <p>설계상 query_traces(Observability)는 Tempo 분산 trace summary 자리이고, connector task 예외는
     * {@link #getConnectorTaskTrace}로 분리한다(#368 realign 1단계). 무중단을 위해 현재는 두 엔드포인트가
     * 동일 본문을 제공하며, 이후 query_traces는 Tempo 기반으로 교체 예정.
     */
    @GetMapping("/projects/{projectId}/connectors/{connectorName}/traces")
    public ResponseEntity<OpsEnvelope<Map<String, Object>>> queryTraces(
            @PathVariable String projectId,
            @PathVariable String connectorName,
            HttpServletRequest request) {
        String requestId = AgentHeaders.requestId(request);
        return ResponseEntity.ok(OpsEnvelope.ok(requestId, "query_traces", connectorTaskTraceBody(connectorName)));
    }

    /**
     * get_connector_task_trace — Kafka Connect task status의 exception trace를 RCA evidence로 노출
     * (설계 tool-catalog §8.3). #368: query_traces가 임시로 맡던 역할을 이 도구로 분리.
     */
    @GetMapping("/projects/{projectId}/connectors/{connectorName}/task-trace")
    public ResponseEntity<OpsEnvelope<Map<String, Object>>> getConnectorTaskTrace(
            @PathVariable String projectId,
            @PathVariable String connectorName,
            HttpServletRequest request) {
        String requestId = AgentHeaders.requestId(request);
        return ResponseEntity.ok(OpsEnvelope.ok(requestId, "get_connector_task_trace", connectorTaskTraceBody(connectorName)));
    }

    /** Connect REST {@code /connectors/{name}/status}의 task {@code trace}(예외) 필드를 모은 본문. 미연결 시 빈 결과 + note. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> connectorTaskTraceBody(String connectorName) {
        try {
            Map<String, Object> status = connectRestClient.get()
                    .uri("/connectors/{name}/status", connectorName)
                    .retrieve()
                    .body(Map.class);

            List<Map<String, Object>> tasks = status != null
                    ? (List<Map<String, Object>>) status.get("tasks") : List.of();

            List<Map<String, Object>> traces = tasks == null ? List.of() : tasks.stream()
                    .filter(t -> t.containsKey("trace"))
                    .map(t -> Map.of(
                            "taskId", t.get("id"),
                            "state", t.get("state"),
                            "trace", t.get("trace")))
                    .collect(Collectors.toList());

            return Map.of("connector", connectorName, "traces", traces);
        } catch (RestClientException e) {
            log.debug("connector task trace Connect REST 실패(무시): connector={} cause={}", connectorName, e.getMessage());
            return Map.of("connector", connectorName, "traces", List.of(), "note", "Connect REST unavailable");
        }
    }

    /**
     * get_incident_summary — incidents 테이블 직접 조회(S4).
     * S2(#258) V16 마이그레이션 적용 후 동작한다.
     */
    @GetMapping("/incidents/{incidentId}/summary")
    public ResponseEntity<OpsEnvelope<IncidentSummaryResult>> incidentSummary(
            @PathVariable String incidentId,
            HttpServletRequest request) {
        String requestId = AgentHeaders.requestId(request);
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT id, severity, status, title, rca, opened_at, resolved_at FROM incidents WHERE id = ?::uuid",
                    incidentId);
            IncidentSummaryResult result = new IncidentSummaryResult(
                    incidentId,
                    String.valueOf(row.get("status")),
                    buildSummaryNote(row));
            return ResponseEntity.ok(OpsEnvelope.ok(requestId, "get_incident_summary", result));
        } catch (Exception e) {
            log.debug("incident 조회 실패(무시): id={} cause={}", incidentId, e.getMessage());
            return ResponseEntity.ok(OpsEnvelope.ok(requestId, "get_incident_summary",
                    IncidentSummaryResult.stub(incidentId)));
        }
    }

    private static String buildSummaryNote(Map<String, Object> row) {
        return "severity=" + row.get("severity")
                + " title=" + row.get("title")
                + (row.get("rca") != null ? " rca=" + row.get("rca") : "");
    }

    private static long parseNs(Object val) {
        if (val instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(val)); } catch (NumberFormatException e) {
            return Instant.parse(String.valueOf(val)).toEpochMilli() * 1_000_000L;
        }
    }

    private Optional<WorkspaceEntity> findWorkspace(String projectId) {
        try {
            Optional<WorkspaceEntity> byId = workspaceRepository.findById(UUID.fromString(projectId));
            if (byId.isPresent()) {
                return byId;
            }
        } catch (IllegalArgumentException ignored) {
            // projectId may be a namespace slug.
        }
        return workspaceRepository.findByNamespace(projectId);
    }

    private static String normalizeFilter(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }

    private static int effectiveAlertLimit(Integer limit) {
        return Math.min(limit == null ? DEFAULT_ALERT_LIMIT : limit, MAX_ALERT_LIMIT);
    }

    private List<IncidentEntity> listIncidents(
            UUID tenantId,
            String status,
            String severity,
            int limit) {
        PageRequest page = PageRequest.of(0, limit);
        if (status != null && severity != null) {
            return incidentRepository.findByTenantIdAndStatusAndSeverityOrderByOpenedAtDesc(
                    tenantId, status, severity, page);
        }
        if (status != null) {
            return incidentRepository.findByTenantIdAndStatusOrderByOpenedAtDesc(tenantId, status, page);
        }
        if (severity != null) {
            return incidentRepository.findByTenantIdAndSeverityOrderByOpenedAtDesc(tenantId, severity, page);
        }
        return incidentRepository.findByTenantIdOrderByOpenedAtDesc(tenantId, page);
    }

    private static Integer parseAlertLimit(String limit) {
        if (limit == null || limit.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(limit);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private long fetchLag(String groupId) {
        try {
            Map<TopicPartition, OffsetAndMetadata> committed = adminClient
                    .listConsumerGroupOffsets(groupId)
                    .partitionsToOffsetAndMetadata()
                    .get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (committed.isEmpty()) return 0L;

            Map<TopicPartition, OffsetSpec> endReqs = committed.keySet().stream()
                    .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends = adminClient
                    .listOffsets(endReqs).all().get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);

            long lag = 0L;
            for (Map.Entry<TopicPartition, OffsetAndMetadata> e : committed.entrySet()) {
                long end = ends.containsKey(e.getKey()) ? ends.get(e.getKey()).offset() : 0L;
                lag += Math.max(0L, end - e.getValue().offset());
            }
            return lag;
        } catch (Exception e) {
            log.warn("[InternalOps] consumer lag 조회 실패: group={} cause={}", groupId, e.getMessage());
            return -1L;
        }
    }
}
