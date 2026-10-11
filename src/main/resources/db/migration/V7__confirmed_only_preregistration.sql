-- Existing waiting applications need an explicit operator decision; never silently promote or cancel them.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM tb_preregister_application WHERE status = 'WAITING') THEN
        RAISE EXCEPTION 'Resolve legacy WAITING applications before deploying confirmed-only preregistration';
    END IF;
END $$;

ALTER TABLE tb_preregister_application DROP CONSTRAINT tb_preregister_application_status_check;
ALTER TABLE tb_preregister_application ADD CHECK (status IN ('CONFIRMED', 'CANCELLED'));
ALTER TABLE tb_preregister_transition DROP CONSTRAINT tb_preregister_transition_status_check;
-- Transition history remains historical; new transitions cannot be WAITING.
ALTER TABLE tb_preregister_transition ADD CHECK (status IN ('CONFIRMED', 'CANCELLED')) NOT VALID;
DROP INDEX uq_preregister_active_participant;
CREATE UNIQUE INDEX uq_preregister_active_participant ON tb_preregister_application (expo_id, participant_id)
    WHERE status = 'CONFIRMED';
ALTER TABLE tb_preregister_session_state DROP COLUMN auto_promote;

CREATE TABLE tb_preregister_reservation (
    expo_id UUID NOT NULL,
    request_id VARCHAR(100) NOT NULL,
    session_id BIGINT NOT NULL,
    command TEXT NOT NULL,
    user_request TEXT NOT NULL,
    applicants INTEGER NOT NULL CHECK (applicants BETWEEN 1 AND 5),
    state VARCHAR(16) NOT NULL DEFAULT 'RESERVED' CHECK (state IN ('RESERVED', 'COMPLETED', 'REJECTED')),
    result TEXT,
    error_status INTEGER,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (expo_id, request_id)
);
CREATE INDEX ix_preregister_reservation_retry ON tb_preregister_reservation (next_attempt_at) WHERE state = 'RESERVED';
CREATE INDEX ix_preregister_reserved_seats ON tb_preregister_reservation (expo_id, session_id)
    INCLUDE (applicants) WHERE state = 'RESERVED';
CREATE TABLE tb_preregister_count_outbox (
    expo_id UUID NOT NULL,
    participant_id BIGINT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (expo_id, participant_id)
);
