ALTER TABLE tb_program_session_application ALTER COLUMN expo_id TYPE UUID USING expo_id::uuid;
ALTER TABLE tb_program_session_receipt ALTER COLUMN expo_id TYPE UUID USING expo_id::uuid;
UPDATE tb_program_session_receipt
SET command = jsonb_set(command, '{expoId}', to_jsonb((command->>'expoId')::uuid::text));
