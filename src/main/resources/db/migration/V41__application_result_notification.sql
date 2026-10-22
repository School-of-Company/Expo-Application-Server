-- #308: 신청 결과 문자(Notification #110)용 별도 outbox. tb_program_session_event.delivered_at(승급 안내)과 섞지 않는다.
-- 행은 상태 변경과 같은 트랜잭션에서만 쌓고, 과거 이력은 채우지 않는다. delivered_at은 Kafka ACK이지 문자 수신이 아니다.
CREATE TABLE tb_application_notification (
    id BIGSERIAL PRIMARY KEY,
    -- 작업별 안정 키. 같은 상태 변경은 한 번만 쌓이고, 발행 eventId는 이 키에서 만들어 재시도·복구에도 같다.
    dedupe_key TEXT NOT NULL,
    -- 같은 트랜잭션에서 쌓인 행을 한 통의 문자로 묶는다.
    batch_key TEXT NOT NULL DEFAULT txid_current()::text,
    type TEXT NOT NULL,
    expo_id UUID NOT NULL,
    application_id UUID,
    preregister_application_id BIGINT,
    request_id TEXT,
    session_id BIGINT,
    participant_id BIGINT,
    result TEXT,
    cancelled_by TEXT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    delivered_at TIMESTAMPTZ,
    skipped_at TIMESTAMPTZ,
    skip_reason TEXT,
    CONSTRAINT ck_application_notification_type CHECK (type IN (
        'PROGRAM_CONFIRMED', 'PROGRAM_WAITLISTED', 'PROGRAM_CANCELLED',
        'PREREGISTER_CANCELLED', 'PREREGISTER_SESSION_ADDED', 'OFFER_EXPIRED'))
);
CREATE UNIQUE INDEX uk_application_notification_dedupe ON tb_application_notification(dedupe_key);
CREATE INDEX ix_application_notification_due ON tb_application_notification(next_attempt_at, id)
    WHERE delivered_at IS NULL AND skipped_at IS NULL;
