-- #162: 관리자 대시보드 갱신 신호. 신청 원장의 실제 상태 변화만 행사 ID로 NOTIFY 한다.
-- NOTIFY는 커밋된 트랜잭션만 전달되고 롤백되면 버려진다. 같은 트랜잭션의 같은 payload는 한 번만 전달된다.
-- 모든 Application 인스턴스가 같은 DB 채널을 LISTEN 하므로 변경을 처리한 인스턴스와 구독 인스턴스가 달라도 전달된다.
CREATE FUNCTION notify_admin_application_change() RETURNS TRIGGER
    LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_notify(
        'application_admin_changes',
        (CASE WHEN TG_OP = 'DELETE' THEN OLD.expo_id ELSE NEW.expo_id END)::TEXT
    );
    RETURN NULL;
END
$$;

-- 프로그램·연수 신청 원장: 신청, 취소, 승급, 삭제
CREATE TRIGGER tr_program_session_application_admin_change
    AFTER INSERT OR DELETE ON tb_program_session_application
    FOR EACH ROW EXECUTE FUNCTION notify_admin_application_change();
CREATE TRIGGER tr_program_session_application_admin_status
    AFTER UPDATE OF status ON tb_program_session_application
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status)
    EXECUTE FUNCTION notify_admin_application_change();

-- 사전등록 확정 원장: 확정, 취소, 승급 확정
CREATE TRIGGER tr_preregister_application_admin_change
    AFTER INSERT OR DELETE ON tb_preregister_application
    FOR EACH ROW EXECUTE FUNCTION notify_admin_application_change();
CREATE TRIGGER tr_preregister_application_admin_status
    AFTER UPDATE OF status ON tb_preregister_application
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status)
    EXECUTE FUNCTION notify_admin_application_change();

-- 사전등록 좌석 예약과 대기 묶음: 예약, 대기 등록, 완료·거절, 철회. 재시도 시각·오류 갱신은 신호를 만들지 않는다.
CREATE TRIGGER tr_preregister_reservation_admin_change
    AFTER INSERT OR DELETE ON tb_preregister_reservation
    FOR EACH ROW EXECUTE FUNCTION notify_admin_application_change();
CREATE TRIGGER tr_preregister_reservation_admin_state
    AFTER UPDATE OF state, withdrawn_at ON tb_preregister_reservation
    FOR EACH ROW WHEN (OLD.state IS DISTINCT FROM NEW.state OR OLD.withdrawn_at IS DISTINCT FROM NEW.withdrawn_at)
    EXECUTE FUNCTION notify_admin_application_change();

-- 참여 제안(#144): 좌석을 잡거나 놓을 때만. 발송 단계 사이의 상태 변화는 좌석 수를 바꾸지 않는다.
CREATE TRIGGER tr_promotion_offer_admin_change
    AFTER INSERT ON tb_promotion_offer
    FOR EACH ROW EXECUTE FUNCTION notify_admin_application_change();
CREATE TRIGGER tr_promotion_offer_admin_hold
    AFTER UPDATE OF state ON tb_promotion_offer
    FOR EACH ROW WHEN (
        (OLD.state IN ('SCHEDULED', 'PUBLISHED', 'SENT', 'UNKNOWN', 'DELIVERY_FAILED'))
            IS DISTINCT FROM (NEW.state IN ('SCHEDULED', 'PUBLISHED', 'SENT', 'UNKNOWN', 'DELIVERY_FAILED'))
    )
    EXECUTE FUNCTION notify_admin_application_change();
