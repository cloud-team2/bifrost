package com.bifrost.ops.governance.audit.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** {@code audit_events} 테이블(#70). append-only 감사 로그. 비밀값은 저장하지 않는다. */
@Entity
@Table(name = "audit_events")
public class AuditEventEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(length = 255)
    private String actor;

    @Column(nullable = false, length = 100)
    private String action;

    @Column(name = "target_type", length = 50)
    private String targetType;

    @Column(name = "target_id")
    private UUID targetId;

    @Column(columnDefinition = "text")
    private String detail;

    /** 거버넌스 게이트 보강 필드(S3 — V15) */
    @Column(name = "policy_decision", length = 30)
    private String policyDecision;

    @Column(name = "approval_id")
    private UUID approvalId;

    @Column(name = "evidence_id")
    private UUID evidenceId;

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
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public UUID getTargetId() { return targetId; }
    public void setTargetId(UUID targetId) { this.targetId = targetId; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public String getPolicyDecision() { return policyDecision; }
    public void setPolicyDecision(String policyDecision) { this.policyDecision = policyDecision; }
    public UUID getApprovalId() { return approvalId; }
    public void setApprovalId(UUID approvalId) { this.approvalId = approvalId; }
    public UUID getEvidenceId() { return evidenceId; }
    public void setEvidenceId(UUID evidenceId) { this.evidenceId = evidenceId; }
    public Instant getCreatedAt() { return createdAt; }
}
