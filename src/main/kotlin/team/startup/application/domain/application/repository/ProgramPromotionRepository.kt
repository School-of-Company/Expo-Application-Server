package team.startup.application.domain.application.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import team.startup.application.domain.application.presentation.dto.ProgramPromotionEvent
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class ProgramPromotionRepository(
    private val jdbc: JdbcTemplate,
) {
    data class Promotion(
        val event: ProgramPromotionEvent,
        val status: String,
        val createdAt: Instant,
    )

    private val select =
        "SELECT e.event_id, a.id, a.expo_id, a.participant_id, a.program_type, a.program_id, a.status, e.created_at " +
            "FROM tb_program_session_event e JOIN tb_program_session_application a ON a.id = e.application_id " +
            "WHERE e.status = 'CONFIRMED' AND e.is_promotion "

    fun find(applicationId: UUID): Promotion? =
        jdbc
            .query(select + "AND a.id = ?", { rs, _ ->
                Promotion(
                    ProgramPromotionEvent(
                        1,
                        rs.getObject(1, UUID::class.java),
                        rs.getObject(2, UUID::class.java),
                        rs.getObject(3, UUID::class.java),
                        rs.getLong(4),
                        ProgramSessionType.valueOf(rs.getString(5)),
                        rs.getLong(6),
                    ),
                    rs.getString(7),
                    rs.getTimestamp(8).toInstant(),
                )
            }, applicationId)
            .singleOrNull()

    fun due(cutoff: Instant): List<UUID> =
        jdbc.query(
            select +
                "AND e.created_at >= ? AND e.delivered_at IS NULL AND e.next_attempt_at <= clock_timestamp() ORDER BY e.next_attempt_at, e.event_id LIMIT 100",
            { rs, _ -> rs.getObject(1, UUID::class.java) },
            Timestamp.from(cutoff),
        )

    fun publish(
        eventId: UUID,
        cutoff: Instant,
        send: (ProgramPromotionEvent) -> Unit,
    ) {
        jdbc.withDeliveryLock(47, eventId.toString()) { scoped ->
            val event =
                scoped
                    .query(
                        select +
                            "AND e.event_id = ? AND e.created_at >= ? AND e.delivered_at IS NULL AND e.next_attempt_at <= clock_timestamp()",
                        { rs, _ ->
                            ProgramPromotionEvent(
                                1,
                                rs.getObject(1, UUID::class.java),
                                rs.getObject(2, UUID::class.java),
                                rs.getObject(3, UUID::class.java),
                                rs.getLong(4),
                                ProgramSessionType.valueOf(rs.getString(5)),
                                rs.getLong(6),
                            )
                        },
                        eventId,
                        Timestamp.from(cutoff),
                    ).singleOrNull()
                    ?: return@withDeliveryLock
            try {
                send(event)
                scoped.update("UPDATE tb_program_session_event SET delivered_at = clock_timestamp() WHERE event_id = ?", eventId)
            } catch (exception: Exception) {
                scoped.update(
                    "UPDATE tb_program_session_event SET attempts = LEAST(attempts + 1, 30), " +
                        "next_attempt_at = clock_timestamp() + LEAST(300, power(2, LEAST(attempts, 8))) * interval '1 second' WHERE event_id = ?",
                    eventId,
                )
                throw exception
            }
        }
    }
}
