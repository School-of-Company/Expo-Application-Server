-- Expo #112: 신청 당시 채널 이름. 채널 없는 회차와 기존 신청은 NULL.
-- V18(#107 접수번호)·V19(#108 페이지 조회) 병행 작업 다음 번호를 쓴다.
ALTER TABLE tb_program_session_application ADD COLUMN channel VARCHAR(50);
