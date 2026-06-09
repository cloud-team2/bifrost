package com.bifrost.ops.governance.changemanagement.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** {@code change_ticket} 테이블. 변경 티켓(S3). */
@Entity
@Table(name = "change_ticket")
public class ChangeTicketEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, length = 20)
    private String status = "OPEN"; // OPEN / CLOSED

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
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
}
