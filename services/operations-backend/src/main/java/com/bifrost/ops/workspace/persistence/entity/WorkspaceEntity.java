package com.bifrost.ops.workspace.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenants")
public class WorkspaceEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 100, unique = true)
    private String name;

    @Column(nullable = false, length = 63, unique = true)
    private String namespace;

    @Column(length = 50)
    private String timezone;

    /** 워크스페이스 소유자(생성자) user id. 소유 기반 다중 워크스페이스 scope의 기준(#72). */
    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public enum Status {
        PROVISIONING,
        ACTIVE,
        FAILED
    }

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = Status.PROVISIONING;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public UUID getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(UUID ownerUserId) { this.ownerUserId = ownerUserId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
