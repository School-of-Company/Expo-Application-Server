-- 동시 생성 실패 시 pg_index.indisvalid를 확인한다. INVALID이면 트랜잭션 밖에서
-- DROP INDEX CONCURRENTLY IF EXISTS ix_preregister_application_participant;
-- 실행 후 Flyway repair로 V45 실패 이력을 정리하고 migrate를 재실행한다.
-- IF NOT EXISTS는 남은 INVALID 인덱스를 복구하지 않는다.
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_preregister_application_participant
    ON tb_preregister_application (expo_id, participant_id);
