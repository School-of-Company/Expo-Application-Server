BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '30s';
SET LOCAL lock_timeout = '5s';

SELECT 'standard' AS application_type,
       count(*) AS total_rows,
       count(*) FILTER (WHERE status) AS status_true_rows,
       count(*) FILTER (WHERE entry_time IS NOT NULL) AS entry_time_rows,
       count(*) FILTER (WHERE leave_time IS NOT NULL) AS leave_time_rows,
       count(*) FILTER (WHERE attendance_date IS NOT NULL) AS attendance_date_rows,
       count(*) FILTER (WHERE status OR entry_time IS NOT NULL OR leave_time IS NOT NULL OR attendance_date IS NOT NULL) AS recorded_rows,
       count(*) FILTER (WHERE (status OR entry_time IS NOT NULL OR leave_time IS NOT NULL OR attendance_date IS NOT NULL)
                           AND (entry_time IS NULL OR attendance_date IS NULL)) AS incomplete_rows,
       count(*) FILTER (WHERE NOT status AND (entry_time IS NOT NULL OR leave_time IS NOT NULL OR attendance_date IS NOT NULL)) AS status_false_with_data_rows
FROM tb_standard_program_application
UNION ALL
SELECT 'training',
       count(*),
       count(*) FILTER (WHERE status),
       count(*) FILTER (WHERE entry_time IS NOT NULL),
       count(*) FILTER (WHERE leave_time IS NOT NULL),
       count(*) FILTER (WHERE attendance_date IS NOT NULL),
       count(*) FILTER (WHERE status OR entry_time IS NOT NULL OR leave_time IS NOT NULL OR attendance_date IS NOT NULL),
       count(*) FILTER (WHERE (status OR entry_time IS NOT NULL OR leave_time IS NOT NULL OR attendance_date IS NOT NULL)
                           AND (entry_time IS NULL OR attendance_date IS NULL)),
       count(*) FILTER (WHERE NOT status AND (entry_time IS NOT NULL OR leave_time IS NOT NULL OR attendance_date IS NOT NULL))
FROM tb_training_program_application
ORDER BY application_type;

COMMIT;
