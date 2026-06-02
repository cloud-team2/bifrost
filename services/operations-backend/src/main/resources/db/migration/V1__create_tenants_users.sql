CREATE TABLE tenants (
    id          UUID PRIMARY KEY,
    name        VARCHAR(100) UNIQUE NOT NULL,
    namespace   VARCHAR(63) UNIQUE NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'PROVISIONING',
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    email           VARCHAR(255) UNIQUE NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_users_tenant ON users(tenant_id);
