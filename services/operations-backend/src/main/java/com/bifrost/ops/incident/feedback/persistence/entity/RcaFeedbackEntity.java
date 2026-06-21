package com.bifrost.ops.incident.feedback.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code rca_feedback} 테이블 (#964). RCA 결과에 대한 운영자 피드백(채택/거부/수정).
 * 축적되면 RCA 평가(AC@k)·confidence 캘리브레이션(ECE)용 gold set 으로 사용한다.
 */
@Entity
@Table(name = "rca_feedback")
public class RcaFeedbackEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    @Column(name = "run_id", length = 255)
    private String runId;

    /** RCA 가 제시한 root cause(피드백 시점 스냅샷). */
    @Column(name = "rca_root_cause_id", length = 255)
    private String rcaRootCauseId;

    @Column(name = "rca_confidence")
    private Double rcaConfidence;

    /** ACCEPTED / REJECTED / CORRECTED */
    @Column(nullable = false, length = 20)
    private String verdict;

    /** CORRECTED 일 때 운영자가 지정한 올바른 root cause. */
    @Column(name = "corrected_root_cause_id", length = 255)
    private String correctedRootCauseId;

    @Column(name = "trigger_label", columnDefinition = "text")
    private String triggerLabel;

    @Column(name = "symptom_label", columnDefinition = "text")
    private String symptomLabel;

    /** 피드백을 남긴 운영자(email). */
    @Column(length = 255)
    private String operator;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getIncidentId() { return incidentId; }
    public void setIncidentId(UUID incidentId) { this.incidentId = incidentId; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getRcaRootCauseId() { return rcaRootCauseId; }
    public void setRcaRootCauseId(String rcaRootCauseId) { this.rcaRootCauseId = rcaRootCauseId; }
    public Double getRcaConfidence() { return rcaConfidence; }
    public void setRcaConfidence(Double rcaConfidence) { this.rcaConfidence = rcaConfidence; }
    public String getVerdict() { return verdict; }
    public void setVerdict(String verdict) { this.verdict = verdict; }
    public String getCorrectedRootCauseId() { return correctedRootCauseId; }
    public void setCorrectedRootCauseId(String correctedRootCauseId) { this.correctedRootCauseId = correctedRootCauseId; }
    public String getTriggerLabel() { return triggerLabel; }
    public void setTriggerLabel(String triggerLabel) { this.triggerLabel = triggerLabel; }
    public String getSymptomLabel() { return symptomLabel; }
    public void setSymptomLabel(String symptomLabel) { this.symptomLabel = symptomLabel; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
