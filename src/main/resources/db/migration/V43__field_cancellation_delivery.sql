CREATE TABLE tb_field_cancellation_delivery (
    registration_id uuid PRIMARY KEY REFERENCES tb_field_visit_registration(id),
    expo_id uuid NOT NULL,
    participant_id bigint NOT NULL CHECK (participant_id > 0),
    visit_date date NOT NULL,
    generation bigint NOT NULL CHECK (generation > 0),
    operation_id uuid NOT NULL UNIQUE,
    actor_participant_id bigint NOT NULL CHECK (actor_participant_id > 0),
    action varchar(16) NOT NULL CHECK (action IN ('ACQUIRING', 'COMMIT', 'ABORT')),
    next_attempt_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_field_cancellation_delivery_retry ON tb_field_cancellation_delivery(next_attempt_at);
