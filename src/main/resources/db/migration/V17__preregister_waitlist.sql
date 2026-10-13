-- Preregistration waitlist (#99): a waiting party is a reservation that has not been delivered to User.
-- Withdrawn waiting parties use the existing terminal REJECTED state so pre-#99 code never delivers them.
ALTER TABLE tb_preregister_reservation DROP CONSTRAINT tb_preregister_reservation_state_check;
ALTER TABLE tb_preregister_reservation ADD CHECK (state IN ('RESERVED', 'COMPLETED', 'REJECTED', 'WAITING'));
CREATE SEQUENCE seq_preregister_waitlist;
ALTER TABLE tb_preregister_reservation
    ADD COLUMN queue_seq BIGINT,
    ADD COLUMN withdrawn_at TIMESTAMPTZ,
    ADD CHECK (state <> 'WAITING' OR queue_seq IS NOT NULL),
    ADD CHECK (withdrawn_at IS NULL OR (state = 'REJECTED' AND queue_seq IS NOT NULL));
CREATE INDEX ix_preregister_waitlist ON tb_preregister_reservation (expo_id, session_id, queue_seq)
    INCLUDE (applicants) WHERE state = 'WAITING';
