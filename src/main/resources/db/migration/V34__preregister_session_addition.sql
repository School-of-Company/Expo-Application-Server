-- #199: 이미 신청한 일행의 다른 회차 추가. User 참가자가 이미 있어 User에 저장하지 않고 예약 result에 참석자를 미리 담는다.
ALTER TABLE tb_preregister_reservation ADD COLUMN session_addition BOOLEAN NOT NULL DEFAULT FALSE;
