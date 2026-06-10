package com.bifrost.ops.internalops.controller;

import com.bifrost.ops.internalops.AgentHeaders;
import com.bifrost.ops.internalops.dto.HealthResult;
import com.bifrost.ops.internalops.dto.OpsEnvelope;
import com.bifrost.ops.internalops.dto.ReadyResult;
import com.bifrost.ops.internalops.dto.VersionResult;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FastAPI agent용 /internal/ops 공통 표면.
 *
 * <p>모든 응답은 OpsEnvelope로 감싸고, X-Agent-Request-Id 헤더를 request_id로 반영한다.
 * Security 제외(JWT 없음) — SecurityConfig에서 /internal/ops/** permitAll 처리.
 */
@RestController
@RequestMapping("/internal/ops")
public class InternalOpsController {

    private static final Logger log = LoggerFactory.getLogger(InternalOpsController.class);

    private final JdbcTemplate jdbcTemplate;
    private final String appVersion;

    public InternalOpsController(
            JdbcTemplate jdbcTemplate,
            @Value("${spring.application.name:operations-backend}") String appName) {
        this.jdbcTemplate = jdbcTemplate;
        this.appVersion = appName;
    }

    @GetMapping("/health")
    public ResponseEntity<OpsEnvelope<HealthResult>> health(HttpServletRequest request) {
        String requestId = AgentHeaders.requestId(request);
        return ResponseEntity.ok(OpsEnvelope.ok(requestId, "health", HealthResult.up()));
    }

    @GetMapping("/ready")
    public ResponseEntity<OpsEnvelope<ReadyResult>> ready(HttpServletRequest request) {
        String requestId = AgentHeaders.requestId(request);
        boolean dbOk = isDbAlive();
        ReadyResult result = dbOk ? ReadyResult.ok() : ReadyResult.degraded();
        int statusCode = dbOk ? 200 : 503;
        return ResponseEntity.status(statusCode)
                .body(OpsEnvelope.ok(requestId, "ready", result));
    }

    @GetMapping("/version")
    public ResponseEntity<OpsEnvelope<VersionResult>> version(HttpServletRequest request) {
        String requestId = AgentHeaders.requestId(request);
        VersionResult result = new VersionResult("operations-backend", appVersion);
        return ResponseEntity.ok(OpsEnvelope.ok(requestId, "version", result));
    }

    /**
     * tool-catalog — agent가 호출 가능한 internalops tool 목록(S4, server.md §7.1).
     * allowlist 정본으로 agent와 계약을 맞춘다.
     */
    @GetMapping("/admin/tool-catalog")
    public ResponseEntity<OpsEnvelope<java.util.List<java.util.Map<String, String>>>> toolCatalog(
            HttpServletRequest request) {
        String requestId = AgentHeaders.requestId(request);
        var catalog = java.util.List.of(
                tool("get_consumer_lag",       "GET",  "/internal/ops/projects/{projectId}/kafka/consumer-groups/{consumerGroup}/lag"),
                tool("search_logs",            "POST", "/internal/ops/projects/{projectId}/observability/logs/search"),
                tool("query_traces",           "GET",  "/internal/ops/projects/{projectId}/connectors/{connectorName}/traces"),
                tool("list_alerts",            "GET",  "/internal/ops/projects/{projectId}/observability/alerts"),
                tool("get_incident_summary",   "GET",  "/internal/ops/projects/{projectId}/incidents/{incidentId}/summary"),
                tool("list_project_pipelines", "GET",  "/internal/ops/projects/{projectId}/pipelines"),
                tool("get_pipeline_topology",  "GET",  "/internal/ops/projects/{projectId}/pipelines/{pipelineId}/topology"),
                tool("get_connector_status",   "GET",  "/internal/ops/projects/{projectId}/kafka/connectors/{connectorName}/status"),
                // mutation tool (#395) — InternalOpsMutationController(#308) 실구현과 정합.
                tool("restart_connector",      "POST", "/internal/ops/projects/{projectId}/connectors/{connectorName}/restart"),
                tool("pause_connector",        "POST", "/internal/ops/projects/{projectId}/connectors/{connectorName}/pause"),
                tool("resume_connector",       "POST", "/internal/ops/projects/{projectId}/connectors/{connectorName}/resume"),
                tool("restart_consumer_group", "POST", "/internal/ops/projects/{projectId}/kafka/consumer-groups/{consumerGroup}/restart")
        );
        return ResponseEntity.ok(OpsEnvelope.ok(requestId, "tool_catalog", catalog));
    }

    private static java.util.Map<String, String> tool(String name, String method, String path) {
        return java.util.Map.of("name", name, "method", method, "path", path);
    }

    private boolean isDbAlive() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (Exception e) {
            log.warn("[InternalOps] DB 연결 확인 실패: {}", e.getMessage());
            return false;
        }
    }
}
