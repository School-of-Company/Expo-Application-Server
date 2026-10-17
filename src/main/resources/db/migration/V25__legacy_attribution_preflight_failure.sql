ALTER TABLE tb_legacy_trainee_attribution_operation
    DROP CONSTRAINT tb_legacy_trainee_attribution_operation_state_check;

ALTER TABLE tb_legacy_trainee_attribution_operation
    ADD CONSTRAINT tb_legacy_trainee_attribution_operation_state_check
    CHECK (state IN ('PENDING', 'APPLIED', 'REJECTED', 'FAILED'));
