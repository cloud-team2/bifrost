package com.bifrost.ops.incident;

import com.bifrost.ops.event.EventLevel;
import com.bifrost.ops.event.EventService;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.incident.dto.IncidentReportResponse;
import com.bifrost.ops.incident.dto.IncidentResponse;
import com.bifrost.ops.incident.persistence.entity.IncidentEntity;
import com.bifrost.ops.incident.persistence.repository.IncidentRepository;
import com.bifrost.ops.streaming.SsePublisher;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * event 임계 위반을 grouping_key로 묶어 incident를 생성·관리한다(S2).
 *
 * <p>규칙: WARN 2건/30분 → incident 생성, ERROR 즉시 생성.
 * 복구 event 수신 시 자동 닫기 없음: CRITICAL은 OPEN 유지, WARNING은 OPEN→INVESTIGATING 전이.
 */
@Service
public class IncidentService {

    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);

    private final IncidentRepository incidentRepository;
    private final EventService eventService;
    private final SsePublisher sseService;
    private final IncidentAnalysisTrigger incidentAnalysisTrigger;

    public IncidentService(IncidentRepository incidentRepository,
                           EventService eventService,
                           SsePublisher sseService,
                           IncidentAnalysisTrigger incidentAnalysisTrigger) {
        this.incidentRepository = incidentRepository;
        this.eventService = eventService;
        this.sseService = sseService;
        this.incidentAnalysisTrigger = incidentAnalysisTrigger;
    }

    // (#558, 스펙 B.7) 심각도·상태값
    private static final String SEV_WARNING = "WARNING";
    private static final String SEV_CRITICAL = "CRITICAL";
    private static final String ST_OPEN = "OPEN";
    private static final String ST_INVESTIGATING = "INVESTIGATING";
    private static final String ST_RESOLVED = "RESOLVED";
    private static final List<String> ACTIVE_STATUSES = List.of(ST_OPEN, ST_INVESTIGATING);

    // WARN 게이팅: 동일 리소스 30분 내 2건 이상일 때만 인시던트 생성(단건은 이벤트 로그만).
    private static final Duration WARN_WINDOW = Duration.ofMinutes(30);
    private static final int WARN_THRESHOLD = 2;
    private final ConcurrentHashMap<String, Deque<Instant>> warnWindow = new ConcurrentHashMap<>();

    private static String severityOf(EventLevel level) {
        return level == EventLevel.ERROR ? SEV_CRITICAL : SEV_WARNING;
    }

    /** WARN 발생을 누적하고 30분 창에서 임계(2건) 도달 여부 반환. */
    private boolean warnGateReached(UUID tenantId, String groupingKey) {
        Deque<Instant> q = warnWindow.computeIfAbsent(tenantId + ":" + groupingKey, k -> new ArrayDeque<>());
        Instant now = Instant.now();
        Instant cutoff = now.minus(WARN_WINDOW);
        synchronized (q) {
            q.addLast(now);
            while (!q.isEmpty() && q.peekFirst().isBefore(cutoff)) {
                q.pollFirst();
            }
            return q.size() >= WARN_THRESHOLD;
        }
    }

    private List<IncidentEntity> activeIncidents(UUID tenantId, String groupingKey) {
        return incidentRepository.findByTenantIdAndGroupingKeyAndStatusInOrderByOpenedAtAsc(
                tenantId, groupingKey, ACTIVE_STATUSES);
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
        acquireGroupingLock(tenantId, groupingKey);

        // 기존 활성(OPEN·INVESTIGATING) incident 조회
        List<IncidentEntity> existing = activeIncidents(tenantId, groupingKey);

        if (!existing.isEmpty()) {
            // 이미 인시던트가 있으면 게이팅 없이 연결 + 필요 시 에스컬레이션.
            IncidentEntity incident = existing.get(0);
            List<IncidentResponse> duplicateUpdates = closeDuplicateActiveIncidents(existing);
            // severity escalation: WARNING + ERROR 이벤트 → CRITICAL (B.7)
            if (level == EventLevel.ERROR && SEV_WARNING.equals(incident.getSeverity())) {
                incident.setSeverity(SEV_CRITICAL);
                incidentRepository.save(incident);
            }
            eventService.recordWithIncident(tenantId, pipelineId, level, eventType, eventMessage,
                    incident.getId(), sourceType);
            publishAfterCommit(duplicateUpdates);
            publishAfterCommit(tenantId, "incident_updated", IncidentResponse.from(incident));
            return;
        }

        // 인시던트 없음 — ERROR는 즉시 생성, WARN은 30분/2건 게이팅(단건은 이벤트만, 인시던트 미생성).
        if (level != EventLevel.ERROR && !warnGateReached(tenantId, groupingKey)) {
            eventService.record(tenantId, pipelineId, level, eventType, eventMessage);
            return;
        }

        IncidentEntity incident = new IncidentEntity();
        incident.setTenantId(tenantId);
        incident.setGroupingKey(groupingKey);
        incident.setSeverity(severityOf(level));
        incident.setStatus(ST_OPEN);
        incident.setTitle(title);
        incident.setSourceType(sourceType);
        incident.setSourceId(sourceId);
        incidentRepository.save(incident);
        log.info("[incident] 인시던트 생성: id={} groupingKey={} severity={}",
                incident.getId(), groupingKey, incident.getSeverity());

        eventService.recordWithIncident(tenantId, pipelineId, level, eventType, eventMessage,
                incident.getId(), sourceType);
        publishAfterCommit(tenantId, "incident_opened", IncidentResponse.from(incident));
        incidentAnalysisTrigger.startAfterCommit(tenantId, incident.getId(), title, eventMessage);
    }

    /**
     * 복구 event 수신 시 자동 닫기 없이 CRITICAL은 유지하고 WARNING은 INVESTIGATING으로 전이한다.
     */
    @Transactional
    public boolean onRecovery(UUID tenantId, String groupingKey, String eventType,
                              String eventMessage, UUID pipelineId) {
        acquireGroupingLock(tenantId, groupingKey);

        List<IncidentEntity> existing = activeIncidents(tenantId, groupingKey);
        if (existing.isEmpty()) {
            return false;
        }

        // 스펙 B.7: 자동 닫기 없음. CRITICAL은 상태 유지(사용자 확인 필수), WARNING은 OPEN→INVESTIGATING 전이.
        List<IncidentResponse> updates = new ArrayList<>();
        for (IncidentEntity incident : existing) {
            if (SEV_WARNING.equals(incident.getSeverity()) && ST_OPEN.equals(incident.getStatus())) {
                incident.setStatus(ST_INVESTIGATING);
                incidentRepository.save(incident);
                updates.add(IncidentResponse.from(incident));
            }
        }
        IncidentEntity incident = existing.get(0);
        String note = SEV_CRITICAL.equals(incident.getSeverity())
                ? eventMessage + " (트리거 복구 — 확인 후 직접 해소 필요)"
                : eventMessage + " (트리거 복구 — investigating 전이)";
        log.info("[incident] 복구 처리(자동 닫기 안 함): id={} severity={} status={}",
                incident.getId(), incident.getSeverity(), incident.getStatus());

        eventService.recordWithIncident(tenantId, pipelineId, EventLevel.INFO,
                eventType, note, incident.getId(), null);
        if (!updates.isEmpty()) {
            publishAfterCommit(tenantId, "incident_updated", updates);
        }
        return true;
    }

    /**
     * 사용자의 인시던트 상태 전이(#558, 스펙 B.7). OPEN↔INVESTIGATING, →RESOLVED 허용.
     * RESOLVED는 종료 상태라 더 전이할 수 없다. resolved 전이 시 resolvedAt을 기록한다.
     */
    @Transactional
    public IncidentResponse transitionStatus(UUID tenantId, UUID incidentId, String target) {
        String to = target == null ? "" : target.trim().toUpperCase();
        if (!List.of(ST_OPEN, ST_INVESTIGATING, ST_RESOLVED).contains(to)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "유효하지 않은 상태: " + target);
        }
        IncidentEntity incident = incidentRepository.findByIdAndTenantId(incidentId, tenantId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "incident not found: " + incidentId));
        if (ST_RESOLVED.equals(incident.getStatus())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "이미 해소된 인시던트입니다");
        }
        String from = incident.getStatus();
        incident.setStatus(to);
        incident.setResolvedAt(ST_RESOLVED.equals(to) ? Instant.now() : null);
        incidentRepository.save(incident);
        IncidentResponse resp = IncidentResponse.from(incident);
        publishAfterCommit(tenantId, "incident_updated", resp);
        log.info("[incident] 사용자 상태 전이: id={} {}→{}", incidentId, from, to);
        return resp;
    }

    private void acquireGroupingLock(UUID tenantId, String groupingKey) {
        incidentRepository.lockIncidentGroup(tenantId + ":" + groupingKey);
    }

    /** 같은 그룹에 활성 인시던트가 둘 이상이면(경합) 첫 건만 남기고 중복을 RESOLVED 처리(#558). */
    private List<IncidentResponse> closeDuplicateActiveIncidents(List<IncidentEntity> incidents) {
        List<IncidentResponse> updates = new ArrayList<>();
        if (incidents.size() <= 1) {
            return updates;
        }
        Instant resolvedAt = Instant.now();
        for (int i = 1; i < incidents.size(); i++) {
            IncidentEntity duplicate = incidents.get(i);
            duplicate.setStatus(ST_RESOLVED);
            duplicate.setResolvedAt(resolvedAt);
            incidentRepository.save(duplicate);
            updates.add(IncidentResponse.from(duplicate));
        }
        return updates;
    }

    private void publishAfterCommit(List<IncidentResponse> updates) {
        updates.forEach(update -> publishAfterCommit(update.tenantId(), "incident_updated", update));
    }

    private void publishAfterCommit(UUID tenantId, String eventName, List<IncidentResponse> updates) {
        updates.forEach(update -> publishAfterCommit(tenantId, eventName, update));
    }

    private void publishAfterCommit(UUID tenantId, String eventName, IncidentResponse response) {
        Runnable publish = () -> sseService.incidentEvent(tenantId, eventName, response);
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

    /** RCA 기록 */
    @Transactional
    public IncidentResponse updateRca(UUID tenantId, UUID incidentId, String rca) {
        IncidentEntity incident = incidentRepository.findById(incidentId)
                .filter(e -> e.getTenantId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("incident not found: " + incidentId));
        incident.setRca(rca);
        return IncidentResponse.from(incidentRepository.save(incident));
    }

    /**
     * 인시던트 rca가 비어 있고 AI 분석 리포트가 있으면 리포트 요약으로 rca를 backfill한다(#595).
     * ai-service 분석 run은 비동기라 결과 콜백이 없으므로, 리포트를 fetch하는 시점(상세 조회)에 채운다.
     * 이미 rca가 있거나 리포트가 없으면 현재 값을 그대로 반환한다.
     */
    @Transactional
    public IncidentResponse backfillRcaIfMissing(UUID tenantId, UUID incidentId,
                                                 IncidentResponse current, List<IncidentReportResponse> reports) {
        if (current.rca() != null && !current.rca().isBlank()) {
            return current;
        }
        String summary = reportSummary(reports);
        if (summary == null || summary.isBlank()) {
            return current;
        }
        log.info("[incident] rca backfill(리포트→rca): id={}", incidentId);
        return updateRca(tenantId, incidentId, summary);
    }

    /** 가장 최근 리포트 body에서 사람이 읽을 RCA 요약을 추출(없으면 rootCauseId 폴백). */
    private static String reportSummary(List<IncidentReportResponse> reports) {
        if (reports == null || reports.isEmpty()) {
            return null;
        }
        IncidentReportResponse best = reports.stream()
                .max(java.util.Comparator.comparing(
                        r -> r.createdAt() == null ? Instant.EPOCH : r.createdAt()))
                .orElse(null);
        if (best == null) {
            return null;
        }
        JsonNode body = best.body();
        if (body != null) {
            // "answer" = ai-service incident_analysis 리포트의 사람이 읽는 본문 필드(실 스키마).
            for (String field : List.of("answer", "summary", "headline", "narrative", "root_cause", "rootCause")) {
                JsonNode n = body.get(field);
                if (n != null && n.isTextual() && !n.asText().isBlank()) {
                    return n.asText();
                }
                if (n != null && n.isObject() && n.get("summary") != null && n.get("summary").isTextual()) {
                    return n.get("summary").asText();
                }
            }
        }
        return best.rootCauseId();
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
