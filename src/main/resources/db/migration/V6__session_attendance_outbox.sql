CREATE TABLE tb_session_attendance_outbox (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    expo_id UUID NOT NULL,
    participant_id BIGINT NOT NULL CHECK (participant_id > 0),
    session_id BIGINT CHECK (session_id > 0),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_session_attendance_outbox_participant
    ON tb_session_attendance_outbox (expo_id, participant_id, id);
