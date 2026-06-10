ALTER TABLE idempotency_key
    ADD COLUMN operation VARCHAR(100),
    ADD COLUMN params_hash VARCHAR(64),
    ADD COLUMN response_status VARCHAR(20),
    ADD COLUMN http_status INTEGER,
    ADD COLUMN approval_id UUID;
