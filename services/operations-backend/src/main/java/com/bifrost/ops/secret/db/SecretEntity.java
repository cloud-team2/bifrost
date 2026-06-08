package com.bifrost.ops.secret.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "secrets")
class SecretEntity {

    @Id
    @Column(name = "secret_ref", length = 63)
    private String secretRef;

    @Column(name = "credential_json", nullable = false)
    private String credentialJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    String getSecretRef() { return secretRef; }
    void setSecretRef(String secretRef) { this.secretRef = secretRef; }
    String getCredentialJson() { return credentialJson; }
    void setCredentialJson(String credentialJson) { this.credentialJson = credentialJson; }
}
