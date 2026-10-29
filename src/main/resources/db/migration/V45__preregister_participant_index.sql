CREATE INDEX IF NOT EXISTS ix_preregister_application_participant
    ON tb_preregister_application (expo_id, participant_id) INCLUDE (status);
