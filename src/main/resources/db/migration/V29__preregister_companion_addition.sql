-- #169: 확정된 사전등록 대표자 일행에 동행자를 더하는 좌석 예약. NULL은 기존 일행 신청(대표자 포함 전체)이다.
-- 값이 있으면 그 대표자에게 새 동행자만 붙이는 예약이며, 기존 RESERVED 복구 경로로 User 저장과 확정을 끝낸다.
ALTER TABLE tb_preregister_reservation ADD COLUMN companion_representative_id BIGINT
    CHECK (companion_representative_id IS NULL OR companion_representative_id > 0);
-- 대기열에는 들어가지 않는다. 정원이 모자라면 추가를 거절한다.
ALTER TABLE tb_preregister_reservation ADD CONSTRAINT ck_preregister_companion_not_waiting
    CHECK (companion_representative_id IS NULL OR queue_seq IS NULL);
-- 대표자 한 명의 동행자 추가는 동시에 하나만 진행한다.
CREATE UNIQUE INDEX uq_preregister_companion_in_progress ON tb_preregister_reservation (expo_id, companion_representative_id)
    WHERE companion_representative_id IS NOT NULL AND state = 'RESERVED';
