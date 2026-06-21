package com.bifrost.ops.internalops.controller;

import com.bifrost.ops.adapters.logstore.LokiClient;
import com.bifrost.ops.event.EventLevel;
import com.bifrost.ops.event.persistence.entity.EventEntity;
import com.bifrost.ops.event.persistence.repository.EventRepository;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.internalops.AgentHeaders;
import com.bifrost.ops.internalops.WorkspaceLookup;
import com.bifrost.ops.internalops.dto.AlertListResult;
import com.bifrost.ops.internalops.dto.AlertSummaryResult;
import com.bifrost.ops.internalops.dto.ConsumerLagResult;
import com.bifrost.ops.internalops.dto.ConsumerGroupsResult;
import com.bifrost.ops.internalops.dto.EventIncidentSummaryResult;
import com.bifrost.ops.internalops.dto.IncidentSummaryResult;
import com.bifrost.ops.internalops.dto.LogSearchResult;
import com.bifrost.ops.internalops.dto.MetricsResult;
import com.bifrost.ops.internalops.dto.OpsEnvelope;
import com.bifrost.ops.internalops.dto.TraceSummaryResult;
import com.bifrost.ops.incident.IncidentGroupingKeys;
import com.bifrost.ops.incident.persistence.entity.IncidentEntity;
import com.bifrost.ops.incident.persistence.repository.IncidentRepository;
import com.bifrost.ops.monitoring.query.ObservabilityMetricsQuery;
import com.bifrost.ops.monitoring.query.TraceQuery;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.provisioning.dto.ConnectorKind;
import com.bifrost.ops.provisioning.persistence.entity.ConnectorEntity;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final WorkspaceRepository workspaceRepository;
    private final PipelineRepository pipelineRepository;
    private final ConnectorRepository connectorRepository;
    private final IncidentRepository incidentRepository;
    private final EventRepository eventRepository;
    private final ObservabilityMetricsQuery metricsQuery;
    private final TraceQuery traceQuery;
    private final String kafkaNamespace;
    private final RestClient connectRestClient;

    public InternalOpsObservabilityController(
            AdminClient adminClient,
            LokiClient lokiClient,
            WorkspaceRepository workspaceRepository,
            PipelineRepository pipelineRepository,
            ConnectorRepository connectorRepository,
            IncidentRepository incidentRepository,
            EventRepository eventRepository,
            ObservabilityMetricsQuery metricsQuery,
            TraceQuery traceQuery,
            @Value("${kafka-cluster.namespace:platform-kafka}") String kafkaNamespace,
            @Value("${kafka-connect.rest-url:http://platform-connect-connect-api.platform-kafka.svc:8083}")
            String connectRestUrl) {
        this.adminClient = adminClient;
        this.lokiClient = lokiClient;
        this.workspaceRepository = workspaceRepository;
        this.pipelineRepository = pipelineRepository;
        this.connectorRepository = connectorRepository;
        this.incidentRepository = incidentRepository;
        this.eventRepository = eventRepository;
        this.metricsQuery = metricsQuery;
        this.traceQuery = traceQuery;
        this.kafkaNamespace = nonBlankOrDefault(kafkaNamespace, "platform-kafka");
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(3000);
        this.connectRestClient = RestClient.builder()
                .baseUrl(connectRestUrl)
                .requestFactory(factory)
                .build();
    }

    /** get_consumer_lag — consumer group 전체 lag 합계. Kafka 조회 실패 시 오류 envelope 반환. */
    @GetMapping("/projects/{projectId}/kafka/consumer-groups/{consumerGroup}/lag")
    public ResponseEntity<OpsEnvelope<ConsumerLagResult>> consumerLag(
            @PathVariable String projectId,
            @PathVariable String consumerGroup,
            HttpServletRequest request) {
        String requestId = AgentHeaders.requestId(request);
        try {
            requireOwnedConnectConsumerGroup(projectId, consumerGroup);
        } catch (ApiException e) {
            return apiError(requestId, "get_consumer_lag", e);
        }
        LagSnapshot lag = fetchLagSnapshot(consumerGroup);
        if (lag.error() != null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(OpsEnvelope.error(requestId, "get_consumer_lag", "UPSTREAM_UNAVAILABLE",
                            lag.error(), true, "retry_kafka_admin"));
        }
        ConsumerLagResult result = ConsumerLagResult.fromSnapshot(
                consumerGroup,
                lag.lag() == null ? 0L : lag.lag(),
                "kafka-admin",
                lag.partitions(),
                Instant.now());
        return ResponseEntity.ok(OpsEnvelope.ok(requestId, "get_consumer_lag", result));
    }

    /** get_consumer_groups — project scope Kafka Connect consumer group 목록 + lag. */
    @GetMapping("/projects/{projectId}/kafka/consumer-groups")
    public ResponseEntity<OpsEnvelope<ConsumerGroupsResult>> consumerGroups(
            @PathVariable String projectId,
            HttpServletRequest request) {
        String requestId = AgentHeaders.requestId(request);
        WorkspaceEntity workspace;
        try {
            workspace = requireWorkspace(projectId);
        } catch (ApiException e) {
            return apiError(requestId, "get_consumer_groups", e);
        }

        Map<UUID, String> pipelineNames = pipelineRepository
                .findByTenantIdOrderByCreatedAtDesc(workspace.getId())
                .stream()
                .collect(Collectors.toMap(
                        p -> p.getId(),
                        p -> p.getName(),
                        (left, right) -> left));

        List<ConnectorEntity> sinkConnectors = connectorRepository.findByTenantIdOrderByCrName(workspace.getId())
                .stream()
                .filter(connector -> connector.getKind() == ConnectorKind.SINK)
                .toList();

        List<String> groups = sinkConnectors.stream()
                .map(connector -> "connect-" + connector.getCrName())
                .toList();
        DescribeGroupsSnapshot described = describeGroups(groups);
        List<ConsumerGroupsResult.ConsumerGroup> rows = new ArrayList<>();
        String describeError = described.error();
        String resultError = describeError;
        for (ConnectorEntity connector : sinkConnectors) {
            String group = "connect-" + connector.getCrName();
            ConsumerGroupDescription description = described.groups().get(group);
            LagSnapshot lag = describeError == null ? fetchLagSnapshot(group) : new LagSnapshot(null, List.of(), describeError);
            if (resultError == null && lag.error() != null) {
                resultError = lag.error();
            }
            String error = lag.error() != null ? lag.error() : describeError;
            rows.add(new ConsumerGroupsResult.ConsumerGroup(
                    group,
                    description == null ? "UNKNOWN" : description.state().toString(),
                    lag.lag(),
                    pipelineNames.getOrDefault(connector.getPipelineId(), connector.getCrName()),
                    error));
        }

        return ResponseEntity.ok(OpsEnvelope.ok(requestId, "get_consumer_groups",
                new ConsumerGroupsResult(rows, resultError)));
    }

    /** search_logs — Loki HTTP API 실구현(S4). */
    @PostMapping("/projects/{projectId}/observability/logs/search")
    public ResponseEntity<OpsEnvelope<LogSearchResult>> searchLogs(
            @PathVariable String projectId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        String requestId = AgentHeaders.requestId(request);
        WorkspaceEntity workspace;
        try {
            workspace = requireWorkspace(projectId);
        } catch (ApiException e) {
            return apiError(requestId, "search_logs", e);
        }

        List<String> connectorNames = ownedConnectorNames(workspace.getId());
        String query = scopedLogQuery(
                workspace,
                body != null ? String.valueOf(body.getOrDefault("query", "")) : "",
                connectorNames,
                kafkaNamespace);
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
        return ResponseEntity.ok(OpsEnvelope.ok(requestId, "search_logs", LogSearchResult.of(logs, connectorNames)));
    }

    /**
     * query_metrics — Prometheus range 메트릭 조회(S4, #391). ai-service get_metrics tool 대응.
     *
     * <p>{@code prometheus.enabled=false}(기본)면 well-formed stub을, true면 실 시계열을 반환한다.
     * 어떤 경우에도 OpsEnvelope + MetricsResult로 200을 반환해 RCA evidence 수집을 막지 않는다.
     */
    @GetMapping("/projects/{projectId}/observability/metrics")
    public ResponseEntity<OpsEnvelope<MetricsResult>> queryMetrics(
            @PathVariable String projectId,
            @RequestParam String metric,
            @RequestParam(required = false) String timeRange,
            HttpServletRequest request) {
        String requestId = AgentHeaders.requestId(request);
        WorkspaceEntity workspace;
        try {
            workspace = requireWorkspace(projectId);
        } catch (ApiException e) {
            return apiError(requestId, "query_metrics", e);
        }
        MetricsResult result = metricsQuery.query(workspace, metric, timeRange);
        return ResponseEntity.ok(OpsEnvelope.ok(requestId, "query_metrics", result));
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
            @RequestParam(name = "pipeline_id", required = false) String pipelineId,
            @RequestParam(name = "connector_name", required = false) String connectorName,
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

        ObservabilityScope scope;
        try {
            scope = resolveObservabilityScope(workspace.get(), pipelineId, connectorName);
        } catch (ApiException e) {
            return apiError(requestId, "list_alerts", e);
        }

        List<AlertSummaryResult> alerts = listIncidents(workspace.get().getId(), normalizedStatus, normalizedSeverity, max, scope)
                .stream()
                .map(AlertSummaryResult::fromIncident)
                .toList();

        return ResponseEntity.ok(OpsEnvelope.ok(requestId, "list_alerts", AlertListResult.of(alerts)));
    }

    /** analyze_event_log — window/level 기준 event + incident 요약. */
    @GetMapping("/projects/{projectId}/observability/events/summary")
    public ResponseEntity<OpsEnvelope<EventIncidentSummaryResult>> eventIncidentSummary(
            @PathVariable String projectId,
            @RequestParam(required = false, defaultValue = "2h") String window,
            @RequestParam(required = false, defaultValue = "warn+") String level,
            @RequestParam(name = "pipeline_id", required = false) String pipelineId,
            @RequestParam(name = "connector_name", required = false) String connectorName,
            HttpServletRequest request) {
        String requestId = AgentHeaders.requestId(request);
        WorkspaceEntity workspace;
        try {
            workspace = requireWorkspace(projectId);
        } catch (ApiException e) {
            return apiError(requestId, "analyze_event_log", e);
        }

        Instant since = Instant.now().minusSeconds(parseWindowSeconds(window));
        EventLevel threshold = parseLevelThreshold(level);
        ObservabilityScope scope;
        try {
            scope = resolveObservabilityScope(workspace, pipelineId, connectorName);
        } catch (ApiException e) {
            return apiError(requestId, "analyze_event_log", e);
        }
        List<IncidentEntity> openIncidents = openIncidents(workspace.getId(), scope, threshold, since);

        List<IncidentEntity> criticalIncidents = openIncidents.stream()
                .filter(incident -> severityRank(incident.getSeverity()) >= severityRank("ERROR"))
                .toList();

        List<EventIncidentSummaryResult.CriticalIncident> critical = criticalIncidents.stream()
                .limit(20)
                .map(incident -> new EventIncidentSummaryResult.CriticalIncident(
                        incident.getId().toString(),
                        incident.getSeverity(),
                        incident.getStatus(),
                        incident.getTitle(),
                        incident.getOpenedAt()))
                .toList();

        List<EventIncidentSummaryResult.WarningEvent> warnings = warningEvents(
                        workspace.getId(), scope, threshold, since, openIncidents)
                .stream()
                .map(event -> new EventIncidentSummaryResult.WarningEvent(
                        event.getId().toString(),
                        event.getLevel().name(),
                        event.getType(),
                        event.getMessage(),
                        event.getPipelineId() == null ? null : event.getPipelineId().toString(),
                        event.getIncidentId() == null ? null : event.getIncidentId().toString(),
                        event.getCreatedAt()))
                .toList();

        EventIncidentSummaryResult result = new EventIncidentSummaryResult(
                normalizeWindow(window),
                normalizeLevel(level),
                openIncidents.size(),
                criticalIncidents.size(),
                critical,
                warnings);
        return ResponseEntity.ok(OpsEnvelope.ok(requestId, "analyze_event_log", result));
    }

    /**
     * query_traces — Tempo 분산 trace summary(#373). 변경 이벤트가 source→topic→sink로 흐르며
     * 어디서 지연/실패했는지를 한 trace로 요약한다(RCA 근거). connector task 예외는
     * {@link #getConnectorTaskTrace}(#368)로 분리되어 있다.
     *
     * <p>{@code tempo.enabled=false}(기본)·미발견·조회 실패 시 well-formed stub을 반환한다(파싱 안전).
     */
    @GetMapping("/projects/{projectId}/connectors/{connectorName}/traces")
    public ResponseEntity<OpsEnvelope<TraceSummaryResult>> queryTraces(
            @PathVariable String projectId,
            @PathVariable String connectorName,
            HttpServletRequest request) {
        String requestId = AgentHeaders.requestId(request);
        try {
            requireOwnedConnector(projectId, connectorName);
        } catch (ApiException e) {
            return apiError(requestId, "query_traces", e);
        }
        TraceSummaryResult summary = traceQuery.query(connectorName, resolveConnectorTopic(connectorName));
        return ResponseEntity.ok(OpsEnvelope.ok(requestId, "query_traces", summary));
    }

    /** Connect REST {@code /connectors/{name}/topics}의 첫 topic(데이터플레인 trace join 키). 실패/없으면 null. */
    @SuppressWarnings("unchecked")
    private String resolveConnectorTopic(String connectorName) {
        try {
            Map<String, Object> body = connectRestClient.get()
                    .uri("/connectors/{name}/topics", connectorName)
                    .retrieve()
                    .body(Map.class);
            if (body == null) {
                return null;
            }
            Object entry = body.get(connectorName);
            if (!(entry instanceof Map<?, ?> m)) {
                return null;
            }
            Object topics = ((Map<String, Object>) m).get("topics");
            if (topics instanceof List<?> list && !list.isEmpty()) {
                return String.valueOf(list.get(0));
            }
            return null;
        } catch (RestClientException | IllegalArgumentException e) {
            log.debug("connector topics Connect REST 실패(무시): connector={} cause={}", connectorName, e.getMessage());
            return null;
        }
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
        try {
            requireOwnedConnector(projectId, connectorName);
        } catch (ApiException e) {
            return apiError(requestId, "get_connector_task_trace", e);
        }
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
                    .map(t -> taskTraceSummaryEntry(t.get("id"), t.get("state"), t.get("trace")))
                    .collect(Collectors.toList());

            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("connector", connectorName);
            body.put("traces", traces);
            body.put("summary", connectorTaskTraceSummary(connectorName, traces));
            return body;
        } catch (RestClientException | IllegalArgumentException e) {
            log.debug("connector task trace Connect REST 실패(무시): connector={} cause={}", connectorName, e.getMessage());
            return Map.of(
                    "connector", connectorName,
                    "traces", List.of(),
                    "summary", "connector task trace unavailable for connector " + connectorName,
                    "note", "Connect REST unavailable");
        }
    }

    private static String connectorTaskTraceSummary(String connectorName, List<Map<String, Object>> traces) {
        if (traces == null || traces.isEmpty()) {
            return "connector task trace: no task trace available for connector " + connectorName;
        }
        List<String> classes = traces.stream()
                .map(InternalOpsObservabilityController::traceClass)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        long failedTasks = traces.stream()
                .filter(t -> "FAILED".equalsIgnoreCase(String.valueOf(t.get("state"))))
                .count();
        if (failedTasks == 0) {
            return "connector task trace available; no failed task confirmed"
                    + " connector=" + connectorName
                    + " traces=" + traces.size()
                    + " failedTasks=0"
                    + (!classes.isEmpty() ? " classes=" + classes : "");
        }
        return "connector task status FAILED; task trace 또는 worker log exception stack summary"
                + " connector=" + connectorName
                + " traces=" + traces.size()
                + " failedTasks=" + failedTasks
                + (!classes.isEmpty() ? " classes=" + classes : "");
    }

    private static Map<String, Object> taskTraceSummaryEntry(Object taskId, Object state, Object trace) {
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("taskId", taskId);
        entry.put("state", state);
        entry.put("hasTrace", trace != null && !String.valueOf(trace).isBlank());
        String traceClass = classifyTrace(String.valueOf(trace == null ? "" : trace));
        if (traceClass != null) {
            entry.put("traceClass", traceClass);
        }
        return entry;
    }

    private static String traceClass(Map<String, Object> traceEntry) {
        Object existing = traceEntry.get("traceClass");
        if (existing != null && !String.valueOf(existing).isBlank()) {
            return String.valueOf(existing);
        }
        return classifyTrace(String.valueOf(traceEntry.getOrDefault("trace", "")));
    }

    private static String classifyTrace(String trace) {
        String normalized = trace.toLowerCase();
        if (containsAny(normalized, "accessdenied", "permission denied", "token expired",
                "authentication failed", "authorization failed", "password authentication failed",
                "인증 실패", "권한 거부")) {
            return "auth";
        }
        if (containsAny(normalized, "schema", "serialization", "deserialization", "incompatible",
                "스키마", "역직렬화")) {
            return "schema";
        }
        if (containsAny(normalized, "constraint", "duplicate key", "not null", "foreign key",
                "sqlintegrityconstraintviolation")) {
            return "constraint";
        }
        if (containsAny(normalized, "timeout", "timed out", "connection refused", "no route to host")) {
            return "timeout";
        }
        if (containsAny(normalized, "config validation", "invalid option", "unknown config", "invalid converter")) {
            return "config";
        }
        return null;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * get_incident_summary — incidents 테이블 직접 조회(S4).
     * S2(#258) V16 마이그레이션 적용 후 동작한다.
     */
    @GetMapping("/projects/{projectId}/incidents/{incidentId}/summary")
    public ResponseEntity<OpsEnvelope<IncidentSummaryResult>> incidentSummary(
            @PathVariable String projectId,
            @PathVariable String incidentId,
            HttpServletRequest request) {
        String requestId = AgentHeaders.requestId(request);
        try {
            WorkspaceEntity workspace = requireWorkspace(projectId);
            IncidentEntity incident = incidentRepository.findByIdAndTenantId(UUID.fromString(incidentId), workspace.getId())
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                            "incident not found in project: " + incidentId));
            IncidentSummaryResult result = new IncidentSummaryResult(
                    incidentId,
                    incident.getStatus(),
                    buildSummaryNote(incident),
                    resolveIncidentConnectors(workspace.getId(), incident));
            return ResponseEntity.ok(OpsEnvelope.ok(requestId, "get_incident_summary", result));
        } catch (ApiException e) {
            if (e.code() == ErrorCode.WORKSPACE_NOT_FOUND || e.code() == ErrorCode.WORKSPACE_FORBIDDEN) {
                return apiError(requestId, "get_incident_summary", e);
            }
            log.debug("incident 조회 실패(무시): id={} cause={}", incidentId, e.getMessage());
            return ResponseEntity.ok(OpsEnvelope.ok(requestId, "get_incident_summary",
                    IncidentSummaryResult.stub(incidentId)));
        } catch (Exception e) {
            log.debug("incident 조회 실패(무시): id={} cause={}", incidentId, e.getMessage());
            return ResponseEntity.ok(OpsEnvelope.ok(requestId, "get_incident_summary",
                    IncidentSummaryResult.stub(incidentId)));
        }
    }

    /** 인시던트 영향 파이프라인의 source/sink 커넥터를 해석한다(#925, RCA 도구 체이닝용). */
    private java.util.List<IncidentSummaryResult.ConnectorRef> resolveIncidentConnectors(
            UUID tenantId, IncidentEntity incident) {
        try {
            java.util.LinkedHashSet<UUID> pipelineIds = new java.util.LinkedHashSet<>();
            eventRepository.findByTenantIdAndIncidentIdOrderByCreatedAtDesc(tenantId, incident.getId())
                    .forEach(e -> {
                        if (e.getPipelineId() != null) {
                            pipelineIds.add(e.getPipelineId());
                        }
                    });
            String sourceType = incident.getSourceType();
            UUID sourceId = incident.getSourceId();
            if (sourceId != null && "PIPELINE".equalsIgnoreCase(sourceType)) {
                pipelineIds.add(sourceId);
            } else if (sourceId != null && "DATABASE".equalsIgnoreCase(sourceType)) {
                pipelineRepository.findBySourceDatasourceIdOrSinkDatasourceId(sourceId, sourceId)
                        .forEach(p -> pipelineIds.add(p.getId()));
            }
            java.util.List<IncidentSummaryResult.ConnectorRef> connectors = new java.util.ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (UUID pid : pipelineIds) {
                pipelineRepository.findByIdAndTenantId(pid, tenantId).ifPresent(p -> {
                    addConnectorRef(connectors, seen, p.getSourceConnectorName(), "source", p);
                    addConnectorRef(connectors, seen, p.getSinkConnectorName(), "sink", p);
                });
            }
            return connectors;
        } catch (Exception e) {
            log.debug("incident 커넥터 해석 실패(무시): id={} cause={}", incident.getId(), e.getMessage());
            return java.util.List.of();
        }
    }

    private static void addConnectorRef(java.util.List<IncidentSummaryResult.ConnectorRef> out,
                                        java.util.Set<String> seen, String name, String role, PipelineEntity p) {
        if (name == null || name.isBlank() || !seen.add(name)) {
            return;
        }
        out.add(new IncidentSummaryResult.ConnectorRef(name, role, p.getId().toString(), p.getName()));
    }

    @GetMapping("/incidents/{incidentId}/summary")
    public ResponseEntity<OpsEnvelope<IncidentSummaryResult>> legacyIncidentSummary(
            @PathVariable String incidentId,
            HttpServletRequest request) {
        String requestId = AgentHeaders.requestId(request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(OpsEnvelope.error(requestId, "get_incident_summary", "VALIDATION_FAILED",
                        "project-scoped incident summary path is required", false,
                        "use_project_scoped_path"));
    }

    private static String buildSummaryNote(IncidentEntity incident) {
        return "severity=" + incident.getSeverity()
                + " title=" + incident.getTitle()
                + (incident.getRca() != null ? " rca=" + incident.getRca() : "");
    }

    private static long parseNs(Object val) {
        if (val instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(val)); } catch (NumberFormatException e) {
            return Instant.parse(String.valueOf(val)).toEpochMilli() * 1_000_000L;
        }
    }

    private Optional<WorkspaceEntity> findWorkspace(String projectId) {
        return WorkspaceLookup.resolve(workspaceRepository, projectId);
    }

    private WorkspaceEntity requireWorkspace(String projectId) {
        return findWorkspace(projectId)
                .orElseThrow(() -> new ApiException(ErrorCode.WORKSPACE_NOT_FOUND,
                        "프로젝트를 찾을 수 없습니다: " + projectId));
    }

    private ConnectorEntity requireOwnedConnectConsumerGroup(String projectId, String consumerGroup) {
        if (!consumerGroup.startsWith("connect-") || consumerGroup.length() <= "connect-".length()) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                    "consumer group is not a Kafka Connect-managed group: " + consumerGroup);
        }
        return requireOwnedConnector(projectId, consumerGroup.substring("connect-".length()));
    }

    private ConnectorEntity requireOwnedConnector(String projectId, String connectorName) {
        WorkspaceEntity workspace = requireWorkspace(projectId);
        ConnectorEntity connector = connectorRepository.findByCrName(connectorName)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "connector not found: " + connectorName));
        pipelineRepository.findByIdAndTenantId(connector.getPipelineId(), workspace.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.WORKSPACE_FORBIDDEN,
                        "connector is not owned by project: " + projectId));
        return connector;
    }

    private List<String> ownedConnectorNames(UUID workspaceId) {
        List<ConnectorEntity> connectors = connectorRepository.findByTenantIdOrderByCrName(workspaceId);
        if (connectors == null) {
            return List.of();
        }
        return connectors.stream()
                .map(ConnectorEntity::getCrName)
                .filter(name -> name != null && !name.isBlank())
                .toList();
    }

    private ObservabilityScope resolveObservabilityScope(
            WorkspaceEntity workspace,
            String pipelineIdValue,
            String connectorNameValue) {
        UUID pipelineId = parseOptionalUuid(pipelineIdValue, "pipeline_id");
        String connectorName = normalizeOptional(connectorNameValue);
        if (pipelineId == null && connectorName == null) {
            return ObservabilityScope.projectWide();
        }

        PipelineEntity pipeline = pipelineId == null ? null : requireOwnedPipeline(workspace, pipelineId);
        ConnectorEntity connector = null;
        if (connectorName != null) {
            Optional<ConnectorEntity> found = connectorRepository.findByCrName(connectorName);
            if (found.isEmpty()) {
                // #938: 커넥터명을 못 찾아도 analyze_event_log/list_alerts 를 hard-fail 하지 않는다.
                // 이벤트·알림 요약 도구라 부정확한 connector scope 로 전체 분석(RCA)을 깨면 안 되므로,
                // pipeline_id 가 있으면 pipeline scope, 없으면 project-wide 로 graceful degrade 한다.
                log.debug("observability scope: connector '{}' 미발견 → degrade(scope 완화)", connectorName);
                if (pipeline != null) {
                    return ObservabilityScope.forPipeline(pipeline, connectorRepository.findByPipelineId(pipeline.getId()));
                }
                return ObservabilityScope.projectWide();
            }
            connector = found.get();
            PipelineEntity connectorPipeline = pipelineRepository
                    .findByIdAndTenantId(connector.getPipelineId(), workspace.getId())
                    .orElseThrow(() -> new ApiException(ErrorCode.WORKSPACE_FORBIDDEN,
                            "connector is not owned by project: " + workspace.getNamespace()));
            if (pipeline != null && !pipeline.getId().equals(connectorPipeline.getId())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "connector_name does not belong to pipeline_id");
            }
            pipeline = connectorPipeline;
        }

        if (connector != null) {
            return ObservabilityScope.forConnector(pipeline, connector);
        }
        return ObservabilityScope.forPipeline(pipeline, connectorRepository.findByPipelineId(pipeline.getId()));
    }

    private PipelineEntity requireOwnedPipeline(WorkspaceEntity workspace, UUID pipelineId) {
        return pipelineRepository.findByIdAndTenantId(pipelineId, workspace.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "pipeline not found in project: " + pipelineId));
    }

    private static UUID parseOptionalUuid(String value, String fieldName) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            return null;
        }
        try {
            return UUID.fromString(normalized);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, fieldName + " must be a UUID");
        }
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private List<IncidentEntity> openIncidents(
            UUID tenantId,
            ObservabilityScope scope,
            EventLevel threshold,
            Instant since) {
        if (!scope.allProjects()) {
            return incidentRepository.findScopedByTenantIdAndStatusAndSeverityInAndOpenedAtGreaterThanEqualOrderByOpenedAtDesc(
                    tenantId, "OPEN", severitiesAtOrAbove(threshold), since,
                    scope.groupingKeys(), scope.sourceIds());
        }
        return incidentRepository.findByTenantIdAndStatusAndSeverityInAndOpenedAtGreaterThanEqualOrderByOpenedAtDesc(
                tenantId, "OPEN", severitiesAtOrAbove(threshold), since);
    }

    private List<EventEntity> warningEvents(
            UUID tenantId,
            ObservabilityScope scope,
            EventLevel threshold,
            Instant since,
            List<IncidentEntity> openIncidents) {
        PageRequest page = PageRequest.of(0, 20);
        if (scope.connectorName() != null) {
            return eventRepository.findConnectorScopedEventsOrderByCreatedAtDesc(
                    tenantId,
                    scope.pipelineId(),
                    levelsAtOrAbove(threshold),
                    since,
                    scopedIncidentIds(openIncidents),
                    scope.connectorName(),
                    scope.consumerGroup(),
                    page);
        }
        if (scope.pipelineId() != null) {
            return eventRepository.findByTenantIdAndPipelineIdAndLevelInAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                    tenantId, scope.pipelineId(), levelsAtOrAbove(threshold), since, page);
        }
        return eventRepository.findByTenantIdAndLevelInAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                tenantId, levelsAtOrAbove(threshold), since, page);
    }

    private static List<UUID> scopedIncidentIds(List<IncidentEntity> incidents) {
        List<UUID> ids = incidents.stream()
                .map(IncidentEntity::getId)
                .filter(id -> id != null)
                .toList();
        return ids.isEmpty() ? List.of(new UUID(0L, 0L)) : ids;
    }

    private static String scopedLogQuery(
            WorkspaceEntity workspace,
            String rawQuery,
            List<String> connectorNames,
            String kafkaNamespace) {
        String projectKey = projectKey(workspace);
        String scopeFilter = workspaceScopeLineFilter(projectKey, connectorNames);
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isBlank()) {
            return kafkaConnectSelector(kafkaNamespace) + scopeFilter;
        }
        if (query.startsWith("{")) {
            return mergeKafkaConnectSelector(kafkaNamespace, query, scopeFilter);
        }
        return kafkaConnectSelector(kafkaNamespace) + scopeFilter + " |= \"" + escapeLogQuery(query) + "\"";
    }

    private static String mergeKafkaConnectSelector(String kafkaNamespace, String query, String scopeFilter) {
        int end = query.indexOf('}');
        if (end < 0) {
            return kafkaConnectSelector(kafkaNamespace) + scopeFilter + " |= \"" + escapeLogQuery(query) + "\"";
        }
        String selector = query.substring(1, end);
        String suffix = query.substring(end + 1).trim();
        List<String> matchers = new ArrayList<>();
        matchers.add("namespace=\"" + escapeLogQuery(kafkaNamespace) + "\"");
        matchers.add("app=\"kafka-connect\"");
        for (String matcher : splitLabelMatchers(selector)) {
            String trimmed = matcher.trim();
            if (!trimmed.isBlank() && !isEnforcedLogLabelMatcher(trimmed)) {
                matchers.add(trimmed);
            }
        }
        return "{" + String.join(",", matchers) + "}" + scopeFilter + (suffix.isBlank() ? "" : " " + suffix);
    }

    private static String kafkaConnectSelector(String kafkaNamespace) {
        return "{namespace=\"" + escapeLogQuery(kafkaNamespace) + "\",app=\"kafka-connect\"}";
    }

    private static String workspaceScopeLineFilter(String projectKey, List<String> connectorNames) {
        List<String> alternatives = new ArrayList<>();
        String project = escapeRegex(projectKey);
        alternatives.add("cdc\\.table\\." + project + "\\.");
        alternatives.add("eda\\.table\\." + project + "\\.");
        alternatives.add("bifrost\\." + project + "\\.");
        alternatives.add(boundedToken("proj-" + projectKey + "-user"));
        if (connectorNames != null) {
            connectorNames.stream()
                    .filter(name -> name != null && !name.isBlank())
                    .forEach(name -> {
                        alternatives.add(boundedToken(name));
                        alternatives.add(boundedToken("connect-" + name));
                    });
        }
        return " |~ \"" + escapeLogQuery(String.join("|", alternatives)) + "\"";
    }

    private static String boundedToken(String raw) {
        return "(^|[^A-Za-z0-9._-])" + escapeRegex(raw) + "([^A-Za-z0-9._-]|$)";
    }

    private static String projectKey(WorkspaceEntity workspace) {
        return workspace.getNamespace() != null && !workspace.getNamespace().isBlank()
                ? workspace.getNamespace()
                : workspace.getId().toString();
    }

    private static List<String> splitLabelMatchers(String selector) {
        List<String> matchers = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean escaped = false;
        for (int i = 0; i < selector.length(); i++) {
            char c = selector.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                current.append(c);
                escaped = true;
                continue;
            }
            if (c == '"') {
                inQuotes = !inQuotes;
                current.append(c);
                continue;
            }
            if (c == ',' && !inQuotes) {
                matchers.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        matchers.add(current.toString());
        return matchers;
    }

    private static boolean isEnforcedLogLabelMatcher(String matcher) {
        return matcher.matches("\\s*(namespace|app)\\s*(=|!=|=~|!~).*");
    }

    private static String escapeLogQuery(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String escapeRegex(String raw) {
        StringBuilder escaped = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ("\\.[]{}()+*?^$|".indexOf(c) >= 0) {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    private static String nonBlankOrDefault(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private static <T> ResponseEntity<OpsEnvelope<T>> apiError(
            String requestId,
            String operation,
            ApiException e) {
        if (e.code() == ErrorCode.VALIDATION_FAILED) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(OpsEnvelope.error(requestId, operation, "VALIDATION_FAILED",
                            e.getMessage(), false));
        }
        if (e.code() == ErrorCode.WORKSPACE_FORBIDDEN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(OpsEnvelope.error(requestId, operation, "RESOURCE_NOT_OWNED_BY_PROJECT",
                            e.getMessage(), false, "check_project_scope"));
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(OpsEnvelope.error(requestId, operation, "RESOURCE_NOT_FOUND",
                        e.getMessage(), false, "check_project_scope"));
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
            int limit,
            ObservabilityScope scope) {
        if (!scope.allProjects()) {
            return incidentRepository.findScopedAlertsByTenantIdOrderByOpenedAtDesc(
                    tenantId,
                    status,
                    severity,
                    scope.groupingKeys(),
                    scope.sourceIds(),
                    scope.pipelineId(),
                    PageRequest.of(0, limit));
        }
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

    private record ObservabilityScope(
            UUID pipelineId,
            List<String> groupingKeys,
            List<UUID> sourceIds,
            String connectorName,
            String consumerGroup,
            boolean allProjects) {

        static ObservabilityScope projectWide() {
            return new ObservabilityScope(null, List.of(), List.of(), null, null, true);
        }

        static ObservabilityScope forPipeline(PipelineEntity pipeline, List<ConnectorEntity> connectors) {
            List<String> groupingKeys = new ArrayList<>();
            List<UUID> sourceIds = new ArrayList<>();
            UUID pipelineId = pipeline.getId();
            add(sourceIds, pipelineId);
            add(groupingKeys, IncidentGroupingKeys.pipelineAvailability(pipelineId));
            add(groupingKeys, IncidentGroupingKeys.pipelineErrorRate(pipelineId));
            addDatasource(groupingKeys, sourceIds, pipeline.getSourceDatasourceId());
            addDatasource(groupingKeys, sourceIds, pipeline.getSinkDatasourceId());
            addTopic(groupingKeys, pipeline.getTopicName());
            addConnector(groupingKeys, pipeline.getSourceConnectorName());
            addConnector(groupingKeys, pipeline.getSinkConnectorName());
            for (ConnectorEntity connector : connectors == null ? List.<ConnectorEntity>of() : connectors) {
                addConnector(groupingKeys, connector.getCrName());
            }
            return new ObservabilityScope(
                    pipelineId, List.copyOf(groupingKeys), List.copyOf(sourceIds), null, null, false);
        }

        static ObservabilityScope forConnector(PipelineEntity pipeline, ConnectorEntity connector) {
            List<String> groupingKeys = new ArrayList<>();
            List<UUID> sourceIds = new ArrayList<>();
            add(sourceIds, connector.getId());
            addConnector(groupingKeys, connector.getCrName());
            UUID datasourceId = connector.getKind() == ConnectorKind.SINK
                    ? pipeline.getSinkDatasourceId()
                    : pipeline.getSourceDatasourceId();
            addDatasource(groupingKeys, sourceIds, datasourceId);
            String connectorName = connector.getCrName();
            return new ObservabilityScope(
                    pipeline.getId(),
                    List.copyOf(groupingKeys),
                    List.copyOf(sourceIds),
                    connectorName,
                    connectorName == null || connectorName.isBlank() ? null : "connect-" + connectorName,
                    false);
        }

        boolean matches(IncidentEntity incident) {
            if (allProjects) {
                return true;
            }
            String groupingKey = incident.getGroupingKey();
            if (groupingKey != null && groupingKeys.contains(groupingKey)) {
                return true;
            }
            UUID sourceId = incident.getSourceId();
            return sourceId != null && sourceIds.contains(sourceId);
        }

        private static void addConnector(List<String> groupingKeys, String connectorName) {
            if (connectorName == null || connectorName.isBlank()) {
                return;
            }
            add(groupingKeys, IncidentGroupingKeys.connectorWorker(connectorName));
            add(groupingKeys, IncidentGroupingKeys.consumerLag("connect-" + connectorName));
        }

        private static void addDatasource(List<String> groupingKeys, List<UUID> sourceIds, UUID datasourceId) {
            if (datasourceId == null) {
                return;
            }
            add(sourceIds, datasourceId);
            add(groupingKeys, IncidentGroupingKeys.datasource(datasourceId));
        }

        private static void addTopic(List<String> groupingKeys, String topicName) {
            if (topicName != null && !topicName.isBlank()) {
                add(groupingKeys, IncidentGroupingKeys.topicReplication(topicName));
            }
        }

        private static <T> void add(List<T> values, T value) {
            if (value != null && !values.contains(value)) {
                values.add(value);
            }
        }
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

    private DescribeGroupsSnapshot describeGroups(List<String> groupIds) {
        if (groupIds.isEmpty()) {
            return new DescribeGroupsSnapshot(Map.of(), null);
        }
        try {
            return new DescribeGroupsSnapshot(
                    adminClient.describeConsumerGroups(groupIds)
                            .all()
                            .get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS),
                    null);
        } catch (Exception e) {
            log.warn("[InternalOps] consumer group 상태 조회 실패: groups={} cause={}", groupIds, e.getMessage());
            return new DescribeGroupsSnapshot(Map.of(), e.getMessage());
        }
    }

    private LagSnapshot fetchLagSnapshot(String groupId) {
        try {
            Map<TopicPartition, OffsetAndMetadata> committed = adminClient
                    .listConsumerGroupOffsets(groupId)
                    .partitionsToOffsetAndMetadata()
                    .get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (committed.isEmpty()) return new LagSnapshot(0L, List.of(), null);

            Map<TopicPartition, OffsetSpec> endReqs = committed.keySet().stream()
                    .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends = adminClient
                    .listOffsets(endReqs).all().get(ADMIN_TIMEOUT_SEC, TimeUnit.SECONDS);

            long lag = 0L;
            List<ConsumerLagResult.PartitionLag> partitions = new ArrayList<>();
            for (Map.Entry<TopicPartition, OffsetAndMetadata> e : committed.entrySet()) {
                long end = ends.containsKey(e.getKey()) ? ends.get(e.getKey()).offset() : 0L;
                long committedOffset = e.getValue().offset();
                long partitionLag = Math.max(0L, end - committedOffset);
                lag += partitionLag;
                partitions.add(new ConsumerLagResult.PartitionLag(
                        e.getKey().topic(),
                        e.getKey().partition(),
                        committedOffset,
                        end,
                        partitionLag));
            }
            partitions.sort(java.util.Comparator.comparing(ConsumerLagResult.PartitionLag::topic)
                    .thenComparingInt(ConsumerLagResult.PartitionLag::partition));
            return new LagSnapshot(lag, partitions, null);
        } catch (Exception e) {
            log.warn("[InternalOps] consumer lag 조회 실패: group={} cause={}", groupId, e.getMessage());
            return new LagSnapshot(null, List.of(), e.getMessage());
        }
    }

    private static long parseWindowSeconds(String window) {
        String normalized = normalizeWindow(window);
        try {
            long value = Long.parseLong(normalized.substring(0, normalized.length() - 1));
            char unit = normalized.charAt(normalized.length() - 1);
            return switch (unit) {
                case 'm' -> value * 60L;
                case 'h' -> value * 60L * 60L;
                case 'd' -> value * 24L * 60L * 60L;
                default -> 2L * 60L * 60L;
            };
        } catch (RuntimeException e) {
            return 2L * 60L * 60L;
        }
    }

    private static String normalizeWindow(String window) {
        String value = window == null || window.isBlank() ? "2h" : window.trim().toLowerCase();
        return value.matches("\\d+[mhd]") ? value : "2h";
    }

    private static String normalizeLevel(String level) {
        return level == null || level.isBlank() ? "warn+" : level.trim().toLowerCase();
    }

    private static EventLevel parseLevelThreshold(String level) {
        String normalized = normalizeLevel(level).replace("+", "");
        return switch (normalized) {
            case "error", "critical" -> EventLevel.ERROR;
            case "info" -> EventLevel.INFO;
            default -> EventLevel.WARN;
        };
    }

    private static int eventLevelRank(EventLevel level) {
        if (level == null) {
            return 0;
        }
        return switch (level) {
            case INFO -> 0;
            case WARN -> 1;
            case ERROR -> 2;
        };
    }

    private static String levelToSeverity(EventLevel level) {
        return level == EventLevel.ERROR ? "ERROR" : level == EventLevel.WARN ? "WARN" : "INFO";
    }

    private static List<EventLevel> levelsAtOrAbove(EventLevel threshold) {
        return switch (threshold) {
            case ERROR -> List.of(EventLevel.ERROR);
            case WARN -> List.of(EventLevel.WARN, EventLevel.ERROR);
            case INFO -> List.of(EventLevel.INFO, EventLevel.WARN, EventLevel.ERROR);
        };
    }

    private static List<String> severitiesAtOrAbove(EventLevel threshold) {
        return switch (threshold) {
            case ERROR -> List.of("ERROR", "CRITICAL");
            case WARN -> List.of("WARN", "ERROR", "CRITICAL");
            case INFO -> List.of("INFO", "WARN", "ERROR", "CRITICAL");
        };
    }

    private static int severityRank(String severity) {
        if (severity == null) {
            return 0;
        }
        return switch (severity.toUpperCase()) {
            case "CRITICAL" -> 3;
            case "ERROR" -> 2;
            case "WARN", "WARNING" -> 1;
            default -> 0;
        };
    }

    private record LagSnapshot(Long lag, List<ConsumerLagResult.PartitionLag> partitions, String error) {}

    private record DescribeGroupsSnapshot(Map<String, ConsumerGroupDescription> groups, String error) {}
}
