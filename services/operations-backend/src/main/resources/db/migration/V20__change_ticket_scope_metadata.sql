ALTER TABLE change_ticket
    ADD COLUMN rollback_plan TEXT,
    ADD COLUMN impact_analysis TEXT,
    ADD COLUMN scope_operation VARCHAR(100);
