package team.startup.application.domain.application.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

data class SessionAttendanceMessage(
    val id: Long,
    val expoId: UUID,
    val participantId: Long,
    val sessionId: Long?,
    val attempts: Int,
    // #198: DELETE of only [sessionId]. A null sessionId still releases every session of the participant.
    val cancelled: Boolean = false,
) {
    val delete: Boolean get() = sessionId == null || cancelled

    // Reserve the next revision for purge DELETE compensation of this immutable PUT.
    val revision: Long
        get() = Math.addExact(Math.multiplyExact(id, 2L), if (delete) 1L else 0L)
}

@Repository
class SessionAttendanceOutboxRepository(
    private val jdbc: JdbcTemplate,
) {
    fun deliverNext(action: (SessionAttendanceOutboxRepository, SessionAttendanceMessage) -> Boolean): Boolean {
        val candidates =
            jdbc.query(
                "SELECT o.expo_id, o.participant_id FROM tb_session_attendance_outbox o " +
                    "WHERE o.next_attempt_at <= CURRENT_TIMESTAMP AND NOT EXISTS(" +
                    "SELECT 1 FROM tb_session_attendance_outbox p WHERE p.expo_id = o.expo_id " +
                    "AND p.participant_id = o.participant_id AND p.id < o.id) ORDER BY o.id LIMIT 100",
                { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getLong(2) },
            )
        for ((expoId, participantId) in candidates) {
            val delivered =
                jdbc.withDeliveryLock(38, "$expoId:$participantId") { scoped ->
                    val repository = SessionAttendanceOutboxRepository(scoped)
                    val message = repository.nextFor(expoId, participantId) ?: return@withDeliveryLock null
                    action(repository, message)
                }
            if (delivered != null) return delivered
        }
        return false
    }

    private fun nextFor(
        expoId: UUID,
        participantId: Long,
    ): SessionAttendanceMessage? =
        jdbc
            .query(
                "SELECT * FROM tb_session_attendance_outbox WHERE expo_id = ? AND participant_id = ? " +
                    "AND next_attempt_at <= CURRENT_TIMESTAMP AND id = " +
                    "(SELECT MIN(id) FROM tb_session_attendance_outbox WHERE expo_id = ? AND participant_id = ?)",
                { rs, _ ->
                    SessionAttendanceMessage(
                        rs.getLong("id"),
                        expoId,
                        participantId,
                        rs.getObject("session_id", Long::class.javaObjectType),
                        rs.getInt("attempts"),
                        rs.getBoolean("cancelled"),
                    )
                },
                expoId,
                participantId,
                expoId,
                participantId,
            ).singleOrNull()

    fun append(
        expoId: UUID,
        participantId: Long,
        sessionId: Long?,
        cancelled: Boolean = false,
    ): Long {
        // Keep identity order equal to commit order for this participant, including concurrent reapplications.
        jdbc.queryForObject(
            "SELECT CAST(pg_advisory_xact_lock(36, hashtext(?)) AS text)",
            String::class.java,
            "$expoId:$participantId",
        )
        return requireNotNull(
            jdbc.queryForObject(
                "INSERT INTO tb_session_attendance_outbox (expo_id, participant_id, session_id, cancelled) VALUES (?, ?, ?, ?) RETURNING id",
                Long::class.java,
                expoId,
                participantId,
                sessionId,
                cancelled,
            ),
        )
    }

    fun complete(id: Long) {
        jdbc.update("DELETE FROM tb_session_attendance_outbox WHERE id = ?", id)
    }

    fun lockExpoForDelivery(expoId: UUID): Boolean =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM tb_preregister_deleted_expo WHERE expo_id = ?)",
                Boolean::class.java,
                expoId,
            ),
        )

    fun retry(
        message: SessionAttendanceMessage,
        error: String,
    ) {
        val delaySeconds = minOf(300L, 1L shl minOf(message.attempts, 9))
        jdbc.update(
            "UPDATE tb_session_attendance_outbox SET attempts = LEAST(attempts::bigint + 1, 2147483647)::integer, " +
                "next_attempt_at = clock_timestamp() + (? * INTERVAL '1 second'), last_error = ? WHERE id = ?",
            delaySeconds,
            error,
            message.id,
        )
    }
}
