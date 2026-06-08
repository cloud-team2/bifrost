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
