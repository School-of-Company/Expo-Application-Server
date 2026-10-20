package team.startup.application.domain.application.repository

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.CancelOwnerApplicationCommand
import team.startup.application.domain.application.presentation.dto.OwnerApplicationType
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

@Repository
class OwnerCancellationDeliveryRepository(
    private val jdbc: JdbcTemplate,
    private val dataSource: DataSource,
) {
    data class Delivery(
        val command: CancelOwnerApplicationCommand,
        val participantId: Long,
        val operationId: UUID,
        val action: String,
        val cancellationRevision: Long? = null,
        val sessionId: Long? = null,
    )

    fun lock(command: CancelOwnerApplicationCommand): AutoCloseable {
        val connection = dataSource.connection
        val key = "owner-cancellation/${command.expoId}/${command.type}/${command.applicationId}"
        try {
            connection.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?, 0))").use { statement ->
                statement.setString(1, key)
                statement.executeQuery().use { result ->
                    result.next()
                    if (!result.getBoolean(1)) throw ResponseStatusException(HttpStatus.CONFLICT, "취소 작업이 진행 중입니다.")
                }
            }
        } catch (exception: Exception) {
            connection.close()
            throw exception
        }
        return AutoCloseable { unlock(connection, key) }
    }

    private fun unlock(
        connection: Connection,
        key: String,
    ) {
        try {
            connection.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?, 0))").use { statement ->
                statement.setString(1, key)
                statement.execute()
            }
        } finally {
            connection.close()
        }
    }

    fun pending(): List<Delivery> =
        jdbc.query(
            "SELECT * FROM tb_owner_cancellation_delivery WHERE next_attempt_at <= now() ORDER BY next_attempt_at, operation_id LIMIT 100",
            { rs, _ -> delivery(rs) },
        )

    fun find(command: CancelOwnerApplicationCommand): Delivery? =
        jdbc
            .query(
                "SELECT * FROM tb_owner_cancellation_delivery WHERE expo_id = ? AND application_type = ? AND application_id = ?",
                { rs, _ -> delivery(rs) },
                command.expoId,
                command.type.name,
                command.applicationId,
            ).singleOrNull()

    fun insert(
        command: CancelOwnerApplicationCommand,
        participantId: Long,
        operationId: UUID,
    ) {
        jdbc.update(
            "INSERT INTO tb_owner_cancellation_delivery (expo_id, application_type, application_id, participant_id, operation_id, action) VALUES (?, ?, ?, ?, ?, 'ACQUIRING')",
            command.expoId,
            command.type.name,
            command.applicationId,
            participantId,
            operationId,
        )
    }

    fun commit(
        operationId: UUID,
        cancellationRevision: Long,
        sessionId: Long? = null,
    ) {
        require(cancellationRevision > 0)
        check(
            jdbc.update(
                "UPDATE tb_owner_cancellation_delivery SET action = 'COMMIT', cancellation_revision = ?, session_id = ? " +
                    "WHERE operation_id = ? AND action = 'ACQUIRING'",
                cancellationRevision,
                sessionId,
                operationId,
            ) ==
                1,
        )
    }

    fun delete(operationId: UUID) {
        jdbc.update("DELETE FROM tb_owner_cancellation_delivery WHERE operation_id = ?", operationId)
    }

    fun abort(operationId: UUID) {
        jdbc.update(
            "UPDATE tb_owner_cancellation_delivery SET action = 'ABORT' WHERE operation_id = ? AND action = 'ACQUIRING'",
            operationId,
        )
    }

    fun defer(operationId: UUID) {
        jdbc.update(
            "UPDATE tb_owner_cancellation_delivery SET next_attempt_at = now() + interval '1 minute' WHERE operation_id = ?",
            operationId,
        )
    }

    fun operation(operationId: UUID): Delivery? =
        jdbc
            .query(
                "SELECT * FROM tb_owner_cancellation_delivery WHERE operation_id = ?",
                { rs, _ -> delivery(rs) },
                operationId,
            ).singleOrNull()

    private fun delivery(rs: ResultSet) =
        Delivery(
            CancelOwnerApplicationCommand(
                rs.getObject("expo_id", UUID::class.java),
                "",
                OwnerApplicationType.valueOf(rs.getString("application_type")),
                rs.getString("application_id"),
            ),
            rs.getLong("participant_id"),
            rs.getObject("operation_id", UUID::class.java),
            rs.getString("action"),
            rs.getObject("cancellation_revision", Long::class.javaObjectType),
            rs.getObject("session_id", Long::class.javaObjectType),
        )
}
