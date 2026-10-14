-- Expo-scoped short receipt numbers. Existing rows keep their acceptance order; new numbers are gapless because
-- the counter row is updated in the same transaction as the application insert.
ALTER TABLE tb_program_session_application ADD COLUMN receipt_number BIGINT;

UPDATE tb_program_session_application a
SET receipt_number = numbered.receipt_number
FROM (
    SELECT id, row_number() OVER (PARTITION BY expo_id ORDER BY created_at, id) AS receipt_number
    FROM tb_program_session_application
) numbered
WHERE a.id = numbered.id;

ALTER TABLE tb_program_session_application ALTER COLUMN receipt_number SET NOT NULL;
ALTER TABLE tb_program_session_application ADD CONSTRAINT ck_program_session_receipt_number CHECK (receipt_number > 0);
CREATE UNIQUE INDEX uk_program_session_receipt_number ON tb_program_session_application (expo_id, receipt_number);

CREATE TABLE tb_program_session_receipt_sequence (
    expo_id UUID PRIMARY KEY,
    last_number BIGINT NOT NULL CHECK (last_number > 0)
);

INSERT INTO tb_program_session_receipt_sequence (expo_id, last_number)
SELECT expo_id, max(receipt_number) FROM tb_program_session_application GROUP BY expo_id;

-- The counter row lock is held until commit, so numbers follow commit order and rollbacks leave no gaps.
CREATE FUNCTION assign_program_session_receipt_number() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO tb_program_session_receipt_sequence AS s (expo_id, last_number) VALUES (NEW.expo_id, 1)
    ON CONFLICT (expo_id) DO UPDATE SET last_number = s.last_number + 1
    RETURNING last_number INTO NEW.receipt_number;
    RETURN NEW;
END;
$$;

CREATE TRIGGER assign_program_session_receipt_number BEFORE INSERT ON tb_program_session_application
    FOR EACH ROW EXECUTE FUNCTION assign_program_session_receipt_number();

-- Unexpired report snapshots store application rows as JSON; add the number so they keep deserializing.
UPDATE tb_report_application_snapshot s
SET items = (
    SELECT jsonb_agg(
        CASE WHEN a.receipt_number IS NULL THEN t.item
        ELSE jsonb_set(t.item, '{application,receiptNumber}', to_jsonb(a.receipt_number)) END
        ORDER BY t.position
    )
    FROM jsonb_array_elements(s.items) WITH ORDINALITY AS t(item, position)
    LEFT JOIN tb_program_session_application a ON a.id = (t.item #>> '{application,applicationId}')::uuid
)
WHERE jsonb_array_length(s.items) > 0;
