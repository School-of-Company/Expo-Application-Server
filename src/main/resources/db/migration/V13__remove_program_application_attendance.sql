DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM tb_standard_program_application
        WHERE status OR entry_time IS NOT NULL OR leave_time IS NOT NULL OR attendance_date IS NOT NULL
    ) OR EXISTS (
        SELECT 1 FROM tb_training_program_application
        WHERE status OR entry_time IS NOT NULL OR leave_time IS NOT NULL OR attendance_date IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'Application attendance data must be migrated before dropping columns';
    END IF;
END $$;

ALTER TABLE tb_standard_program_application
    DROP COLUMN status,
    DROP COLUMN entry_time,
    DROP COLUMN leave_time,
    DROP COLUMN attendance_date;

ALTER TABLE tb_training_program_application
    DROP COLUMN status,
    DROP COLUMN entry_time,
    DROP COLUMN leave_time,
    DROP COLUMN attendance_date;
