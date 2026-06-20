ALTER TABLE incidents
    ADD COLUMN IF NOT EXISTS severity_reason TEXT,
    ADD COLUMN IF NOT EXISTS alert_route VARCHAR(20);
