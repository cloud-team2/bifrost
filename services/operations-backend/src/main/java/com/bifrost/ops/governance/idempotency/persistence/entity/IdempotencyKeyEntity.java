package com.bifrost.ops.governance.idempotency.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** {@code idempotency_key} 테이블. 동일 요청 중복 실행 방지(S3). */
@Entity
@Table(name = "idempotency_key",
       uniqueConstraints = @UniqueConstraint(columnNames = {"idem_key", "tenant_id"}))
public class IdempotencyKeyEntity {

    @Id
    private UUID id;

    @Column(name = "idem_key", nullable = false, length = 128)
    private String idemKey;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 20)
    private String status = "PROCESSING"; // PROCESSING / DONE

    @Column(length = 100)
    private String operation;

    @Column(name = "params_hash", length = 64)
    private String paramsHash;

    @Column(name = "response_status", length = 20)
    private String responseStatus;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "approval_id")
    private UUID approvalId;

    @Column(name = "change_ticket_id")
    private UUID changeTicketId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String result;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getIdemKey() { return idemKey; }
    public void setIdemKey(String idemKey) { this.idemKey = idemKey; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }
    public String getParamsHash() { return paramsHash; }
    public void setParamsHash(String paramsHash) { this.paramsHash = paramsHash; }
    public String getResponseStatus() { return responseStatus; }
    public void setResponseStatus(String responseStatus) { this.responseStatus = responseStatus; }
    public Integer getHttpStatus() { return httpStatus; }
    public void setHttpStatus(Integer httpStatus) { this.httpStatus = httpStatus; }
    public UUID getApprovalId() { return approvalId; }
    public void setApprovalId(UUID approvalId) { this.approvalId = approvalId; }
    public UUID getChangeTicketId() { return changeTicketId; }
    public void setChangeTicketId(UUID changeTicketId) { this.changeTicketId = changeTicketId; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
}
