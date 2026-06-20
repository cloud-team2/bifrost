package com.bifrost.ops.incident;

import com.bifrost.ops.event.EventLevel;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.incident.dto.IncidentReportResponse;
import com.bifrost.ops.incident.dto.IncidentResponse;
import com.bifrost.ops.incident.persistence.entity.IncidentEntity;
import com.bifrost.ops.incident.persistence.repository.IncidentRepository;
import com.bifrost.ops.monitoring.sli.UserImpactSliType;
import com.bifrost.ops.monitoring.slo.SloAlertRoute;
import com.bifrost.ops.monitoring.slo.SloBurnRateEvaluation;
import com.bifrost.ops.monitoring.slo.SloBurnRateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bifrost.ops.streaming.SsePublisher;
import com.bifrost.ops.event.EventService;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IncidentServiceTest {

    @Mock private IncidentRepository incidentRepository;
    @Mock private EventService eventService;
    @Mock private SsePublisher ssePublisher;
    @Mock private IncidentAnalysisTrigger incidentAnalysisTrigger;
    @Mock private SloBurnRateService sloBurnRateService;

    private final UUID tenantId = UUID.randomUUID();

    private IncidentService service() {
        return new IncidentService(incidentRepository, eventService, ssePublisher, incidentAnalysisTrigger);
    }

    @Test
    void listUsesStatusFilter() {
        IncidentEntity open = incident(UUID.randomUUID(), tenantId, "OPEN");
        when(incidentRepository.findByTenantIdAndStatusOrderByOpenedAtDesc(tenantId, "OPEN"))
                .thenReturn(List.of(open));

        assertThat(service().list(tenantId, "OPEN"))
                .singleElement()
                .satisfies(response -> {
                    assertThat(response.id()).isEqualTo(open.getId());
                    assertThat(response.status()).isEqualTo("OPEN");
                });
        verify(incidentRepository).findByTenantIdAndStatusOrderByOpenedAtDesc(tenantId, "OPEN");
    }

    @Test
    void getReturnsIncidentForTenant() {
        UUID incidentId = UUID.randomUUID();
        IncidentEntity incident = incident(incidentId, tenantId, "OPEN");
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));

        assertThat(service().get(tenantId, incidentId))
                .satisfies(response -> {
                    assertThat(response.id()).isEqualTo(incidentId);
                    assertThat(response.tenantId()).isEqualTo(tenantId);
                    assertThat(response.status()).isEqualTo("OPEN");
                    assertThat(response.title()).isEqualTo("Orders connector failed");
                    assertThat(response.sourceType()).isEqualTo("CONNECTOR");
                });
    }

    @Test
    void getRejectsMissingIncidentAs404() {
        UUID incidentId = UUID.randomUUID();
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().get(tenantId, incidentId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void getRejectsOtherTenantIncidentAs404() {
        UUID incidentId = UUID.randomUUID();
        when(incidentRepository.findById(incidentId))
                .thenReturn(Optional.of(incident(incidentId, UUID.randomUUID(), "OPEN")));

        assertThatThrownBy(() -> service().get(tenantId, incidentId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void thresholdViolationCreatesIncidentAndLinksEvent() {
        UUID pipelineId = UUID.randomUUID();
        String groupingKey = IncidentGroupingKeys.pipelineAvailability(pipelineId);
        when(incidentRepository.findByTenantIdAndGroupingKeyAndStatusInOrderByOpenedAtAsc(
                tenantId, groupingKey, List.of("OPEN", "INVESTIGATING"))).thenReturn(List.of());
        when(incidentRepository.save(any())).thenAnswer(inv -> {
            IncidentEntity incident = inv.getArgument(0);
            incident.setId(UUID.randomUUID());
            return incident;
        });

        service().onThresholdViolation(tenantId, groupingKey, "PIPELINE", pipelineId,
                EventLevel.ERROR, "Pipeline failed", "PIPELINE_STATUS_CHANGED", "boom", pipelineId);

        ArgumentCaptor<IncidentEntity> captor = ArgumentCaptor.forClass(IncidentEntity.class);
        verify(incidentRepository).save(captor.capture());
        IncidentEntity incident = captor.getValue();
        assertThat(incident.getGroupingKey()).isEqualTo(groupingKey);
        assertThat(incident.getSeverity()).isEqualTo("CRITICAL");
        assertThat(incident.getSeverityReason()).contains("slo_burn_rate=unavailable");
        assertThat(incident.getAlertRoute()).isEqualTo("PAGE");
        assertThat(incident.getSourceType()).isEqualTo("PIPELINE");
        assertThat(incident.getSourceId()).isEqualTo(pipelineId);
        verify(eventService).recordWithIncident(tenantId, pipelineId, EventLevel.ERROR,
                "PIPELINE_STATUS_CHANGED", "boom", incident.getId(), "PIPELINE");
        verify(ssePublisher).incidentEvent(eq(tenantId), eq("incident_opened"), any());
        verify(incidentAnalysisTrigger).startAfterCommit(tenantId, incident.getId(), "Pipeline failed", "boom");
    }

    @Test
    void causeOnlySignalWithoutUserImpactIsRecordedAsDiagnosticWithoutIncident() {
        UUID pipelineId = UUID.randomUUID();
        String groupingKey = IncidentGroupingKeys.consumerLag("connect-orders-sink");
        when(incidentRepository.findByTenantIdAndGroupingKeyAndStatusInOrderByOpenedAtAsc(
                tenantId, groupingKey, List.of("OPEN", "INVESTIGATING"))).thenReturn(List.of());
        when(sloBurnRateService.decideIncident(tenantId, EventLevel.ERROR,
                "CONSUMER_GROUP", "CONSUMER_LAG_CRITICAL", 1))
                .thenReturn(new SloBurnRateEvaluation(
                        UserImpactSliType.DATA_FRESHNESS,
                        SloAlertRoute.DIAGNOSTIC,
                        "WARNING",
                        "impact=none; urgency=diagnostic; slo_burn_rate=below_threshold; affected_resource_count=1",
                        "diagnostic",
                        null,
                        null,
                        UserImpactSliType.DATA_FRESHNESS.targetRatio(),
                        1));
        IncidentService svc = new IncidentService(
                incidentRepository, eventService, ssePublisher, incidentAnalysisTrigger, sloBurnRateService);

        svc.onThresholdViolation(tenantId, groupingKey, "CONSUMER_GROUP", pipelineId,
                EventLevel.ERROR, "Consumer lag critical", "CONSUMER_LAG_CRITICAL", "lag too high", pipelineId);

        verify(incidentRepository, never()).save(any());
        verify(eventService).record(eq(tenantId), eq(pipelineId), eq(EventLevel.INFO),
                eq("CONSUMER_LAG_CRITICAL"), org.mockito.ArgumentMatchers.contains("impact=none"));
        verify(incidentAnalysisTrigger, never()).startAfterCommit(any(), any(), any(), any());
    }

    @Test
    void thresholdViolationLocksGroupBeforeLookup() {
        UUID pipelineId = UUID.randomUUID();
        String groupingKey = IncidentGroupingKeys.pipelineAvailability(pipelineId);
        when(incidentRepository.findByTenantIdAndGroupingKeyAndStatusInOrderByOpenedAtAsc(
                tenantId, groupingKey, List.of("OPEN", "INVESTIGATING"))).thenReturn(List.of());
        when(incidentRepository.save(any())).thenAnswer(inv -> {
            IncidentEntity incident = inv.getArgument(0);
            incident.setId(UUID.randomUUID());
            return incident;
        });

        service().onThresholdViolation(tenantId, groupingKey, "PIPELINE", pipelineId,
                EventLevel.ERROR, "Pipeline failed", "PIPELINE_STATUS_CHANGED", "boom", pipelineId);

        InOrder inOrder = inOrder(incidentRepository);
        inOrder.verify(incidentRepository).lockIncidentGroup(tenantId + ":" + groupingKey);
        inOrder.verify(incidentRepository).findByTenantIdAndGroupingKeyAndStatusInOrderByOpenedAtAsc(
                tenantId, groupingKey, List.of("OPEN", "INVESTIGATING"));
    }

    @Test
    void thresholdViolationPublishesSseAfterCommitWhenTransactionSynchronizationIsActive() {
        UUID pipelineId = UUID.randomUUID();
        String groupingKey = IncidentGroupingKeys.pipelineAvailability(pipelineId);
        when(incidentRepository.findByTenantIdAndGroupingKeyAndStatusInOrderByOpenedAtAsc(
                tenantId, groupingKey, List.of("OPEN", "INVESTIGATING"))).thenReturn(List.of());
        when(incidentRepository.save(any())).thenAnswer(inv -> {
            IncidentEntity incident = inv.getArgument(0);
            incident.setId(UUID.randomUUID());
            return incident;
        });

        TransactionSynchronizationManager.initSynchronization();
        try {
            service().onThresholdViolation(tenantId, groupingKey, "PIPELINE", pipelineId,
                    EventLevel.ERROR, "Pipeline failed", "PIPELINE_STATUS_CHANGED", "boom", pipelineId);

            verify(ssePublisher, never()).incidentEvent(any(), any(), any());
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(ssePublisher).incidentEvent(eq(tenantId), eq("incident_opened"), any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void thresholdViolationReusesOpenIncidentAndEscalatesWarnSeverity() {
        UUID pipelineId = UUID.randomUUID();
        UUID incidentId = UUID.randomUUID();
        String groupingKey = IncidentGroupingKeys.pipelineAvailability(pipelineId);
        IncidentEntity incident = incident(incidentId, tenantId, "OPEN");
        incident.setGroupingKey(groupingKey);
        incident.setSeverity("WARNING");
        when(incidentRepository.findByTenantIdAndGroupingKeyAndStatusInOrderByOpenedAtAsc(
                tenantId, groupingKey, List.of("OPEN", "INVESTIGATING"))).thenReturn(List.of(incident));
        when(incidentRepository.save(incident)).thenReturn(incident);

        service().onThresholdViolation(tenantId, groupingKey, "PIPELINE", pipelineId,
                EventLevel.ERROR, "Pipeline failed", "PIPELINE_STATUS_CHANGED", "boom", pipelineId);

        assertThat(incident.getSeverity()).isEqualTo("CRITICAL");
        verify(incidentRepository).findByTenantIdAndGroupingKeyAndStatusInOrderByOpenedAtAsc(
                tenantId, groupingKey, List.of("OPEN", "INVESTIGATING"));
        verify(incidentRepository).save(incident);
        verify(eventService).recordWithIncident(tenantId, pipelineId, EventLevel.ERROR,
                "PIPELINE_STATUS_CHANGED", "boom", incidentId, "PIPELINE");
        verify(ssePublisher).incidentEvent(eq(tenantId), eq("incident_updated"), any());
        verify(incidentAnalysisTrigger, never()).startAfterCommit(any(), any(), any(), any());
    }

    @Test
    void thresholdViolationReusesOldestOpenIncidentAndClosesDuplicateRows() {
        UUID pipelineId = UUID.randomUUID();
        String groupingKey = IncidentGroupingKeys.pipelineAvailability(pipelineId);
        IncidentEntity primary = incident(UUID.randomUUID(), tenantId, "OPEN");
        primary.setGroupingKey(groupingKey);
        IncidentEntity duplicate = incident(UUID.randomUUID(), tenantId, "OPEN");
        duplicate.setGroupingKey(groupingKey);
        when(incidentRepository.findByTenantIdAndGroupingKeyAndStatusInOrderByOpenedAtAsc(
                tenantId, groupingKey, List.of("OPEN", "INVESTIGATING"))).thenReturn(List.of(primary, duplicate));
        when(incidentRepository.save(duplicate)).thenReturn(duplicate);

        service().onThresholdViolation(tenantId, groupingKey, "PIPELINE", pipelineId,
                EventLevel.ERROR, "Pipeline failed", "PIPELINE_STATUS_CHANGED", "boom", pipelineId);

        assertThat(duplicate.getStatus()).isEqualTo("RESOLVED");
        assertThat(duplicate.getResolvedAt()).isNotNull();
        verify(eventService).recordWithIncident(tenantId, pipelineId, EventLevel.ERROR,
                "PIPELINE_STATUS_CHANGED", "boom", primary.getId(), "PIPELINE");
        verify(ssePublisher, times(2)).incidentEvent(eq(tenantId), eq("incident_updated"), any());
    }

    @Test
    void recoveryOnCriticalKeepsOpenAndReturnsTrue() {
        // 스펙 B.7: CRITICAL은 복구돼도 자동 닫기 없음(사용자 확인 필요).
        UUID pipelineId = UUID.randomUUID();
        String groupingKey = IncidentGroupingKeys.pipelineAvailability(pipelineId);
        UUID incidentId = UUID.randomUUID();
        IncidentEntity incident = incident(incidentId, tenantId, "OPEN"); // severity=CRITICAL
        incident.setGroupingKey(groupingKey);
        when(incidentRepository.findByTenantIdAndGroupingKeyAndStatusInOrderByOpenedAtAsc(
                tenantId, groupingKey, List.of("OPEN", "INVESTIGATING"))).thenReturn(List.of(incident));

        boolean handled = service().onRecovery(tenantId, groupingKey,
                "PIPELINE_STATUS_CHANGED", "recovered", pipelineId);

        assertThat(handled).isTrue();
        assertThat(incident.getStatus()).isEqualTo("OPEN");          // 자동 닫기 안 함
        verify(incidentRepository, never()).save(any());             // 상태 변경 없음
        verify(eventService).recordWithIncident(eq(tenantId), eq(pipelineId), eq(EventLevel.INFO),
                eq("PIPELINE_STATUS_CHANGED"),
                org.mockito.ArgumentMatchers.contains("확인 후 직접 해소"), eq(incidentId), eq(null));
        verify(ssePublisher, never()).incidentEvent(any(), any(), any());
    }

    @Test
    void recoveryOnWarningTransitionsToInvestigating() {
        // 스펙 B.7: WARNING은 복구 시 OPEN→INVESTIGATING + 사용자 복구 알림.
        UUID pipelineId = UUID.randomUUID();
        String groupingKey = IncidentGroupingKeys.pipelineAvailability(pipelineId);
        UUID incidentId = UUID.randomUUID();
        IncidentEntity incident = incident(incidentId, tenantId, "OPEN");
        incident.setGroupingKey(groupingKey);
        incident.setSeverity("WARNING");
        when(incidentRepository.findByTenantIdAndGroupingKeyAndStatusInOrderByOpenedAtAsc(
                tenantId, groupingKey, List.of("OPEN", "INVESTIGATING"))).thenReturn(List.of(incident));
        when(incidentRepository.save(incident)).thenReturn(incident);

        boolean handled = service().onRecovery(tenantId, groupingKey,
                "PIPELINE_STATUS_CHANGED", "recovered", pipelineId);

        assertThat(handled).isTrue();
        assertThat(incident.getStatus()).isEqualTo("INVESTIGATING");
        verify(ssePublisher).incidentEvent(eq(tenantId), eq("incident_updated"), any());
    }

    @Test
    void recoveryReturnsFalseWhenNoOpenIncidentExists() {
        UUID pipelineId = UUID.randomUUID();
        String groupingKey = IncidentGroupingKeys.pipelineAvailability(pipelineId);
        when(incidentRepository.findByTenantIdAndGroupingKeyAndStatusInOrderByOpenedAtAsc(
                tenantId, groupingKey, List.of("OPEN", "INVESTIGATING"))).thenReturn(List.of());

        boolean resolved = service().onRecovery(tenantId, groupingKey,
                "PIPELINE_STATUS_CHANGED", "recovered", pipelineId);

        assertThat(resolved).isFalse();
        verify(incidentRepository, never()).save(any());
        verify(eventService, never()).recordWithIncident(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void singleWarnDoesNotCreateIncidentButLogsEvent() {
        // 스펙 B.7: WARN 단건은 이벤트 로그만, 인시던트 미생성(30분 2건 게이팅).
        UUID pipelineId = UUID.randomUUID();
        String groupingKey = IncidentGroupingKeys.pipelineAvailability(pipelineId);
        when(incidentRepository.findByTenantIdAndGroupingKeyAndStatusInOrderByOpenedAtAsc(
                tenantId, groupingKey, List.of("OPEN", "INVESTIGATING"))).thenReturn(List.of());

        service().onThresholdViolation(tenantId, groupingKey, "CONSUMER_GROUP", pipelineId,
                EventLevel.WARN, "lag", "CONSUMER_LAG_WARNING", "lag high", pipelineId);

        verify(incidentRepository, never()).save(any());
        verify(eventService).record(tenantId, pipelineId, EventLevel.WARN, "CONSUMER_LAG_WARNING", "lag high");
        verify(incidentAnalysisTrigger, never()).startAfterCommit(any(), any(), any(), any());
    }

    @Test
    void secondWarnWithin30MinCreatesWarningIncident() {
        UUID pipelineId = UUID.randomUUID();
        String groupingKey = IncidentGroupingKeys.pipelineAvailability(pipelineId);
        when(incidentRepository.findByTenantIdAndGroupingKeyAndStatusInOrderByOpenedAtAsc(
                tenantId, groupingKey, List.of("OPEN", "INVESTIGATING"))).thenReturn(List.of());
        when(incidentRepository.save(any())).thenAnswer(inv -> {
            IncidentEntity i = inv.getArgument(0);
            i.setId(UUID.randomUUID());
            return i;
        });

        IncidentService svc = service();
        svc.onThresholdViolation(tenantId, groupingKey, "CONSUMER_GROUP", pipelineId,
                EventLevel.WARN, "lag", "CONSUMER_LAG_WARNING", "lag high", pipelineId);
        svc.onThresholdViolation(tenantId, groupingKey, "CONSUMER_GROUP", pipelineId,
                EventLevel.WARN, "lag", "CONSUMER_LAG_WARNING", "lag high", pipelineId);

        ArgumentCaptor<IncidentEntity> captor = ArgumentCaptor.forClass(IncidentEntity.class);
        verify(incidentRepository).save(captor.capture()); // 2번째에만 생성
        assertThat(captor.getValue().getSeverity()).isEqualTo("WARNING");
    }

    @Test
    void transitionStatusToInvestigating() {
        UUID incidentId = UUID.randomUUID();
        IncidentEntity incident = incident(incidentId, tenantId, "OPEN");
        when(incidentRepository.findByIdAndTenantId(incidentId, tenantId)).thenReturn(Optional.of(incident));
        when(incidentRepository.save(incident)).thenReturn(incident);

        assertThat(service().transitionStatus(tenantId, incidentId, "investigating").status())
                .isEqualTo("INVESTIGATING");
        assertThat(incident.getStatus()).isEqualTo("INVESTIGATING");
    }

    @Test
    void transitionStatusResolvedSetsResolvedAt() {
        UUID incidentId = UUID.randomUUID();
        IncidentEntity incident = incident(incidentId, tenantId, "INVESTIGATING");
        when(incidentRepository.findByIdAndTenantId(incidentId, tenantId)).thenReturn(Optional.of(incident));
        when(incidentRepository.save(incident)).thenReturn(incident);

        service().transitionStatus(tenantId, incidentId, "RESOLVED");
        assertThat(incident.getStatus()).isEqualTo("RESOLVED");
        assertThat(incident.getResolvedAt()).isNotNull();
    }

    @Test
    void transitionRejectsAlreadyResolved() {
        UUID incidentId = UUID.randomUUID();
        IncidentEntity incident = incident(incidentId, tenantId, "RESOLVED");
        when(incidentRepository.findByIdAndTenantId(incidentId, tenantId)).thenReturn(Optional.of(incident));

        assertThatThrownBy(() -> service().transitionStatus(tenantId, incidentId, "OPEN"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void transitionRejectsInvalidStatus() {
        assertThatThrownBy(() -> service().transitionStatus(tenantId, UUID.randomUUID(), "BOGUS"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void backfillRcaFromReportSummaryWhenMissing() {
        // (#595) rca가 비어 있고 리포트가 있으면 리포트 요약으로 backfill.
        UUID incidentId = UUID.randomUUID();
        IncidentEntity incident = incident(incidentId, tenantId, "OPEN"); // rca=null
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
        when(incidentRepository.save(incident)).thenReturn(incident);

        ObjectNode body = new ObjectMapper().createObjectNode();
        body.put("summary", "소스 DB 연결 끊김으로 CDC 중단");
        IncidentReportResponse report = new IncidentReportResponse(
                "r1", "run1", incidentId.toString(), "rc1", 0.9, true, body,
                Instant.parse("2026-06-11T00:00:00Z"));

        IncidentResponse result = service().backfillRcaIfMissing(
                tenantId, incidentId, com.bifrost.ops.incident.dto.IncidentResponse.from(incident), List.of(report));

        assertThat(incident.getRca()).isEqualTo("소스 DB 연결 끊김으로 CDC 중단");
        assertThat(result.rca()).isEqualTo("소스 DB 연결 끊김으로 CDC 중단");
    }

    @Test
    void backfillRcaFromAnswerFieldWhenMissing() {
        // (#595) 실제 ai-service incident_analysis 리포트는 본문 필드가 "answer"다.
        UUID incidentId = UUID.randomUUID();
        IncidentEntity incident = incident(incidentId, tenantId, "OPEN"); // rca=null
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
        when(incidentRepository.save(incident)).thenReturn(incident);

        ObjectNode body = new ObjectMapper().createObjectNode();
        body.put("mode", "incident_analysis");
        body.put("answer", "싱크 DB 연결 실패로 커넥터 task 중단 — DB 가용성 확인 필요");
        IncidentReportResponse report = new IncidentReportResponse(
                "r1", "run1", incidentId.toString(), null, null, true, body,
                Instant.parse("2026-06-12T00:00:00Z"));

        IncidentResponse result = service().backfillRcaIfMissing(
                tenantId, incidentId, com.bifrost.ops.incident.dto.IncidentResponse.from(incident), List.of(report));

        assertThat(result.rca()).isEqualTo("싱크 DB 연결 실패로 커넥터 task 중단 — DB 가용성 확인 필요");
    }

    @Test
    void backfillRcaNoOpWhenRcaAlreadyPresent() {
        UUID incidentId = UUID.randomUUID();
        IncidentEntity incident = incident(incidentId, tenantId, "OPEN");
        incident.setRca("기존 RCA");

        IncidentResponse result = service().backfillRcaIfMissing(
                tenantId, incidentId, com.bifrost.ops.incident.dto.IncidentResponse.from(incident), List.of());

        verify(incidentRepository, never()).save(any());
        assertThat(result.rca()).isEqualTo("기존 RCA");
    }

    @Test
    void resolveForDeletedPipelineResolvesPipelineScopedActiveIncidents() {
        // (#692) 파이프라인 삭제 시 그 파이프라인 한정 grouping_key의 활성 인시던트를 RESOLVED 처리.
        UUID pipelineId = UUID.randomUUID();
        IncidentEntity inc = incident(UUID.randomUUID(), tenantId, "OPEN");
        inc.setGroupingKey(IncidentGroupingKeys.connectorWorker("orders-source"));
        String sourceKey = IncidentGroupingKeys.connectorWorker("orders-source");
        when(incidentRepository.findByTenantIdAndGroupingKeyAndStatusInOrderByOpenedAtAsc(
                eq(tenantId), any(), any()))
                .thenAnswer(invocation ->
                        sourceKey.equals(invocation.getArgument(1)) ? List.of(inc) : List.of());

        int n = service().resolveForDeletedPipeline(
                tenantId, pipelineId, "topic.orders",
                List.of("orders-source", "orders-sink"), "connect-orders-sink");

        assertThat(n).isEqualTo(1);
        assertThat(inc.getStatus()).isEqualTo("RESOLVED");
        assertThat(inc.getResolvedAt()).isNotNull();
        // 공유 자원(datasource:{id})은 grouping_key 조회 대상에서 제외된다.
        verify(incidentRepository, never()).findByTenantIdAndGroupingKeyAndStatusInOrderByOpenedAtAsc(
                eq(tenantId), org.mockito.ArgumentMatchers.startsWith("datasource:"), any());
    }

    private static IncidentEntity incident(UUID incidentId, UUID tenantId, String status) {
        IncidentEntity incident = new IncidentEntity();
        incident.setId(incidentId);
        incident.setTenantId(tenantId);
        incident.setGroupingKey("connector:orders");
        incident.setSeverity("CRITICAL");
        incident.setStatus(status);
        incident.setTitle("Orders connector failed");
        incident.setSourceType("CONNECTOR");
        incident.setSourceId(UUID.randomUUID());
        incident.setOpenedAt(Instant.parse("2026-06-09T00:00:00Z"));
        return incident;
    }
}
