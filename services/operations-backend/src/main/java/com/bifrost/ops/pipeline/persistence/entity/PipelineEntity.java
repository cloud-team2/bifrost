package com.bifrost.ops.pipeline.persistence.entity;

import com.bifrost.ops.pipeline.PipelineLifecycle;
import com.bifrost.ops.provisioning.dto.PipelinePattern;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code pipelines} 테이블 매핑(#71). 파이프라인 도메인의 영속 엔티티.
 *
 * <p>하나의 pipeline은 하나의 topic/table만 담당한다({@code schemaName}/{@code tableName} 단일).
 * 상태({@link PipelineLifecycle})는 부록 B.1 정본이며, 변경은 단일 writer(#70)를 통한다.
 * 자격증명은 datasource의 {@code secret_ref}로만 참조하고 이 엔티티에는 저장하지 않는다.
 */
@Entity
@Table(name = "pipelines",
       uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "name"}))
public class PipelineEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 100)
    private String name;

    /** 레거시 coarse 분류(CDC/SYNC). pattern이 권위값이다. */
    @Column(nullable = false, length = 20)
    private String type;

    @Enumerated(EnumType.STRING)
    @Column(name = "pattern", length = 20)
    private PipelinePattern pattern;

    @Column(name = "source_datasource_id", nullable = false)
    private UUID sourceDatasourceId;

    @Column(name = "sink_datasource_id")
    private UUID sinkDatasourceId;

    /** 담당 테이블 목록(JSONB, NOT NULL). 단일 테이블 모델이므로 {@code ["schema.table"]}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tables", columnDefinition = "jsonb", nullable = false)
    private String tables;

    @Column(name = "schema_name", length = 255)
    private String schemaName;

    @Column(name = "table_name", length = 255)
    private String tableName;

    @Column(name = "source_connector_name", length = 255)
    private String sourceConnectorName;

    @Column(name = "sink_connector_name", length = 255)
    private String sinkConnectorName;

    @Column(name = "topic_name", length = 255)
    private String topicName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PipelineLifecycle status;

    @Column(name = "status_message")
    private String statusMessage;

    @Column(name = "status_updated_at")
    private Instant statusUpdatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (status == null) status = PipelineLifecycle.CREATING;
        if (statusUpdatedAt == null) statusUpdatedAt = now;
        if (type == null) type = "CDC";
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public PipelinePattern getPattern() { return pattern; }
    public void setPattern(PipelinePattern pattern) { this.pattern = pattern; }
    public UUID getSourceDatasourceId() { return sourceDatasourceId; }
    public void setSourceDatasourceId(UUID sourceDatasourceId) { this.sourceDatasourceId = sourceDatasourceId; }
    public UUID getSinkDatasourceId() { return sinkDatasourceId; }
    public void setSinkDatasourceId(UUID sinkDatasourceId) { this.sinkDatasourceId = sinkDatasourceId; }
    public String getTables() { return tables; }
    public void setTables(String tables) { this.tables = tables; }
    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String schemaName) { this.schemaName = schemaName; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public String getSourceConnectorName() { return sourceConnectorName; }
    public void setSourceConnectorName(String sourceConnectorName) { this.sourceConnectorName = sourceConnectorName; }
    public String getSinkConnectorName() { return sinkConnectorName; }
    public void setSinkConnectorName(String sinkConnectorName) { this.sinkConnectorName = sinkConnectorName; }
    public String getTopicName() { return topicName; }
    public void setTopicName(String topicName) { this.topicName = topicName; }
    public PipelineLifecycle getStatus() { return status; }
    public void setStatus(PipelineLifecycle status) { this.status = status; }
    public String getStatusMessage() { return statusMessage; }
    public void setStatusMessage(String statusMessage) { this.statusMessage = statusMessage; }
    public Instant getStatusUpdatedAt() { return statusUpdatedAt; }
    public void setStatusUpdatedAt(Instant statusUpdatedAt) { this.statusUpdatedAt = statusUpdatedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
