package com.bifrost.ops.pipeline.status;

import com.bifrost.ops.event.EventLevel;
import com.bifrost.ops.event.EventService;
import com.bifrost.ops.global.common.log.OpsLog;
import com.bifrost.ops.governance.audit.AuditService;
import com.bifrost.ops.pipeline.ConnectorStatusUpdate;
import com.bifrost.ops.pipeline.PipelineLifecycle;
import com.bifrost.ops.pipeline.PipelineStatusService;
import com.bifrost.ops.pipeline.persistence.entity.PipelineEntity;
import com.bifrost.ops.pipeline.persistence.repository.PipelineRepository;
import com.bifrost.ops.provisioning.dto.PipelinePattern;
import com.bifrost.ops.provisioning.persistence.entity.ConnectorEntity;
import com.bifrost.ops.provisioning.persistence.repository.ConnectorRepository;
import com.bifrost.ops.streaming.SsePublisher;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
                                     AuditService auditService,
                                     SsePublisher ssePublisher,
                                     com.bifrost.ops.workspace.persistence.repository.WorkspaceSettingsRepository settingsRepository) {
        this.pipelineRepository = pipelineRepository;
        this.connectorRepository = connectorRepository;
        this.datasourceRepository = datasourceRepository;
        this.eventService = eventService;
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
        ssePublisher.connectorStateChanged(p.getTenantId(), update.connectorName(),
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
            String reason = firstError(connectorRepository.findByPipelineId(p.getId()));
            String message = reason != null ? reason
                    : "프로비저닝 타임아웃: 커넥터가 " + timeout.toMinutes() + "분 내 RUNNING되지 않음";
            transition(p, PipelineLifecycle.CREATING, PipelineLifecycle.ERROR, message);
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
        PipelineLifecycle connectorNext = computeStatus(p.getPattern(), connectors);

        // DB 헬스도 입력(#179): source/sink DB가 UNREACHABLE이면 커넥터가 retry로 RUNNING이어도 ERROR.
        // 단, 프로비저닝 중(creating)이면 DB 사유로 덮어쓰지 않는다(생성 흐름이 별도 판정).
        String dbReason = current == PipelineLifecycle.CREATING ? null : dbUnreachableReason(p);
        PipelineLifecycle next = dbReason != null ? PipelineLifecycle.ERROR : connectorNext;
        // (#559) lag 오버레이(스펙 B.1): 커넥터 RUNNING(=base active)인데 consumer lag≥warning이면 lag.
        // error/paused/creating는 우선순위가 높아 lag로 덮어쓰지 않는다(error > lag > active).
        long lag = lagCache.getOrDefault(p.getId(), 0L);
        long warning = (next == PipelineLifecycle.ACTIVE) ? lagWarningThreshold(p.getTenantId()) : 0L;
        if (next == PipelineLifecycle.ACTIVE && lag >= warning) {
            next = PipelineLifecycle.LAG;
        }
        if (next == current) {
            return;
        }
        // 사유: DB 원인이 있으면 우선(근본 원인), error면 커넥터 lastError, lag면 consumer lag.
        String message;
        if (next == PipelineLifecycle.ERROR) {
            message = dbReason != null ? dbReason : firstError(connectors);
        } else if (next == PipelineLifecycle.LAG) {
            message = "consumer lag " + lag + " ≥ 경고 임계 " + warning;
        } else {
            message = null;
        }
        transition(p, current, next, message);
    }

    /** 워크스페이스의 consumer lag 경고 임계(미설정 시 기본 5,000). */
    private long lagWarningThreshold(UUID tenantId) {
        return settingsRepository.findById(tenantId)
                .map(com.bifrost.ops.workspace.persistence.entity.WorkspaceSettingsEntity::getLagWarningThreshold)
                .orElse(com.bifrost.ops.workspace.persistence.entity.WorkspaceSettingsEntity.DEFAULT_LAG_WARNING);
    }

    /** 상태 전이 1건: row 갱신 + event/audit/SSE 발행(단일 경로). recompute·timeout이 공통 사용. */
    private void transition(PipelineEntity p, PipelineLifecycle current, PipelineLifecycle next, String message) {
        p.setStatus(next);
        p.setStatusUpdatedAt(Instant.now());
        p.setStatusMessage(message);
        pipelineRepository.save(p);

        EventLevel level = switch (next) {
            case ERROR -> EventLevel.ERROR;
            case LAG -> EventLevel.WARN;
            default -> EventLevel.INFO;
        };
        eventService.record(p.getTenantId(), p.getId(), level, "PIPELINE_STATUS_CHANGED",
                "pipeline '" + p.getName() + "' " + current + " → " + next);
        auditService.record(p.getTenantId(), AuditService.ACTOR_SYSTEM, "PIPELINE_STATUS_TRANSITION",
                "PIPELINE", p.getId(), current + " -> " + next);
        ssePublisher.pipelineStatusChanged(p.getTenantId(), p.getId(), next.name().toLowerCase());
        OpsLog.ok("Pipeline", "상태 전이", "name=" + p.getName() + ", " + current + "→" + next);
        log.info("pipeline {} 상태 전이: {} → {}", p.getId(), current, next);
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

    /** 커넥터들 중 첫 lastError를 pipeline 상태 사유로 쓴다. 없으면 null. */
    private static String firstError(List<ConnectorEntity> connectors) {
        return connectors.stream()
                .map(ConnectorEntity::getLastError)
                .filter(e -> e != null && !e.isBlank())
                .findFirst()
                .orElse(null);
    }

    /** 파이프라인의 source/sink DB 중 UNREACHABLE이 있으면 원인 문자열, 없으면 null(#179). */
    private String dbUnreachableReason(PipelineEntity p) {
        String r = dbReasonFor(p.getSourceDatasourceId(), "source");
        return r != null ? r : dbReasonFor(p.getSinkDatasourceId(), "sink");
    }

    private String dbReasonFor(UUID datasourceId, String role) {
        if (datasourceId == null) {
            return null;
        }
        var ds = datasourceRepository.findById(datasourceId).orElse(null);
        if (ds == null
                || !com.bifrost.ops.database.health.DatabaseHealthProbeJob.UNREACHABLE.equals(ds.getConnectionStatus())) {
            return null;
        }
        String detail = ds.getConnectionError() != null ? ": " + ds.getConnectionError() : "";
        return role + " DB '" + ds.getName() + "' 연결 불가" + detail;
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
}
