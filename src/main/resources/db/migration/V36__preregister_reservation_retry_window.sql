-- #222: RESERVED 예약의 재시도가 끝나지 않을 때 needsReview로 넘기는 기준(횟수·경과 시간)의 시작점.
-- 기존 행은 마이그레이션 시각부터 센다. 운영자 재시도(retryReviewed)는 이 창을 다시 연다.
ALTER TABLE tb_preregister_reservation
    ADD COLUMN retry_window_started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN retry_window_attempts INTEGER NOT NULL DEFAULT 0;
