CREATE TABLE tb_program_guardian_batch (
    id UUID PRIMARY KEY,
    expo_id UUID NOT NULL,
    program_type VARCHAR(8) NOT NULL CHECK (program_type IN ('STANDARD', 'TRAINING')),
    program_id BIGINT NOT NULL,
    representative_id BIGINT NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    command JSONB NOT NULL,
    receipt JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (expo_id, representative_id, idempotency_key)
);

CREATE TABLE tb_program_guardian_member (
    application_id UUID PRIMARY KEY REFERENCES tb_program_session_application(id),
    batch_id UUID NOT NULL REFERENCES tb_program_guardian_batch(id),
    role VARCHAR(8) NOT NULL CHECK (role IN ('STUDENT', 'GUARDIAN'))
);
CREATE INDEX ix_program_guardian_batch_member ON tb_program_guardian_member(batch_id);

CREATE TABLE tb_program_guardian_link (
    student_application_id UUID PRIMARY KEY REFERENCES tb_program_session_application(id),
    guardian_application_id UUID NOT NULL REFERENCES tb_program_session_application(id),
    CHECK (student_application_id <> guardian_application_id)
);
CREATE INDEX ix_program_guardian_students ON tb_program_guardian_link(guardian_application_id);

-- Deferred checks protect legacy cancellation/replacement paths too, after every local write has completed.
CREATE FUNCTION check_program_guardian_invariants() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    target_type VARCHAR(8);
    target_program BIGINT;
BEGIN
    IF TG_TABLE_NAME = 'tb_program_session_application' THEN
        target_type := COALESCE(NEW.program_type, OLD.program_type);
        target_program := COALESCE(NEW.program_id, OLD.program_id);
    ELSIF TG_TABLE_NAME = 'tb_program_guardian_member' THEN
        SELECT program_type, program_id INTO target_type, target_program FROM tb_program_session_application
            WHERE id = COALESCE(NEW.application_id, OLD.application_id);
    ELSIF TG_TABLE_NAME = 'tb_program_guardian_link' THEN
        SELECT program_type, program_id INTO target_type, target_program FROM tb_program_session_application
            WHERE id = COALESCE(NEW.student_application_id, OLD.student_application_id);
    ELSIF TG_TABLE_NAME = 'tb_standard_program_application' THEN
        target_type := 'STANDARD';
        target_program := COALESCE(NEW.standard_program_id, OLD.standard_program_id);
    ELSE
        target_type := 'TRAINING';
        target_program := COALESCE(NEW.training_program_id, OLD.training_program_id);
    END IF;
    IF EXISTS (
        SELECT 1 FROM tb_program_guardian_link l
        JOIN tb_program_session_application s ON s.id = l.student_application_id
        JOIN tb_program_session_application g ON g.id = l.guardian_application_id
        WHERE s.program_type = target_type AND s.program_id = target_program AND (
            s.expo_id <> g.expo_id OR s.program_type <> g.program_type OR s.program_id <> g.program_id
            OR (s.status <> 'CANCELLED' AND (g.status = 'CANCELLED' OR (s.status = 'CONFIRMED' AND g.status <> 'CONFIRMED')))
        )
    ) OR EXISTS (
        SELECT 1 FROM tb_program_guardian_member m
        JOIN tb_program_session_application a ON a.id = m.application_id
        WHERE a.program_type = target_type AND a.program_id = target_program AND a.status <> 'CANCELLED' AND (
            (m.role = 'STUDENT' AND NOT EXISTS (SELECT 1 FROM tb_program_guardian_link l WHERE l.student_application_id = a.id))
            OR (m.role = 'GUARDIAN' AND NOT EXISTS (
                SELECT 1 FROM tb_program_guardian_link l JOIN tb_program_session_application s ON s.id = l.student_application_id
                WHERE l.guardian_application_id = a.id AND s.status <> 'CANCELLED'
            ))
        )
    ) OR EXISTS (
        SELECT 1 FROM tb_program_guardian_member m
        JOIN tb_program_session_application a ON a.id = m.application_id
        WHERE a.program_type = target_type AND a.program_id = target_program AND a.status <> 'CANCELLED'
        GROUP BY m.batch_id HAVING count(DISTINCT a.status) > 1
    ) OR EXISTS (
        SELECT 1 FROM tb_program_guardian_member m
        JOIN tb_program_session_application a ON a.id = m.application_id
        WHERE a.program_type = target_type AND a.program_id = target_program AND a.status = 'CONFIRMED' AND (
            (a.program_type = 'STANDARD' AND NOT EXISTS (
                SELECT 1 FROM tb_standard_program_application p WHERE p.participant_id = a.participant_id AND p.standard_program_id = a.program_id
            )) OR (a.program_type = 'TRAINING' AND NOT EXISTS (
                SELECT 1 FROM tb_training_program_application p WHERE p.trainee_id = a.trainee_id AND p.training_program_id = a.program_id
            ))
        )
    ) THEN
        RAISE EXCEPTION 'student guardian application invariant violated' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER program_guardian_application_check AFTER INSERT OR UPDATE OR DELETE ON tb_program_session_application
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_program_guardian_invariants();
CREATE CONSTRAINT TRIGGER program_guardian_link_check AFTER INSERT OR UPDATE OR DELETE ON tb_program_guardian_link
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_program_guardian_invariants();
CREATE CONSTRAINT TRIGGER program_guardian_member_check AFTER INSERT OR UPDATE OR DELETE ON tb_program_guardian_member
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_program_guardian_invariants();
CREATE CONSTRAINT TRIGGER program_guardian_standard_check AFTER DELETE OR UPDATE ON tb_standard_program_application
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_program_guardian_invariants();
CREATE CONSTRAINT TRIGGER program_guardian_training_check AFTER DELETE OR UPDATE ON tb_training_program_application
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_program_guardian_invariants();
