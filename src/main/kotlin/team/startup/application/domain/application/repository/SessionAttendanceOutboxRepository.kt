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
)

@Repository
class SessionAttendanceOutboxRepository(
    private val jdbc: JdbcTemplate,
) {
    fun append(
        expoId: UUID,
        participantId: Long,
        sessionId: Long?,
    ) {
        // Keep identity order equal to commit order for this participant, including concurrent reapplications.
        jdbc.queryForObject(
            "SELECT CAST(pg_advisory_xact_lock(36, hashtext(?)) AS text)",
            String::class.java,
            "$expoId:$participantId",
        )
        jdbc.update(
            "INSERT INTO tb_session_attendance_outbox (expo_id, participant_id, session_id) VALUES (?, ?, ?)",
            expoId,
            participantId,
            sessionId,
        )
    }

    fun lockNext(): SessionAttendanceMessage? =
        jdbc
            .query(
                """
                SELECT o.* FROM tb_session_attendance_outbox o
                WHERE o.next_attempt_at <= CURRENT_TIMESTAMP
                  AND NOT EXISTS (
                    SELECT 1 FROM tb_session_attendance_outbox previous
                    WHERE previous.expo_id = o.expo_id AND previous.participant_id = o.participant_id
                      AND previous.id < o.id
                  )
                ORDER BY o.id
                LIMIT 1 FOR UPDATE OF o SKIP LOCKED
                """.trimIndent(),
                { rs, _ ->
                    SessionAttendanceMessage(
                        rs.getLong("id"),
                        rs.getObject("expo_id", UUID::class.java),
                        rs.getLong("participant_id"),
                        rs.getObject("session_id", Long::class.javaObjectType),
                        rs.getInt("attempts"),
                    )
                },
            ).singleOrNull()

    fun complete(id: Long) {
        jdbc.update("DELETE FROM tb_session_attendance_outbox WHERE id = ?", id)
    }

    fun lockExpoForDelivery(expoId: UUID): Boolean {
        jdbc.queryForObject(
            "SELECT CAST(pg_advisory_xact_lock_shared(34, hashtext(?)) AS text)",
            String::class.java,
            expoId.toString(),
        )
        return requireNotNull(
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM tb_preregister_deleted_expo WHERE expo_id = ?)",
                Boolean::class.java,
                expoId,
            ),
        )
    }

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
