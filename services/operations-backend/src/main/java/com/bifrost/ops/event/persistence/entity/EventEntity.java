package com.bifrost.ops.event.persistence.entity;

import com.bifrost.ops.event.EventLevel;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** {@code events} 테이블(#70). append-only 운영 이벤트 로그(부록 B.6). */
@Entity
@Table(name = "events")
public class EventEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "pipeline_id")
    private UUID pipelineId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EventLevel level;

    @Column(nullable = false, length = 50)
    private String type;

    @Column(columnDefinition = "text")
    private String message;

    /** 거버넌스 연결 컬럼(S2 — V16). */
    @Column(name = "incident_id")
    private UUID incidentId;

    @Column(length = 50)
    private String category;

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
    public UUID getPipelineId() { return pipelineId; }
    public void setPipelineId(UUID pipelineId) { this.pipelineId = pipelineId; }
    public EventLevel getLevel() { return level; }
    public void setLevel(EventLevel level) { this.level = level; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public UUID getIncidentId() { return incidentId; }
    public void setIncidentId(UUID incidentId) { this.incidentId = incidentId; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public Instant getCreatedAt() { return createdAt; }
}
