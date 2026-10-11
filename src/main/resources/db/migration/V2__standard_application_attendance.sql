ALTER TABLE tb_standard_program_application
    ADD COLUMN status BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN entry_time TIME,
    ADD COLUMN leave_time TIME,
    ADD COLUMN attendance_date DATE;
