CREATE TABLE tb_field_visit_registration (
    id uuid PRIMARY KEY,
    expo_id uuid NOT NULL,
    participant_id bigint NOT NULL CHECK (participant_id > 0),
    visit_date date NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'CANCELLED')),
    generation bigint NOT NULL CHECK (generation > 0),
    registered_at timestamptz NOT NULL,
    cancelled_at timestamptz,
    cancelled_by bigint,
    cancellation_pending boolean NOT NULL DEFAULT false,
    cancellation_operation_id uuid,
    delivery_pending boolean NOT NULL DEFAULT true,
    UNIQUE (expo_id, participant_id, visit_date)
);

CREATE TABLE tb_field_visit_event (
    operation_id uuid NOT NULL,
    registration_id uuid NOT NULL REFERENCES tb_field_visit_registration(id),
    generation bigint NOT NULL,
    event_type varchar(16) NOT NULL CHECK (event_type IN ('REGISTERED', 'CANCELLED')),
    occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor_participant_id bigint,
    PRIMARY KEY (registration_id, generation, event_type)
);

CREATE TABLE tb_field_visit_request (
    expo_id uuid NOT NULL,
    request_id varchar(100) NOT NULL,
    owner_hash varchar(64) NOT NULL,
    representative_id bigint,
    party_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    form_snapshot jsonb,
    visit_date date NOT NULL,
    command jsonb NOT NULL,
    user_result jsonb,
    planned_receipt jsonb,
    receipt jsonb,
    next_attempt_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    attempts integer NOT NULL DEFAULT 0,
    PRIMARY KEY (expo_id, request_id)
);

CREATE INDEX ix_field_visit_source ON tb_field_visit_registration(expo_id, id);
CREATE INDEX ix_field_visit_request_recovery ON tb_field_visit_request(next_attempt_at)
    WHERE receipt IS NULL AND attempts < 40;

-- No backfill: legacy FIELD and participant creation dates do not establish a visit date.
