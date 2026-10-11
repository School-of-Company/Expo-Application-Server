CREATE INDEX ix_program_session_admin_page
    ON tb_program_session_application (expo_id, created_at, id);

CREATE INDEX ix_program_session_history_application
    ON tb_program_session_history (application_id, created_at, id);
