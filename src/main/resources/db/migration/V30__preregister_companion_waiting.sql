-- #169 2단계: 자리가 모자란 동행자 추가도 회차 대기열(FIFO)에 넣고 #144 규칙(전날 전 자동 승급, 전날부터 참여 제안)으로 확정한다.
ALTER TABLE tb_preregister_reservation DROP CONSTRAINT ck_preregister_companion_not_waiting;
-- 대표자 한 명의 동행자 추가는 대기 중인 것까지 포함해 동시에 하나만 둔다.
DROP INDEX uq_preregister_companion_in_progress;
CREATE UNIQUE INDEX uq_preregister_companion_in_progress ON tb_preregister_reservation (expo_id, companion_representative_id)
    WHERE companion_representative_id IS NOT NULL AND state IN ('RESERVED', 'WAITING');
