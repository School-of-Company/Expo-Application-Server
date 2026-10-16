ALTER TABLE tb_program_session_application
    ADD COLUMN admission_route varchar(20),
    ADD CONSTRAINT ck_program_admission_route
        CHECK (admission_route IN ('PRE_REGISTRATION', 'ONSITE'));

CREATE INDEX ix_program_admission_route
    ON tb_program_session_application (expo_id, program_type, program_id, admission_route, status);

CREATE FUNCTION preserve_program_admission_route() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.admission_route IS DISTINCT FROM OLD.admission_route THEN
        RAISE EXCEPTION 'program admission route is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_preserve_program_admission_route
    BEFORE UPDATE OF admission_route ON tb_program_session_application
    FOR EACH ROW EXECUTE FUNCTION preserve_program_admission_route();
