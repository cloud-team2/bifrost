ALTER TABLE change_ticket
    ADD COLUMN window_start TIMESTAMPTZ,
    ADD COLUMN window_end TIMESTAMPTZ;
