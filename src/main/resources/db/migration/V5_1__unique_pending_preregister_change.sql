CREATE UNIQUE INDEX uq_preregister_pending_change
    ON tb_preregister_session_change (expo_id, session_id)
    WHERE NOT completed;
