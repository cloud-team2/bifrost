package com.bifrost.ops.pipeline.status;

import com.bifrost.ops.event.EventLevel;
import com.bifrost.ops.event.EventService;
import com.bifrost.ops.global.common.log.OpsLog;
import com.bifrost.ops.governance.audit.AuditService;
import com.bifrost.ops.incident.IncidentGroupingKeys;
import com.bifrost.ops.incident.IncidentService;
import com.bifrost.ops.pipeline.ConnectorStatusUpdate;
import com.bifrost.ops.pipeline.PipelineLifecycle;
import com.bifrost.ops.pipeline.PipelineStatusService;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.provisioning.dto.PipelinePattern;
import com.bifrost.ops.provisioning.persistence.entity.ConnectorEntity;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
import com.bifrost.ops.streaming.SsePublisher;
import com.bifrost.ops.workspace.persistence.entity.WorkspaceSettingsEntity;
import com.bifrost.ops.workspace.persistence.repository.WorkspaceSettingsRepository;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * pipeline 상태 변경 단일 writer 구현(#70, pipeline.md §5).
 *
 * <p>{@code KafkaConnectorWatcher}가 {@link #applyConnectorStatus}로만 들어온다. connector 상태
 * ({@code connectors} 테이블, sink가 갱신)를 보고 pipeline 상태를 재계산하고, 변경된 경우에만 pipeline
 * row를 갱신하며 event/audit/SSE를 발행한다. 동일 상태 반복 통지는 no-op이라 중복 이벤트가 나지 않는다.
 */
@Service
public class PipelineStatusServiceImpl implements PipelineStatusService {

    private static final Logger log = LoggerFactory.getLogger(PipelineStatusServiceImpl.class);

    private final PipelineRepository pipelineRepository;
    private final ConnectorRepository connectorRepository;
    private final com.bifrost.ops.database.persistence.repository.DatasourceRepository datasourceRepository;
    private final EventService eventService;
    private final IncidentService incidentService;
    private final AuditService auditService;
    private final SsePublisher ssePublisher;
    private final com.bifrost.ops.workspace.persistence.repository.WorkspaceSettingsRepository settingsRepository;

    // (#559) 최신 consumer lag(파이프라인별). KafkaAdminPoller가 30초마다 갱신하고, recompute가
    // 커넥터/DB 상태와 함께 읽어 lag 상태를 산정한다. 재기동 시 비어있으면 다음 폴까지 lag=0으로 본다.
    private final java.util.concurrent.ConcurrentHashMap<UUID, Long> lagCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    public PipelineStatusServiceImpl(PipelineRepository pipelineRepository,
                                     ConnectorRepository connectorRepository,
                                     com.bifrost.ops.database.persistence.repository.DatasourceRepository datasourceRepository,
                                     EventService eventService,
                                     IncidentService incidentService,
                                     AuditService auditService,
                                     SsePublisher ssePublisher,
                                     com.bifrost.ops.workspace.persistence.repository.WorkspaceSettingsRepository settingsRepository) {
        this.pipelineRepository = pipelineRepository;
        this.connectorRepository = connectorRepository;
        this.datasourceRepository = datasourceRepository;
        this.eventService = eventService;
        this.incidentService = incidentService;
        this.auditService = auditService;
        this.ssePublisher = ssePublisher;
        this.settingsRepository = settingsRepository;
    }

    /**
     * consumer lag 갱신(#559). KafkaAdminPoller가 호출. lag 값을 캐시하고 파이프라인 상태를 재평가한다
     * (커넥터 RUNNING + lag≥warning이면 active→lag, 회복되면 lag→active). 단일 writer 경로 유지.
     */
    @Override
    @Transactional
    public void applyConsumerLag(UUID pipelineId, long lag) {
        lagCache.put(pipelineId, Math.max(0L, lag));
        pipelineRepository.findById(pipelineId).ifPresent(this::recompute);
    }

    @Observed(name = "pipeline.status.apply_connector_status")
    @Override
    @Transactional
    public void applyConnectorStatus(ConnectorStatusUpdate update) {
        UUID pipelineId = resolvePipelineId(update.connectorName());
        if (pipelineId == null) {
            log.warn("connector→pipeline 매핑 실패, 상태 반영 skip: name={}", update.connectorName());
            return;
        }
        PipelineEntity p = pipelineRepository.findById(pipelineId).orElse(null);
        if (p == null) {
            log.warn("pipeline 행 없음, 상태 반영 skip: pipeline={}", pipelineId);
            return;
        }
        // connector 상태 변경 알림(상세 토글)
        publishConnectorStateAfterCommit(p.getTenantId(), update.connectorName(),
                update.connectorState().name());
        recompute(p);
    }

    @Override
    @Transactional
    public int failTimedOutCreating(java.time.Duration timeout) {
        Instant cutoff = Instant.now().minus(timeout);
        List<PipelineEntity> stuck =
                pipelineRepository.findByStatusAndCreatedAtBefore(PipelineLifecycle.CREATING, cutoff);
        int n = 0;
        for (PipelineEntity p : stuck) {
            List<ConnectorEntity> connectors = connectorRepository.findByPipelineId(p.getId());
            ConnectorEntity failedConnector = firstFailedConnector(connectors);
            String reason = failedConnector != null ? connectorFailureMessage(p, failedConnector) : firstError(connectors);
            String message = reason != null ? reason
                    : "프로비저닝 타임아웃: 커넥터가 " + timeout.toMinutes() + "분 내 RUNNING되지 않음";
            IncidentCause incidentCause = failedConnector != null
                    ? connectorIncidentCause(p, failedConnector)
                    : IncidentCause.pipeline(p);
            transition(p, PipelineLifecycle.CREATING,
                    new StatusDecision(PipelineLifecycle.ERROR, message, incidentCause));
            n++;
        }
        if (n > 0) {
            log.warn("프로비저닝 타임아웃으로 {}개 파이프라인을 error로 전이", n);
        }
        return n;
    }

    /**
     * 특정 datasource(source/sink) 헬스 변화 시, 이를 쓰는 파이프라인 상태를 재평가한다(#179).
     * source DB가 죽어도 Debezium은 retry로 RUNNING을 유지해 커넥터 이벤트가 안 오므로, DB 프로브가
     * 이 경로로 파이프라인을 직접 재평가해야 'DB 죽음'이 파이프라인 상태에 반영된다.
     */
    @Override
    @Transactional
    public void reevaluateForDatasource(UUID datasourceId) {
        for (PipelineEntity p : pipelineRepository
                .findBySourceDatasourceIdOrSinkDatasourceId(datasourceId, datasourceId)) {
            recompute(p);
        }
    }

    /** connector 상태 + DB 헬스를 보고 pipeline 상태를 재계산하고, 변경 시에만 기록·발행한다. */
    private void recompute(PipelineEntity p) {
        List<ConnectorEntity> connectors = connectorRepository.findByPipelineId(p.getId());
        PipelineLifecycle current = p.getStatus();
        StatusDecision decision = decideStatus(p, current, connectors);
        if (decision.next() == current && !isReasonChangedError(current, p.getStatusMessage(), decision.message())) {
            return;
        }
        transition(p, current, decision);
    }

    private StatusDecision decideStatus(PipelineEntity p, PipelineLifecycle current,
                                        List<ConnectorEntity> connectors) {
        PipelineLifecycle connectorNext = computeStatus(p.getPattern(), connectors);

        // DB 헬스도 입력(#179): source/sink DB가 UNREACHABLE이면 커넥터가 retry로 RUNNING이어도 ERROR.
        // 단, 프로비저닝 중(creating)이면 DB 사유로 덮어쓰지 않는다(생성 흐름이 별도 판정).
        // (#559 보존) lag 오버레이는 아래 connectorNext==ACTIVE 분기에서 동일 임계로 처리.
        DbFailure dbFailure = current == PipelineLifecycle.CREATING ? null : dbUnreachableFailure(p);
        if (dbFailure != null) {
            return new StatusDecision(PipelineLifecycle.ERROR, dbFailure.message(), dbFailure.incidentCause());
        }

        if (connectorNext == PipelineLifecycle.ERROR) {
            ConnectorEntity failedConnector = firstFailedConnector(connectors);
            IncidentCause incidentCause = failedConnector != null
                    ? connectorIncidentCause(p, failedConnector)
                    : IncidentCause.pipeline(p);
            String message = failedConnector != null ? connectorFailureMessage(p, failedConnector) : firstError(connectors);
            return new StatusDecision(PipelineLifecycle.ERROR, message, incidentCause);
        }

        if (connectorNext == PipelineLifecycle.ACTIVE) {
            long lag = lagCache.getOrDefault(p.getId(), 0L);
            long warning = lagWarningThreshold(p.getTenantId());
            if (lag >= warning) {
                return new StatusDecision(PipelineLifecycle.LAG,
                        "consumer lag " + lag + " ≥ 경고 임계 " + warning,
                        null);
            }
        }

        return new StatusDecision(connectorNext, null, null);
    }

    private static boolean isReasonChangedError(PipelineLifecycle current, String previousMessage, String message) {
        return current == PipelineLifecycle.ERROR && !Objects.equals(previousMessage, message);
    }

    /** 워크스페이스의 consumer lag 경고 임계(미설정 시 기본 5,000). */
    private long lagWarningThreshold(UUID tenantId) {
        return settingsRepository.findById(tenantId)
                .map(WorkspaceSettingsEntity::getLagWarningThreshold)
                .orElse(WorkspaceSettingsEntity.DEFAULT_LAG_WARNING);
    }

    /** 상태 전이 1건: row 갱신 + event/audit/SSE 발행(단일 경로). recompute·timeout이 공통 사용. */
    private void transition(PipelineEntity p, PipelineLifecycle current, StatusDecision decision) {
        PipelineLifecycle next = decision.next();
        String message = decision.message();
        String previousMessage = p.getStatusMessage();
        p.setStatus(next);
        p.setStatusUpdatedAt(Instant.now());
        p.setStatusMessage(message);
        pipelineRepository.save(p);

        EventLevel level = switch (next) {
            case ERROR -> EventLevel.ERROR;
            case LAG -> EventLevel.WARN;
            default -> EventLevel.INFO;
        };
        String eventMessage = statusEventMessage(p, current, next, message);
        auditService.record(p.getTenantId(), AuditService.ACTOR_SYSTEM, "PIPELINE_STATUS_TRANSITION",
                "PIPELINE", p.getId(), current + " -> " + next);
        recordStatusEvent(p, current, next, level, message, eventMessage, previousMessage, decision.incidentCause());
        publishPipelineStatusAfterCommit(p.getTenantId(), p.getId(), next.name().toLowerCase());
        OpsLog.ok("Pipeline", "상태 전이", "name=" + p.getName() + ", " + current + "→" + next);
        log.info("pipeline {} 상태 전이: {} → {}", p.getId(), current, next);
    }

    private void recordStatusEvent(PipelineEntity p, PipelineLifecycle current, PipelineLifecycle next,
                                   EventLevel level, String causeMessage, String eventMessage, String previousMessage,
                                   IncidentCause incidentCause) {
        IncidentCause previousCause = current == PipelineLifecycle.ERROR
                ? incidentCauseFromStoredMessage(p, previousMessage)
                : null;
        if (next == PipelineLifecycle.ERROR) {
            IncidentCause cause = incidentCause != null ? incidentCause : IncidentCause.pipeline(p);
            if (previousCause != null && !previousCause.equals(cause)) {
                incidentService.onRecovery(p.getTenantId(), previousCause.groupingKey(),
                        "PIPELINE_STATUS_CHANGED", eventMessage, p.getId());
            }
            incidentService.onThresholdViolation(p.getTenantId(), cause.groupingKey(), cause.sourceType(), cause.sourceId(),
                    level, incidentTitle(p, next, causeMessage),
                    "PIPELINE_STATUS_CHANGED", eventMessage, p.getId());
        } else {
            boolean recovered = previousCause != null && incidentService.onRecovery(p.getTenantId(), previousCause.groupingKey(),
                    "PIPELINE_STATUS_CHANGED", eventMessage, p.getId());
            if (next == PipelineLifecycle.ACTIVE && recovered) {
                return;
            }
            eventService.record(p.getTenantId(), p.getId(), level, "PIPELINE_STATUS_CHANGED", eventMessage);
        }
    }

    private static String statusEventMessage(PipelineEntity p, PipelineLifecycle current,
                                             PipelineLifecycle next, String reason) {
        String base = "pipeline '" + p.getName() + "' " + current + " → " + next;
        return reason == null || reason.isBlank() ? base : base + ": " + reason;
    }

    /**
     * 인시던트 타이틀(#679). 정제된 원인 메시지(원인 유형 + 대상, 예: "'orders-eda' 소스 커넥터 오류:
     * DB 연결 실패 …")를 그대로 쓰고, 원인 메시지가 없으면 상태 기반 fallback을 쓴다.
     */
    private static String incidentTitle(PipelineEntity p, PipelineLifecycle next, String causeMessage) {
        return causeMessage == null || causeMessage.isBlank()
                ? "Pipeline '" + p.getName() + "' status " + next
                : causeMessage;
    }

    private static IncidentCause incidentCauseFromStoredMessage(PipelineEntity p, String reason) {
        if (reason == null) {
            return IncidentCause.pipeline(p);
        }
        if (reason.contains("source DB") && p.getSourceDatasourceId() != null) {
            return IncidentCause.datasource(p.getSourceDatasourceId());
        }
        if (reason.contains("sink DB") && p.getSinkDatasourceId() != null) {
            return IncidentCause.datasource(p.getSinkDatasourceId());
        }
        // (#596) 커넥터 사유는 UUID 대신 역할 키워드로 매칭(메시지 정제 후에도 회복 그룹핑 유지).
        if (reason.contains("소스 커넥터") && p.getSourceConnectorName() != null) {
            return IncidentCause.connector(p.getSourceConnectorName());
        }
        if (reason.contains("싱크 커넥터") && p.getSinkConnectorName() != null) {
            return IncidentCause.connector(p.getSinkConnectorName());
        }
        // 구버전 메시지(crName 직접 포함) 호환.
        String connectorName = connectorNameMentionedIn(p, reason);
        if (connectorName != null) {
            return IncidentCause.connector(connectorName);
        }
        return IncidentCause.pipeline(p);
    }

    private static String connectorNameMentionedIn(PipelineEntity p, String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        if (p.getSourceConnectorName() != null && reason.contains(p.getSourceConnectorName())) {
            return p.getSourceConnectorName();
        }
        if (p.getSinkConnectorName() != null && reason.contains(p.getSinkConnectorName())) {
            return p.getSinkConnectorName();
        }
        return null;
    }

    private void publishPipelineStatusAfterCommit(UUID tenantId, UUID pipelineId, String status) {
        Runnable publish = () -> ssePublisher.pipelineStatusChanged(tenantId, pipelineId, status);
        publishAfterCommit(publish);
    }

    private void publishConnectorStateAfterCommit(UUID tenantId, String connectorName, String state) {
        Runnable publish = () -> ssePublisher.connectorStateChanged(tenantId, connectorName, state);
        publishAfterCommit(publish);
    }

    private void publishAfterCommit(Runnable publish) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publish.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publish.run();
            }
        });
    }

    /**
     * connector 상태 집합 → pipeline 상태(부록 B.1/B.4). EDA는 source 1개, CDC는 source+sink.
     * 우선순위: FAILED 또는 일부 task FAILED(PARTIALLY_FAILED)→error, PAUSED→paused,
     * 기대 수만큼 모두 RUNNING→active, 그 외→creating.
     * lag 상태는 여기서 도출하지 않고 consumer lag(KafkaAdminPoller→applyConsumerLag)로만 산정한다.
     */
    static PipelineLifecycle computeStatus(PipelinePattern pattern, List<ConnectorEntity> connectors) {
        int expected = pattern == PipelinePattern.DIRECT ? 2 : 1;
        boolean anyFailed = false;
        boolean anyPartial = false;
        boolean anyPaused = false;
        int running = 0;
        for (ConnectorEntity c : connectors) {
            switch (parseState(c.getState())) {
                case "FAILED" -> anyFailed = true;
                case "PARTIALLY_FAILED" -> anyPartial = true;
                case "PAUSED" -> anyPaused = true;
                case "RUNNING" -> running++;
                default -> { /* UNASSIGNED/UNKNOWN/null → 아직 미기동 */ }
            }
        }
        // 일부 task 실패(PARTIALLY_FAILED)도 error(스펙 B.4: Connector Task FAILED → error, #559).
        // lag 상태는 더 이상 커넥터에서 도출하지 않고 consumer lag로만 산정한다(B.1).
        if (anyFailed || anyPartial) {
            return PipelineLifecycle.ERROR;
        }
        if (anyPaused) {
            return PipelineLifecycle.PAUSED;
        }
        if (connectors.size() >= expected && running >= expected) {
            return PipelineLifecycle.ACTIVE;
        }
        return PipelineLifecycle.CREATING;
    }

    private static String parseState(String state) {
        return state == null ? "" : state.toUpperCase();
    }

    /** 커넥터들 중 첫 lastError를 사람이 읽을 수 있게 정제해 pipeline 상태 사유로 쓴다(#596). 없으면 null. */
    private static String firstError(List<ConnectorEntity> connectors) {
        return connectors.stream()
                .filter(c -> c.getLastError() != null && !c.getLastError().isBlank())
                .findFirst()
                .map(c -> sanitizeConnectorError(c.getLastError()))
                .orElse(null);
    }

    /** 커넥터 raw lastError를 사용자용 한 줄 요약으로 정제한다(#596, 공용 {@link ConnectorErrorMessages}). */
    private static String sanitizeConnectorError(String raw) {
        return ConnectorErrorMessages.summarize(raw);
    }

    private static ConnectorEntity firstFailedConnector(List<ConnectorEntity> connectors) {
        return connectors.stream()
                .filter(c -> {
                    String state = parseState(c.getState());
                    return "FAILED".equals(state) || "PARTIALLY_FAILED".equals(state);
                })
                .findFirst()
                .orElse(null);
    }

    /**
     * 사용자용 커넥터 실패 사유(#596). 커넥터 UUID(crName) 대신 파이프라인명 + 역할(소스/싱크)을 쓰고,
     * raw lastError는 {@link #sanitizeConnectorError}로 정제한다.
     * 역할 키워드("소스/싱크 커넥터")는 {@link #incidentCauseFromStoredMessage}의 회복 매칭에도 쓰인다.
     */
    private static String connectorFailureMessage(PipelineEntity p, ConnectorEntity connector) {
        String who = connectorRoleKo(connector);
        String summary = sanitizeConnectorError(connector.getLastError());
        return "'" + p.getName() + "' " + who + " 오류: " + summary;
    }

    /** 커넥터 역할 표기: SOURCE→"소스 커넥터", SINK→"싱크 커넥터", 그 외→"커넥터". */
    private static String connectorRoleKo(ConnectorEntity connector) {
        if (connector.getKind() == com.bifrost.ops.provisioning.dto.ConnectorKind.SOURCE) return "소스 커넥터";
        if (connector.getKind() == com.bifrost.ops.provisioning.dto.ConnectorKind.SINK) return "싱크 커넥터";
        return "커넥터";
    }

    private static IncidentCause connectorIncidentCause(PipelineEntity p, ConnectorEntity connector) {
        String connectorName = connectorName(p, connector);
        return connectorName == null ? IncidentCause.pipeline(p) : IncidentCause.connector(connectorName);
    }

    private static String connectorName(PipelineEntity p, ConnectorEntity connector) {
        if (connector.getCrName() != null && !connector.getCrName().isBlank()) {
            return connector.getCrName();
        }
        if (connector.getKind() == com.bifrost.ops.provisioning.dto.ConnectorKind.SOURCE) {
            return p.getSourceConnectorName();
        }
        if (connector.getKind() == com.bifrost.ops.provisioning.dto.ConnectorKind.SINK) {
            return p.getSinkConnectorName();
        }
        return null;
    }

    /** 파이프라인의 source/sink DB 중 UNREACHABLE이 있으면 원인과 incident cause, 없으면 null(#179). */
    private DbFailure dbUnreachableFailure(PipelineEntity p) {
        DbFailure r = dbFailureFor(p.getSourceDatasourceId(), "source");
        return r != null ? r : dbFailureFor(p.getSinkDatasourceId(), "sink");
    }

    private DbFailure dbFailureFor(UUID datasourceId, String role) {
        if (datasourceId == null) {
            return null;
        }
        var ds = datasourceRepository.findById(datasourceId).orElse(null);
        if (ds == null
                || !com.bifrost.ops.database.health.DatabaseHealthProbeJob.UNREACHABLE.equals(ds.getConnectionStatus())) {
            return null;
        }
        String detail = ds.getConnectionError() != null ? ": " + ds.getConnectionError() : "";
        return new DbFailure(role + " DB '" + ds.getName() + "' 연결 불가" + detail,
                IncidentCause.datasource(datasourceId));
    }

    private UUID resolvePipelineId(String connectorName) {
        return connectorRepository.findByCrName(connectorName)
                .map(ConnectorEntity::getPipelineId)
                .orElseGet(() -> parseFromName(connectorName));
    }

    /** {@code {pipelineId}-source|-sink}에서 pipelineId를 복원(connectors 행이 아직 없을 때 fallback). */
    private static UUID parseFromName(String connectorName) {
        if (connectorName == null) {
            return null;
        }
        int dash = connectorName.lastIndexOf('-');
        if (dash <= 0) {
            return null;
        }
        try {
            return UUID.fromString(connectorName.substring(0, dash));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private record StatusDecision(PipelineLifecycle next, String message, IncidentCause incidentCause) {}

    private record DbFailure(String message, IncidentCause incidentCause) {}

    private record IncidentCause(String groupingKey, String sourceType, UUID sourceId) {
        static IncidentCause pipeline(PipelineEntity p) {
            return new IncidentCause(IncidentGroupingKeys.pipelineAvailability(p.getId()), "PIPELINE", p.getId());
        }

        static IncidentCause datasource(UUID datasourceId) {
            return new IncidentCause(IncidentGroupingKeys.datasource(datasourceId), "DATABASE", datasourceId);
        }

        static IncidentCause connector(String connectorName) {
            return new IncidentCause(IncidentGroupingKeys.connectorWorker(connectorName), "CONNECTOR", null);
        }
    }
}
