package com.bifrost.ops.database.persistence.entity;

import com.bifrost.ops.global.common.datasource.DbType;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "datasources",
       uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "name"}))
public class DatasourceEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "db_type", nullable = false, length = 20)
    private DbType dbType;

    @Column(nullable = false, length = 255)
    private String host;

    @Column(nullable = false)
    private int port;

    @Column(name = "db_name", nullable = false, length = 255)
    private String dbName;

    @Column(nullable = false, length = 255)
    private String username;

    @Column(name = "secret_ref", nullable = false, length = 255)
    private String secretRef;

    @Column(name = "cdc_readiness_status", length = 20)
    private String cdcReadinessStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cdc_readiness_report", columnDefinition = "jsonb")
    private String cdcReadinessReport;

    @Column(name = "last_inspected_at")
    private Instant lastInspectedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    // Getters / Setters (생략. Lombok @Data 또는 IDE 자동 생성 사용)
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public DbType getDbType() { return dbType; }
    public void setDbType(DbType dbType) { this.dbType = dbType; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getDbName() { return dbName; }
    public void setDbName(String dbName) { this.dbName = dbName; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getSecretRef() { return secretRef; }
    public void setSecretRef(String secretRef) { this.secretRef = secretRef; }
}
