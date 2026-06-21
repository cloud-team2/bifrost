package com.bifrost.ops.incident.feedback.dto;

import com.bifrost.ops.incident.feedback.persistence.entity.RcaFeedbackEntity;

import java.time.Instant;
import java.util.UUID;

/** #964 RCA 피드백 응답 / gold set 레코드. */
public record RcaFeedbackResponse(
        UUID id,
        UUID incidentId,
        String runId,
        String rcaRootCauseId,
        Double rcaConfidence,
        String verdict,
        String correctedRootCauseId,
        String triggerLabel,
        String symptomLabel,
        String operator,
        Instant createdAt
) {
    public static RcaFeedbackResponse from(RcaFeedbackEntity e) {
        return new RcaFeedbackResponse(
                e.getId(), e.getIncidentId(), e.getRunId(),
                e.getRcaRootCauseId(), e.getRcaConfidence(),
                e.getVerdict(), e.getCorrectedRootCauseId(),
                e.getTriggerLabel(), e.getSymptomLabel(),
                e.getOperator(), e.getCreatedAt());
    }
}
