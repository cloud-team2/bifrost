package com.bifrost.ops.provisioning.persistence.entity;

import com.bifrost.ops.provisioning.dto.ConnectorKind;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * connector 메타데이터 행(설계 §4 Data Model 3.5, 테이블 {@code connectors}).
 *
 * <p>파이프라인 1개가 EDA면 connector 1행(source), CDC면 2행(source/sink)을 가진다.
 * {@code state}/{@code lastError}/{@code updatedAt}는 watcher(#46)가 KafkaConnector CR 상태를
 * 받아 갱신한다. 자격증명 등 비밀값은 저장하지 않는다.
 */
@Entity
@Table(name = "connectors",
       uniqueConstraints = @UniqueConstraint(columnNames = {"cr_name"}))
public class ConnectorEntity {

    @Id
    private UUID id;

    @Column(name = "pipeline_id", nullable = false)
    private UUID pipelineId;

    @Column(name = "cr_name", nullable = false, length = 255)
    private String crName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConnectorKind kind;

    @Column(name = "connector_class", nullable = false, length = 255)
    private String connectorClass;

    @Column(length = 30)
    private String state;

    @Column(name = "tasks_max", nullable = false)
    private int tasksMax;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getPipelineId() { return pipelineId; }
    public void setPipelineId(UUID pipelineId) { this.pipelineId = pipelineId; }
    public String getCrName() { return crName; }
    public void setCrName(String crName) { this.crName = crName; }
    public ConnectorKind getKind() { return kind; }
    public void setKind(ConnectorKind kind) { this.kind = kind; }
    public String getConnectorClass() { return connectorClass; }
    public void setConnectorClass(String connectorClass) { this.connectorClass = connectorClass; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public int getTasksMax() { return tasksMax; }
    public void setTasksMax(int tasksMax) { this.tasksMax = tasksMax; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
