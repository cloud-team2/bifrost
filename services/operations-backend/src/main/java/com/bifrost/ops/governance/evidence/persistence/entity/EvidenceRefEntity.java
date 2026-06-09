package com.bifrost.ops.governance.evidence.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** {@code evidence_ref} 테이블. mutation 전후 스냅샷(S3). */
@Entity
@Table(name = "evidence_ref")
public class EvidenceRefEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "mutation_id")
    private UUID mutationId;

    @Column(nullable = false, length = 10)
    private String stage; // BEFORE / AFTER

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String snapshot;

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
    public UUID getMutationId() { return mutationId; }
    public void setMutationId(UUID mutationId) { this.mutationId = mutationId; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public String getSnapshot() { return snapshot; }
    public void setSnapshot(String snapshot) { this.snapshot = snapshot; }
    public Instant getCreatedAt() { return createdAt; }
}
