ALTER TABLE tb_program_session_history
    ADD COLUMN actor_type VARCHAR(10) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (actor_type IN ('USER', 'ADMIN', 'SYSTEM', 'UNKNOWN')),
    ADD COLUMN previous_status VARCHAR(10)
        CHECK (previous_status IN ('CONFIRMED', 'WAITING', 'CANCELLED')),
    ADD COLUMN previous_status_recorded BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE tb_program_session_history ADD CONSTRAINT ck_activity_previous_status
    CHECK (previous_status_recorded OR previous_status IS NULL);
