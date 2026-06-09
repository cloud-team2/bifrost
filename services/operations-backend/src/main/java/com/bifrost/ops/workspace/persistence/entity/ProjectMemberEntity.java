package com.bifrost.ops.workspace.persistence.entity;

import com.bifrost.ops.workspace.Role;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_member")
public class ProjectMemberEntity {

    @EmbeddedId
    private ProjectMemberId id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    protected ProjectMemberEntity() {
    }

    public ProjectMemberEntity(UUID workspaceId, UUID userId, Role role) {
        this.id = new ProjectMemberId(workspaceId, userId);
        this.role = role;
    }

    @PrePersist
    void onCreate() {
        if (joinedAt == null) joinedAt = Instant.now();
    }

    public ProjectMemberId getId() { return id; }
    public UUID getWorkspaceId() { return id.getWorkspaceId(); }
    public UUID getUserId() { return id.getUserId(); }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public Instant getJoinedAt() { return joinedAt; }
}
