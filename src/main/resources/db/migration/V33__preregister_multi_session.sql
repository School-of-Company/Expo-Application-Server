-- #198: 사전등록은 사람당 여러 회차를 받는다. 확정 신청은 회차마다 한 번이다.
DROP INDEX uq_preregister_active_participant;
CREATE UNIQUE INDEX uq_preregister_active_participant ON tb_preregister_application (expo_id, session_id, participant_id)
    WHERE status = 'CONFIRMED';
-- 회차 하나만 해제하는 Attendance DELETE. session_id가 NULL인 행은 기존처럼 그 참가자의 모든 회차를 해제한다.
ALTER TABLE tb_session_attendance_outbox ADD COLUMN cancelled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE tb_session_attendance_outbox ADD CONSTRAINT ck_session_attendance_cancelled_session
    CHECK (NOT cancelled OR session_id IS NOT NULL);
