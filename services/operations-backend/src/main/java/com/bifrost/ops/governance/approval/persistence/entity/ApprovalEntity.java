package com.bifrost.ops.governance.approval.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** {@code approval} 테이블. mutation 승인 토큰(S3). */
@Entity
@Table(name = "approval")
public class ApprovalEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 255)
    private String actor;

    @Column(nullable = false, length = 100)
    private String operation;

    @Column(name = "params_hash", nullable = false, length = 64)
    private String paramsHash;

    @Column(nullable = false, length = 20)
    private String decision = "PENDING";

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

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
    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }
    public String getParamsHash() { return paramsHash; }
    public void setParamsHash(String paramsHash) { this.paramsHash = paramsHash; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getUsedAt() { return usedAt; }
    public void setUsedAt(Instant usedAt) { this.usedAt = usedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
