CREATE TABLE tb_program_session_state (
    expo_id UUID NOT NULL,
    program_type VARCHAR(8) NOT NULL CHECK (program_type IN ('STANDARD', 'TRAINING')),
    program_id BIGINT NOT NULL,
    definition JSONB NOT NULL,
    pending_definition JSONB,
    pending_change_id UUID,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (expo_id, program_type, program_id)
);

CREATE TABLE tb_program_change_receipt (
    expo_id UUID NOT NULL,
    change_id UUID NOT NULL,
    payload JSONB NOT NULL,
    PRIMARY KEY (expo_id, change_id)
);

CREATE INDEX ix_program_session_fifo
    ON tb_program_session_application (expo_id, program_type, program_id, created_at, id)
    WHERE status = 'WAITING';

-- No Notification delivery contract exists yet. Keep durable events until a consumer is supplied.
CREATE TABLE tb_program_session_event (
    application_id UUID NOT NULL REFERENCES tb_program_session_application(id),
    status VARCHAR(10) NOT NULL CHECK (status IN ('CONFIRMED', 'WAITING', 'CANCELLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    delivered_at TIMESTAMPTZ,
    PRIMARY KEY (application_id, status)
);

ALTER TABLE tb_program_session_application ALTER COLUMN created_at SET DEFAULT clock_timestamp();
