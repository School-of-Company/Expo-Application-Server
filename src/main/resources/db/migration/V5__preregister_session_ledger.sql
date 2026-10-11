CREATE TABLE tb_preregister_session_state (
    expo_id UUID NOT NULL,
    session_id BIGINT NOT NULL CHECK (session_id > 0),
    definition TEXT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    auto_promote BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (expo_id, session_id)
);
CREATE TABLE tb_preregister_session_change (
    expo_id UUID NOT NULL,
    session_id BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    command TEXT NOT NULL,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (expo_id, session_id, revision)
);
CREATE TABLE tb_preregister_application (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    expo_id UUID NOT NULL,
    session_id BIGINT NOT NULL,
    representative_id BIGINT NOT NULL CHECK (representative_id > 0),
    participant_id BIGINT NOT NULL CHECK (participant_id > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('CONFIRMED', 'WAITING', 'CANCELLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (expo_id, session_id) REFERENCES tb_preregister_session_state
);
CREATE UNIQUE INDEX uq_preregister_active_participant ON tb_preregister_application (expo_id, participant_id)
    WHERE status IN ('CONFIRMED', 'WAITING');
CREATE INDEX ix_preregister_session_queue ON tb_preregister_application (expo_id, session_id, status, id);
CREATE TABLE tb_preregister_request (
    expo_id UUID NOT NULL,
    request_id VARCHAR(100) NOT NULL,
    command TEXT NOT NULL,
    receipt TEXT NOT NULL,
    PRIMARY KEY (expo_id, request_id)
);
CREATE TABLE tb_preregister_deleted_expo (expo_id UUID PRIMARY KEY);
CREATE TABLE tb_preregister_transition (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    application_id BIGINT NOT NULL,
    expo_id UUID NOT NULL,
    session_id BIGINT NOT NULL,
    participant_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('CONFIRMED', 'WAITING', 'CANCELLED'))
);
