CREATE TABLE tb_training_application_version (
    trainee_id BIGINT PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0)
);

INSERT INTO tb_training_application_version (trainee_id)
SELECT DISTINCT trainee_id FROM tb_training_program_application;

CREATE TABLE tb_training_operation_receipt (
    operation_id UUID PRIMARY KEY,
    command TEXT NOT NULL,
    receipt TEXT NOT NULL
);
