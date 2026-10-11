CREATE TABLE tb_owner_cancellation_delivery (
    expo_id UUID NOT NULL,
    application_type VARCHAR(20) NOT NULL,
    application_id VARCHAR(40) NOT NULL,
    participant_id BIGINT NOT NULL CHECK (participant_id > 0),
    operation_id UUID PRIMARY KEY,
    action VARCHAR(10) NOT NULL CHECK (action IN ('ACQUIRING', 'ABORT', 'COMMIT')),
    cancellation_revision BIGINT CHECK (cancellation_revision > 0),
    CHECK (action <> 'COMMIT' OR cancellation_revision IS NOT NULL),
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_owner_cancellation_active
    ON tb_owner_cancellation_delivery (expo_id, application_type, application_id);
CREATE INDEX ix_owner_cancellation_due ON tb_owner_cancellation_delivery (next_attempt_at);
