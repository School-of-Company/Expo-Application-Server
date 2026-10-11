ALTER TABLE tb_program_session_event
    ADD COLUMN event_id UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    ADD COLUMN is_promotion BOOLEAN NOT NULL DEFAULT FALSE;
CREATE UNIQUE INDEX uk_program_session_event_id ON tb_program_session_event(event_id);
CREATE INDEX ix_program_promotion_history ON tb_program_session_history(application_id, id) INCLUDE (status);
UPDATE tb_program_session_event e SET is_promotion = TRUE
WHERE e.status = 'CONFIRMED' AND EXISTS (
    SELECT 1 FROM tb_program_session_history w
    JOIN tb_program_session_history c ON c.application_id = w.application_id AND c.id > w.id AND c.status = 'CONFIRMED'
    WHERE w.application_id = e.application_id AND w.status = 'WAITING'
);
CREATE FUNCTION classify_program_promotion() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.is_promotion := NEW.status = 'CONFIRMED' AND EXISTS (
        SELECT 1 FROM tb_program_session_history w
        JOIN tb_program_session_history c ON c.application_id = w.application_id AND c.id > w.id AND c.status = 'CONFIRMED'
        WHERE w.application_id = NEW.application_id AND w.status = 'WAITING'
    );
    RETURN NEW;
END;
$$;
CREATE TRIGGER classify_program_promotion BEFORE INSERT ON tb_program_session_event
    FOR EACH ROW EXECUTE FUNCTION classify_program_promotion();
CREATE INDEX ix_program_promotion_due ON tb_program_session_event(created_at, next_attempt_at, event_id)
    WHERE is_promotion AND status = 'CONFIRMED' AND delivered_at IS NULL;
-- delivered_at means Kafka ACK persisted, never SMS delivery.
