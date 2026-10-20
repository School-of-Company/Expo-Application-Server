-- #221: 회차 취소 가드 commit에 함께 보낼 취소 회차. NULL이면 참가자 전체 취소로 보낸다.
ALTER TABLE tb_owner_cancellation_delivery ADD COLUMN session_id BIGINT CHECK (session_id > 0);
