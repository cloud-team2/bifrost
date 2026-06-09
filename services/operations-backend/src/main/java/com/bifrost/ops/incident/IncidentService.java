package com.bifrost.ops.incident;

import com.bifrost.ops.event.EventLevel;
import com.bifrost.ops.event.EventService;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.incident.dto.IncidentResponse;
import com.bifrost.ops.incident.persistence.entity.IncidentEntity;
import com.bifrost.ops.incident.persistence.repository.IncidentRepository;
import com.bifrost.ops.streaming.SsePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * event 임계 위반을 grouping_key로 묶어 incident를 생성·관리한다(S2).
 *
 * <p>규칙: WARN 2건/30분 → incident 생성, ERROR 즉시 생성.
 * 복구 event 수신 시 OPEN 상태의 incident를 auto-resolve.
 */
@Service
public class IncidentService {

    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);

    private final IncidentRepository incidentRepository;
    private final EventService eventService;
    private final SsePublisher sseService;

    public IncidentService(IncidentRepository incidentRepository,
                           EventService eventService,
                           SsePublisher sseService) {
        this.incidentRepository = incidentRepository;
        this.eventService = eventService;
        this.sseService = sseService;
    }

    /**
     * 임계 위반 event를 처리해 incident를 생성하거나 기존 incident에 연결한다.
     * @param tenantId     워크스페이스 ID
     * @param groupingKey  source_db_id / worker_id / consumer_group 등
     * @param sourceType   "CONSUMER_GROUP" | "CONNECTOR" | "DATABASE"
     * @param sourceId     대상 ID (nullable)
     * @param level        WARN / ERROR
     * @param title        인시던트 제목
     * @param eventType    event type 문자열
     * @param eventMessage event 메시지
     * @param pipelineId   연관 파이프라인 ID (nullable)
     */
    @Transactional
    public void onThresholdViolation(UUID tenantId, String groupingKey, String sourceType, UUID sourceId,
                                      EventLevel level, String title,
                                      String eventType, String eventMessage, UUID pipelineId) {
        // 기존 OPEN incident 조회
        Optional<IncidentEntity> existing =
                incidentRepository.findByTenantIdAndGroupingKeyAndStatus(tenantId, groupingKey, "OPEN");

        IncidentEntity incident;
        boolean isNew;
        if (existing.isPresent()) {
            incident = existing.get();
            isNew = false;
            // severity escalation: WARN → ERROR 가능
            if (level == EventLevel.ERROR && "WARN".equals(incident.getSeverity())) {
                incident.setSeverity("ERROR");
                incidentRepository.save(incident);
            }
        } else {
            incident = new IncidentEntity();
            incident.setTenantId(tenantId);
            incident.setGroupingKey(groupingKey);
            incident.setSeverity(level == EventLevel.ERROR ? "ERROR" : "WARN");
            incident.setTitle(title);
            incident.setSourceType(sourceType);
            incident.setSourceId(sourceId);
            incidentRepository.save(incident);
            isNew = true;
            log.info("[incident] 인시던트 생성: id={} groupingKey={} severity={}", incident.getId(), groupingKey, incident.getSeverity());
        }

        // event 기록 (incident_id 연결)
        eventService.recordWithIncident(tenantId, pipelineId, level, eventType, eventMessage,
                incident.getId(), sourceType);

        // SSE 발행
        String sseEvent = isNew ? "incident_opened" : "incident_updated";
        sseService.incidentEvent(tenantId, sseEvent, IncidentResponse.from(incident));
    }

    /**
     * 복구 event 수신 시 OPEN incident를 auto-resolve한다.
     */
    @Transactional
    public void onRecovery(UUID tenantId, String groupingKey, String eventType,
                            String eventMessage, UUID pipelineId) {
        incidentRepository.findByTenantIdAndGroupingKeyAndStatus(tenantId, groupingKey, "OPEN")
                .ifPresent(incident -> {
                    incident.setStatus("RESOLVED");
                    incident.setResolvedAt(Instant.now());
                    incidentRepository.save(incident);
                    log.info("[incident] auto-resolve: id={} groupingKey={}", incident.getId(), groupingKey);

                    eventService.recordWithIncident(tenantId, pipelineId, EventLevel.INFO,
                            eventType, eventMessage, incident.getId(), null);
                    sseService.incidentEvent(tenantId, "incident_updated", IncidentResponse.from(incident));
                });
    }

    /** RCA 기록 */
    @Transactional
    public IncidentResponse updateRca(UUID tenantId, UUID incidentId, String rca) {
        IncidentEntity incident = incidentRepository.findById(incidentId)
                .filter(e -> e.getTenantId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("incident not found: " + incidentId));
        incident.setRca(rca);
        return IncidentResponse.from(incidentRepository.save(incident));
    }

    @Transactional(readOnly = true)
    public List<IncidentResponse> list(UUID tenantId, String status) {
        List<IncidentEntity> rows = status != null
                ? incidentRepository.findByTenantIdAndStatusOrderByOpenedAtDesc(tenantId, status)
                : incidentRepository.findByTenantIdOrderByOpenedAtDesc(tenantId);
        return rows.stream().map(IncidentResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public IncidentResponse get(UUID tenantId, UUID incidentId) {
        return incidentRepository.findById(incidentId)
                .filter(e -> e.getTenantId().equals(tenantId))
                .map(IncidentResponse::from)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "incident not found: " + incidentId));
    }
}
