-- #284: 사전등록 원장 보고 스냅샷이 같은 저장·만료·커서 규칙을 쓴다. 기존 행은 프로그램 신청 스냅샷이다.
-- 커서는 생성한 원천에서만 읽으므로 프로그램 커서로 사전등록 스냅샷을(반대도) 읽지 못한다.
ALTER TABLE tb_report_application_snapshot
    ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'PROGRAM' CHECK (source IN ('PROGRAM', 'PREREGISTRATION'));
