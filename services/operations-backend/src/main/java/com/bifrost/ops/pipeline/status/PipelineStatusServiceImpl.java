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
 * <p>watcher/mock 모두 {@link #applyConnectorStatus}로만 들어온다. connector 상태({@code connectors}
 * 테이블, sink가 갱신)를 보고 pipeline 상태를 재계산하고, 변경된 경우에만 pipeline row를 갱신하며
 * event/audit/SSE를 발행한다. 동일 상태 반복 통지는 no-op이라 중복 이벤트가 나지 않는다.
 *
 * <p>이 빈이 존재하면 real 모드의 log-only fallback({@code PipelineStatusFallbackConfig})은
 * {@code @ConditionalOnMissingBean}으로 비활성화된다.
 */
@Service
public class PipelineStatusServiceImpl implements PipelineStatusService {

    private static final Logger log = LoggerFactory.getLogger(PipelineStatusServiceImpl.class);

    private final PipelineRepository pipelineRepository;
    private final ConnectorRepository connectorRepository;
    private final EventService eventService;
    private final AuditService auditService;
    private final SsePublisher ssePublisher;

    public PipelineStatusServiceImpl(PipelineRepository pipelineRepository,
                                     ConnectorRepository connectorRepository,
                                     EventService eventService,
                                     AuditService auditService,
                                     SsePublisher ssePublisher) {
        this.pipelineRepository = pipelineRepository;
        this.connectorRepository = connectorRepository;
        this.eventService = eventService;
        this.auditService = auditService;
        this.ssePublisher = ssePublisher;
    }

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

    /** connector 상태들을 보고 pipeline 상태를 재계산하고, 변경 시에만 기록·발행한다. */
    private void recompute(PipelineEntity p) {
        PipelineLifecycle current = p.getStatus();
        PipelineLifecycle next = computeStatus(p.getPattern(), connectorRepository.findByPipelineId(p.getId()));
        if (next == current) {
            return;
        }
        p.setStatus(next);
        p.setStatusUpdatedAt(Instant.now());
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
     * connector 상태 집합 → pipeline 상태(부록 B.1/B.2). EDA는 source 1개, CDC는 source+sink.
     * 우선순위: FAILED→error, 일부 task FAILED(PARTIALLY_FAILED)→lag, PAUSED→paused,
     * 기대 수만큼 모두 RUNNING→active, 그 외→creating.
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
        if (anyFailed) {
            return PipelineLifecycle.ERROR;
        }
        if (anyPartial) {
            return PipelineLifecycle.LAG;
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
