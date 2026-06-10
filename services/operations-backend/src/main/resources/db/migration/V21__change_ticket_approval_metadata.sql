ALTER TABLE change_ticket
    ADD COLUMN required_approver UUID,
    ADD COLUMN requested_by UUID,
    ADD COLUMN approved_by UUID,
    ADD COLUMN approved_at TIMESTAMPTZ;
