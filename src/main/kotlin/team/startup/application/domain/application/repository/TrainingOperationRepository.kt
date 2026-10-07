package team.startup.application.domain.application.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class TrainingOperationRepository(
    private val jdbc: JdbcTemplate,
) {
    fun lockOperation(operationId: UUID) {
        jdbc.queryForObject(
            "SELECT CAST(pg_advisory_xact_lock(28, hashtext(?)) AS text)",
            String::class.java,
            operationId.toString(),
        )
    }

    fun find(operationId: UUID): Pair<String, String>? =
        jdbc
            .query(
                "SELECT command, receipt FROM tb_training_operation_receipt WHERE operation_id = ?",
                { rs, _ -> rs.getString("command") to rs.getString("receipt") },
                operationId,
            ).singleOrNull()

    fun save(
        operationId: UUID,
        command: String,
        receipt: String,
    ) {
        jdbc.update(
            "INSERT INTO tb_training_operation_receipt (operation_id, command, receipt) VALUES (?, ?, ?)",
            operationId,
            command,
            receipt,
        )
    }

    // All callers acquire their program locks before version rows; delete locks version rows in trainee ID order.
    fun lockVersion(traineeId: Long): Long {
        jdbc.update("INSERT INTO tb_training_application_version (trainee_id) VALUES (?) ON CONFLICT DO NOTHING", traineeId)
        return requireNotNull(
            jdbc.queryForObject(
                "SELECT version FROM tb_training_application_version WHERE trainee_id = ? FOR UPDATE",
                Long::class.java,
                traineeId,
            ),
        )
    }

    fun incrementVersion(traineeId: Long): Long =
        requireNotNull(
            jdbc.queryForObject(
                "UPDATE tb_training_application_version SET version = version + 1 WHERE trainee_id = ? RETURNING version",
                Long::class.java,
                traineeId,
            ),
        )

    fun incrementVersionsForProgram(programId: Long) {
        jdbc.update(
            "INSERT INTO tb_training_application_version (trainee_id, version) " +
                "SELECT trainee_id, 1 FROM tb_training_program_application WHERE training_program_id = ? ORDER BY trainee_id " +
                "ON CONFLICT (trainee_id) DO UPDATE SET version = tb_training_application_version.version + 1",
            programId,
        )
    }

    fun version(traineeId: Long): Long =
        jdbc
            .query(
                "SELECT version FROM tb_training_application_version WHERE trainee_id = ?",
                { rs, _ -> rs.getLong("version") },
                traineeId,
            ).singleOrNull() ?: 0
}
