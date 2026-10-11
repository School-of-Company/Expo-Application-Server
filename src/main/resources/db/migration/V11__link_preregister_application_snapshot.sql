-- Historical applications cannot be assigned a reservation by phone or current party membership.
ALTER TABLE tb_preregister_application ADD COLUMN reservation_request_id VARCHAR(100);
ALTER TABLE tb_preregister_application ADD CONSTRAINT fk_preregister_application_reservation
    FOREIGN KEY (expo_id, reservation_request_id) REFERENCES tb_preregister_reservation (expo_id, request_id);
CREATE INDEX ix_preregister_owner ON tb_preregister_application (expo_id, representative_id, id);
CREATE INDEX ix_program_session_owner ON tb_program_session_application (expo_id, participant_id, created_at, id);
CREATE INDEX ix_preregister_application_history ON tb_preregister_transition (application_id, id);
CREATE INDEX ix_program_application_history ON tb_program_session_history (application_id, id);
