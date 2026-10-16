CREATE TABLE tb_legacy_trainee_attribution_operation (
    id UUID PRIMARY KEY,
    expo_id UUID NOT NULL,
    trainee_id BIGINT NOT NULL CHECK (trainee_id > 0),
    participant_id BIGINT NOT NULL CHECK (participant_id > 0),
    actor_admin_id BIGINT NOT NULL CHECK (actor_admin_id > 0),
    reason VARCHAR(500) NOT NULL CHECK (length(trim(reason)) > 0),
    policy_reference TEXT NOT NULL,
    revoke BOOLEAN NOT NULL,
    state VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'APPLIED', 'REJECTED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    error_status INTEGER,
    result JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX uk_legacy_attribution_pending_trainee
    ON tb_legacy_trainee_attribution_operation (trainee_id) WHERE state = 'PENDING';
CREATE UNIQUE INDEX uk_legacy_attribution_pending_participant
    ON tb_legacy_trainee_attribution_operation (expo_id, participant_id) WHERE state = 'PENDING';

CREATE TABLE tb_legacy_trainee_attribution (
    trainee_id BIGINT PRIMARY KEY CHECK (trainee_id > 0),
    expo_id UUID NOT NULL,
    participant_id BIGINT NOT NULL CHECK (participant_id > 0),
    operation_id UUID NOT NULL REFERENCES tb_legacy_trainee_attribution_operation(id),
    UNIQUE (expo_id, participant_id)
);

CREATE TABLE tb_legacy_trainee_program (
    trainee_id BIGINT NOT NULL REFERENCES tb_legacy_trainee_attribution(trainee_id) ON DELETE CASCADE,
    program_id BIGINT NOT NULL CHECK (program_id > 0),
    definition JSONB NOT NULL CHECK (jsonb_typeof(definition) = 'object'),
    PRIMARY KEY (trainee_id, program_id)
);
