package team.startup.application.domain.application.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import team.startup.application.domain.application.exception.ProgramApplicationConflictException
import team.startup.application.domain.application.presentation.dto.ChangeLegacyTraineeAttributionCommand
import team.startup.application.domain.application.presentation.dto.LegacyTraineeAttributionResult
import team.startup.application.domain.application.presentation.dto.ProgramSessionDefinition
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import javax.sql.DataSource

@Repository
class LegacyTraineeAttributionRepository(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    private val dataSource: DataSource,
) {
    data class Operation(
        val id: UUID,
        val expoId: UUID,
        val traineeId: Long,
        val participantId: Long,
        val actorId: Long,
        val reason: String,
        val revoke: Boolean,
        val state: String,
        val attempts: Int,
        val errorStatus: Int?,
        val result: LegacyTraineeAttributionResult?,
    )

    fun lock(traineeId: Long): AutoCloseable {
        val connection = dataSource.connection
        val key = "legacy-trainee-attribution/$traineeId"
        try {
            connection.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?, 0))").use { statement ->
                statement.setString(1, key)
                statement.executeQuery().use { result ->
                    result.next()
                    if (!result.getBoolean(1)) conflict()
                }
            }
        } catch (exception: Exception) {
            connection.close()
            throw exception
        }
        return AutoCloseable {
            try {
                connection.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?, 0))").use { statement ->
                    statement.setString(1, key)
                    statement.execute()
                }
            } finally {
                connection.close()
            }
        }
    }

    fun operation(id: UUID): Operation? =
        jdbc
            .query(
                "SELECT * FROM tb_legacy_trainee_attribution_operation WHERE id = ?",
                { rs, _ ->
                    Operation(
                        rs.getObject("id", UUID::class.java),
                        rs.getObject("expo_id", UUID::class.java),
                        rs.getLong("trainee_id"),
                        rs.getLong("participant_id"),
                        rs.getLong("actor_admin_id"),
                        rs.getString("reason"),
                        rs.getBoolean("revoke"),
                        rs.getString("state"),
                        rs.getInt("attempts"),
                        rs.getObject("error_status") as Int?,
                        rs.getString("result")?.let { mapper.readValue(it, LegacyTraineeAttributionResult::class.java) },
                    )
                },
                id,
            ).singleOrNull()

    fun prepare(
        command: ChangeLegacyTraineeAttributionCommand,
        actorId: Long,
        revoke: Boolean,
        policyReference: String,
    ): Operation {
        operation(command.operationId)?.let {
            if (it.expoId != command.expoId || it.traineeId != command.request.traineeId ||
                it.participantId != command.request.participantId || it.actorId != actorId ||
                it.reason != command.request.reason || it.revoke != revoke
            ) {
                conflict()
            }
            return it
        }
        requireReady(command.expoId, command.request.participantId, command.request.traineeId)
        val linked = participant(command.expoId, command.request.traineeId)
        if (linked != null && linked != command.request.participantId) conflict()
        jdbc.update(
            "INSERT INTO tb_legacy_trainee_attribution_operation " +
                "(id, expo_id, trainee_id, participant_id, actor_admin_id, reason, revoke, policy_reference) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            command.operationId,
            command.expoId,
            command.request.traineeId,
            command.request.participantId,
            actorId,
            command.request.reason,
            revoke,
            policyReference,
        )
        return requireNotNull(operation(command.operationId))
    }

    fun participant(
        expoId: UUID,
        traineeId: Long,
    ): Long? =
        jdbc
            .query(
                "SELECT participant_id FROM tb_legacy_trainee_attribution WHERE expo_id = ? AND trainee_id = ?",
                { rs, _ -> rs.getLong(1) },
                expoId,
                traineeId,
            ).singleOrNull()

    fun hasParticipant(
        expoId: UUID,
        participantId: Long,
    ): Boolean =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM tb_legacy_trainee_attribution WHERE expo_id = ? AND participant_id = ?)",
                Boolean::class.java,
                expoId,
                participantId,
            ),
        )

    fun requireReady(
        expoId: UUID,
        participantId: Long? = null,
        traineeId: Long? = null,
    ) {
        if (pending(expoId, participantId, traineeId)) conflict()
    }

    fun pending(
        expoId: UUID,
        participantId: Long? = null,
        traineeId: Long? = null,
    ): Boolean =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM tb_legacy_trainee_attribution_operation WHERE state = 'PENDING' " +
                    "AND ((expo_id = ? AND (CAST(? AS bigint) IS NULL OR participant_id = ?)) OR trainee_id = ?))",
                Boolean::class.java,
                expoId,
                participantId,
                participantId,
                traineeId,
            ),
        )

    fun attempt(id: UUID) {
        jdbc.update("UPDATE tb_legacy_trainee_attribution_operation SET attempts = attempts + 1 WHERE id = ? AND state = 'PENDING'", id)
    }

    fun requireRevocable(operation: Operation) {
        if (jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM tb_program_session_application WHERE expo_id = ? " +
                    "AND participant_id = ? AND trainee_id = ? AND status IN ('CONFIRMED', 'WAITING'))",
                Boolean::class.java,
                operation.expoId,
                operation.participantId,
                operation.traineeId,
            ) == true
        ) {
            throw ProgramApplicationConflictException("연결된 연수 신청을 먼저 취소한 뒤 연결을 해제해 주세요.")
        }
    }

    fun fail(
        operation: Operation,
        status: Int,
        beforeUser: Boolean = false,
    ) {
        val state =
            when {
                operation.attempts > 0 -> "PENDING"
                beforeUser -> "FAILED"
                status in setOf(400, 403, 404, 409) -> "REJECTED"
                else -> "PENDING"
            }
        jdbc.update(
            "UPDATE tb_legacy_trainee_attribution_operation SET error_status = ?, state = ?, " +
                "completed_at = CASE WHEN ? THEN clock_timestamp() ELSE NULL END WHERE id = ? AND state = 'PENDING'",
            status,
            state,
            state != "PENDING",
            operation.id,
        )
    }

    fun complete(
        operation: Operation,
        result: LegacyTraineeAttributionResult,
        definitions: List<ProgramSessionDefinition>,
    ) {
        if (operation.revoke) {
            jdbc.update(
                "DELETE FROM tb_legacy_trainee_attribution WHERE expo_id = ? AND trainee_id = ? AND participant_id = ?",
                operation.expoId,
                operation.traineeId,
                operation.participantId,
            )
        } else {
            jdbc.update(
                "INSERT INTO tb_legacy_trainee_attribution (expo_id, trainee_id, participant_id, operation_id) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT (trainee_id) DO UPDATE SET operation_id = EXCLUDED.operation_id " +
                    "WHERE tb_legacy_trainee_attribution.expo_id = EXCLUDED.expo_id AND tb_legacy_trainee_attribution.participant_id = EXCLUDED.participant_id",
                operation.expoId,
                operation.traineeId,
                operation.participantId,
                operation.id,
            )
            if (participant(operation.expoId, operation.traineeId) != operation.participantId) conflict()
            remember(operation.traineeId, definitions)
        }
        jdbc.update(
            "UPDATE tb_legacy_trainee_attribution_operation SET state = 'APPLIED', result = CAST(? AS jsonb), " +
                "error_status = NULL, completed_at = clock_timestamp() WHERE id = ? AND state = 'PENDING'",
            mapper.writeValueAsString(result),
            operation.id,
        )
    }

    fun remember(
        traineeId: Long,
        definitions: List<ProgramSessionDefinition>,
    ) {
        definitions.forEach {
            jdbc.update(
                "INSERT INTO tb_legacy_trainee_program (trainee_id, program_id, definition) VALUES (?, ?, CAST(? AS jsonb)) " +
                    "ON CONFLICT (trainee_id, program_id) DO UPDATE SET definition = EXCLUDED.definition",
                traineeId,
                it.id,
                mapper.writeValueAsString(it),
            )
        }
    }

    private fun conflict(): Nothing = throw ProgramApplicationConflictException("귀속 변경 또는 복구가 진행 중이거나 다른 연결과 충돌합니다.")
}
