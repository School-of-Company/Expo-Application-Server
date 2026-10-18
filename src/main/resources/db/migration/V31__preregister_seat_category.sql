-- #173: 사전등록 좌석 구분(교사·예비교사 TEACHER / 그 외 OTHER).
-- 기존 행과 직업 답변이 없는 대표자는 NULL(미상)이며 추정해서 채우지 않는다.
-- #169 V29·V30(동행자 추가·대기) 다음 번호를 쓴다.
ALTER TABLE tb_preregister_application
    ADD COLUMN seat_category varchar(10),
    ADD CONSTRAINT ck_preregister_seat_category CHECK (seat_category IN ('TEACHER', 'OTHER'));

-- 예약 시점 신청 스냅샷의 구분별 인원. applicants - teacher - other 가 미상 인원이다.
ALTER TABLE tb_preregister_reservation
    ADD COLUMN teacher_applicants integer,
    ADD COLUMN other_applicants integer,
    ADD CONSTRAINT ck_preregister_reservation_category
        CHECK ((teacher_applicants IS NULL) = (other_applicants IS NULL) AND
               (teacher_applicants IS NULL OR
                (teacher_applicants >= 0 AND other_applicants >= 0 AND teacher_applicants + other_applicants <= applicants)));

CREATE FUNCTION preserve_preregister_seat_category() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.seat_category IS DISTINCT FROM OLD.seat_category THEN
        RAISE EXCEPTION 'preregister seat category is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_preserve_preregister_seat_category
    BEFORE UPDATE OF seat_category ON tb_preregister_application
    FOR EACH ROW EXECUTE FUNCTION preserve_preregister_seat_category();
