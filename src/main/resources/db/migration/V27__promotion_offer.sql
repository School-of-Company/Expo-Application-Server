-- #144: from 00:00 KST the day before a session starts, a waitlist vacancy becomes a participation offer that the
-- representative must accept before anything is confirmed. The waiting rows keep their WAITING status, so pre-#144
-- code and the existing CHECK/trigger contracts are untouched; an active offer row is the seat hold.
-- Active states hold seats: SCHEDULED (outbox, not yet handed to Kafka), PUBLISHED (Kafka ACK, provider result unknown
-- to Application), SENT (provider accepted, response deadline running), UNKNOWN and DELIVERY_FAILED (never read as a
-- user decline or no-response; released only at the hard deadline). The rest are terminal.
-- Targets are plain IDs like the reservation reference: waiting rows are never deleted, and holds join on their status.
CREATE TABLE tb_promotion_offer (
    id UUID PRIMARY KEY,
    target VARCHAR(12) NOT NULL CHECK (target IN ('PROGRAM', 'PREREGISTER')),
    expo_id UUID NOT NULL,
    program_type VARCHAR(8) CHECK (program_type IN ('STANDARD', 'TRAINING')),
    program_id BIGINT,
    channel VARCHAR(50),
    application_id UUID,
    batch_id UUID,
    session_id BIGINT,
    request_id VARCHAR(100),
    seats INTEGER NOT NULL CHECK (seats > 0),
    state VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED' CHECK (state IN (
        'SCHEDULED', 'PUBLISHED', 'SENT', 'UNKNOWN', 'DELIVERY_FAILED', 'ACCEPTED', 'DECLINED', 'EXPIRED', 'VOIDED')),
    hard_deadline TIMESTAMPTZ NOT NULL,
    send_after TIMESTAMPTZ NOT NULL,
    event_id UUID NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    published_at TIMESTAMPTZ,
    attempt_id UUID,
    attempted_at TIMESTAMPTZ,
    provider_accepted_at TIMESTAMPTZ,
    delivery_status VARCHAR(16),
    delivery_reason VARCHAR(100),
    expires_at TIMESTAMPTZ,
    responded_at TIMESTAMPTZ,
    responder_type VARCHAR(10) CHECK (responder_type IN ('USER', 'SYSTEM')),
    responder_participant_id BIGINT,
    void_reason VARCHAR(40),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK ((target = 'PROGRAM' AND application_id IS NOT NULL AND program_type IS NOT NULL AND program_id IS NOT NULL
            AND session_id IS NULL AND request_id IS NULL)
        OR (target = 'PREREGISTER' AND session_id IS NOT NULL AND request_id IS NOT NULL
            AND application_id IS NULL AND batch_id IS NULL AND program_id IS NULL)),
    CHECK (send_after < hard_deadline),
    -- A response deadline exists only from a real provider acceptance and never passes the existing hard cutoffs.
    CHECK ((expires_at IS NULL) = (provider_accepted_at IS NULL)),
    CHECK (expires_at IS NULL OR (expires_at > provider_accepted_at AND expires_at <= hard_deadline)),
    CHECK (state <> 'SENT' OR expires_at IS NOT NULL),
    CHECK (state <> 'ACCEPTED' OR expires_at IS NOT NULL),
    CHECK (responder_participant_id IS NULL OR responder_type = 'USER')
);
CREATE UNIQUE INDEX uk_promotion_offer_event ON tb_promotion_offer(event_id);
CREATE UNIQUE INDEX uk_promotion_offer_active_application ON tb_promotion_offer(application_id)
    WHERE state IN ('SCHEDULED', 'PUBLISHED', 'SENT', 'UNKNOWN', 'DELIVERY_FAILED');
CREATE UNIQUE INDEX uk_promotion_offer_active_batch ON tb_promotion_offer(batch_id)
    WHERE state IN ('SCHEDULED', 'PUBLISHED', 'SENT', 'UNKNOWN', 'DELIVERY_FAILED');
CREATE UNIQUE INDEX uk_promotion_offer_active_reservation ON tb_promotion_offer(expo_id, request_id)
    WHERE state IN ('SCHEDULED', 'PUBLISHED', 'SENT', 'UNKNOWN', 'DELIVERY_FAILED');
CREATE INDEX ix_promotion_offer_program_hold ON tb_promotion_offer(expo_id, program_type, program_id)
    INCLUDE (seats, channel, application_id) WHERE state IN ('SCHEDULED', 'PUBLISHED', 'SENT', 'UNKNOWN', 'DELIVERY_FAILED');
CREATE INDEX ix_promotion_offer_session_hold ON tb_promotion_offer(expo_id, session_id)
    INCLUDE (seats, request_id) WHERE state IN ('SCHEDULED', 'PUBLISHED', 'SENT', 'UNKNOWN', 'DELIVERY_FAILED');
CREATE INDEX ix_promotion_offer_due ON tb_promotion_offer(next_attempt_at) WHERE state = 'SCHEDULED';
