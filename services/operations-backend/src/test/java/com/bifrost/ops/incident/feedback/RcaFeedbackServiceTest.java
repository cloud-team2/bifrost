package com.bifrost.ops.incident.feedback;

import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import com.bifrost.ops.incident.feedback.dto.RcaFeedbackRequest;
import com.bifrost.ops.incident.feedback.dto.RcaFeedbackResponse;
import com.bifrost.ops.incident.feedback.persistence.entity.RcaFeedbackEntity;
import com.bifrost.ops.incident.feedback.persistence.repository.RcaFeedbackRepository;
import com.bifrost.ops.incident.persistence.entity.IncidentEntity;
import com.bifrost.ops.incident.persistence.repository.IncidentRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RcaFeedbackServiceTest {

    private final RcaFeedbackRepository feedbackRepository = mock(RcaFeedbackRepository.class);
    private final IncidentRepository incidentRepository = mock(IncidentRepository.class);
    private final RcaFeedbackService service = new RcaFeedbackService(feedbackRepository, incidentRepository);

    private final UUID tenantId = UUID.randomUUID();
    private final UUID incidentId = UUID.randomUUID();

    private void incidentExists() {
        when(incidentRepository.findByIdAndTenantId(incidentId, tenantId))
                .thenReturn(Optional.of(new IncidentEntity()));
        when(feedbackRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void submitAcceptedNormalizesVerdictAndStampsOperator() {
        incidentExists();
        RcaFeedbackRequest request = new RcaFeedbackRequest(
                "accepted", "run-1", "CONNECTOR_TASK_FAILED", 0.82, null, null, null);

        RcaFeedbackResponse response = service.submit(tenantId, incidentId, request, "ops@bifrost.io");

        ArgumentCaptor<RcaFeedbackEntity> captor = ArgumentCaptor.forClass(RcaFeedbackEntity.class);
        verify(feedbackRepository).save(captor.capture());
        RcaFeedbackEntity saved = captor.getValue();
        assertThat(saved.getVerdict()).isEqualTo("ACCEPTED");
        assertThat(saved.getTenantId()).isEqualTo(tenantId);
        assertThat(saved.getIncidentId()).isEqualTo(incidentId);
        assertThat(saved.getRcaRootCauseId()).isEqualTo("CONNECTOR_TASK_FAILED");
        assertThat(saved.getOperator()).isEqualTo("ops@bifrost.io");
        assertThat(response.verdict()).isEqualTo("ACCEPTED");
    }

    @Test
    void submitCorrectedRequiresCorrectedRootCause() {
        incidentExists();
        RcaFeedbackRequest request = new RcaFeedbackRequest(
                "corrected", null, "CONNECTOR_TASK_FAILED", 0.9, "  ", null, null);

        assertThatThrownBy(() -> service.submit(tenantId, incidentId, request, "ops@bifrost.io"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void submitCorrectedPersistsCorrection() {
        incidentExists();
        RcaFeedbackRequest request = new RcaFeedbackRequest(
                "corrected", "run-2", "CONNECTOR_TASK_FAILED", 0.92, "SINK_DB_CONNECTION_TIMEOUT", "sink down", "task failed");

        service.submit(tenantId, incidentId, request, "ops@bifrost.io");

        ArgumentCaptor<RcaFeedbackEntity> captor = ArgumentCaptor.forClass(RcaFeedbackEntity.class);
        verify(feedbackRepository).save(captor.capture());
        RcaFeedbackEntity saved = captor.getValue();
        assertThat(saved.getVerdict()).isEqualTo("CORRECTED");
        assertThat(saved.getCorrectedRootCauseId()).isEqualTo("SINK_DB_CONNECTION_TIMEOUT");
        assertThat(saved.getTriggerLabel()).isEqualTo("sink down");
        assertThat(saved.getSymptomLabel()).isEqualTo("task failed");
    }

    @Test
    void submitRejectsUnknownVerdict() {
        incidentExists();
        RcaFeedbackRequest request = new RcaFeedbackRequest("maybe", null, null, null, null, null, null);

        assertThatThrownBy(() -> service.submit(tenantId, incidentId, request, "ops@bifrost.io"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void submitRejectsUnknownIncident() {
        when(incidentRepository.findByIdAndTenantId(incidentId, tenantId)).thenReturn(Optional.empty());
        RcaFeedbackRequest request = new RcaFeedbackRequest("accepted", null, null, null, null, null, null);

        assertThatThrownBy(() -> service.submit(tenantId, incidentId, request, "ops@bifrost.io"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        verify(feedbackRepository, never()).save(any());
    }
}
