-- #182: 사전등록 관리자 취소·대기 철회의 관리자 ID. 프로그램 이력 actor_id와 같은 의미이며 ADMIN일 때만 값이 있다.
-- 과거 행은 NULL로 두고 추정하지 않는다.
ALTER TABLE tb_preregister_transition
    ADD COLUMN actor_id BIGINT,
    ADD CONSTRAINT ck_preregister_transition_actor_id CHECK (actor_id IS NULL OR actor_type = 'ADMIN');
ALTER TABLE tb_preregister_reservation
    ADD COLUMN withdrawn_actor_id BIGINT,
    ADD CONSTRAINT ck_preregister_reservation_withdrawn_actor_id CHECK (withdrawn_actor_id IS NULL OR withdrawn_actor_type = 'ADMIN');
