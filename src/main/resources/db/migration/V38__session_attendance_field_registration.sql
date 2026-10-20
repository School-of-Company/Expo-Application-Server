-- #249: 사전등록을 취소한 참가자가 현장등록으로 다시 등록하면 Attendance의 취소 기록을 푼다. 회차 없는 PUT이다.
ALTER TABLE tb_session_attendance_outbox ADD COLUMN field_registered BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE tb_session_attendance_outbox ADD CONSTRAINT ck_session_attendance_field_registered
    CHECK (NOT field_registered OR (session_id IS NULL AND NOT cancelled));
