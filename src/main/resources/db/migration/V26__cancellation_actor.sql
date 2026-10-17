-- #143: the authenticated User representative who executed a cancellation, kept apart from the target participant.
-- actor_id stays the admin ID. Existing rows keep NULL actor references and are never inferred.
ALTER TABLE tb_program_session_history
    ADD COLUMN actor_participant_id BIGINT,
    ADD CONSTRAINT ck_activity_actor_participant CHECK (actor_participant_id IS NULL OR actor_type = 'USER');

-- created_at is added without a default first so historic transitions keep an unknown (NULL) time.
ALTER TABLE tb_preregister_transition
    ADD COLUMN created_at TIMESTAMPTZ,
    ADD COLUMN actor_type VARCHAR(10) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (actor_type IN ('USER', 'ADMIN', 'SYSTEM', 'UNKNOWN')),
    ADD COLUMN actor_participant_id BIGINT,
    ADD CONSTRAINT ck_preregister_transition_actor_participant CHECK (actor_participant_id IS NULL OR actor_type = 'USER');
ALTER TABLE tb_preregister_transition ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;

-- USER means the reservation's own representative, proven by the session phone ownership check; the reference is the
-- reservation (expo_id, request_id) itself. NULL is a historic withdrawal whose actor was not recorded.
ALTER TABLE tb_preregister_reservation
    ADD COLUMN withdrawn_actor_type VARCHAR(10)
        CHECK (withdrawn_actor_type IN ('USER', 'ADMIN', 'SYSTEM', 'UNKNOWN')),
    ADD CONSTRAINT ck_preregister_reservation_withdrawn_actor CHECK (withdrawn_actor_type IS NULL OR withdrawn_at IS NOT NULL);
