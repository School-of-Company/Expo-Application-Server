package team.startup.application.domain.application.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import team.startup.application.domain.application.service.impl.ParticipantRegistration
import team.startup.application.domain.application.service.impl.ParticipantRegistrationResult
import tools.jackson.databind.ObjectMapper
import java.util.UUID

data class PreregisterReservation(
    val expoId: UUID,
    val requestId: String,
    val sessionId: Long,
    val command: String,
    val userRequest: ParticipantRegistration,
    val applicants: Int,
    val state: String,
    val result: ParticipantRegistrationResult?,
    val errorStatus: Int?,
    val attempts: Int,
)

@Repository
class PreregisterReservationRepository(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
) {
    fun deliverCounts(send: (UUID, Long) -> Unit) {
        val candidates =
            jdbc.query(
                "SELECT expo_id, participant_id FROM tb_preregister_count_outbox WHERE next_attempt_at <= CURRENT_TIMESTAMP " +
                    "ORDER BY next_attempt_at LIMIT 100",
                { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getLong(2) },
            )
        for ((expoId, participantId) in candidates) {
            jdbc.withDeliveryLock(39, "$expoId:$participantId") { scoped ->
                val due =
                    scoped.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM tb_preregister_count_outbox WHERE expo_id = ? AND participant_id = ? " +
                            "AND next_attempt_at <= CURRENT_TIMESTAMP)",
                        Boolean::class.java,
                        expoId,
                        participantId,
                    ) == true
                if (!due) return@withDeliveryLock
                val repository = PreregisterReservationRepository(scoped, mapper)
                val deleted =
                    scoped.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM tb_preregister_deleted_expo WHERE expo_id = ?)",
                        Boolean::class.java,
                        expoId,
                    ) == true
                if (deleted) {
                    repository.countDelivered(expoId, participantId)
                    return@withDeliveryLock
                }
                try {
                    send(expoId, participantId)
                } catch (ex: org.springframework.web.server.ResponseStatusException) {
                    repository.countRetry(expoId, participantId)
                    return@withDeliveryLock
                }
                repository.countDelivered(expoId, participantId)
            }
        }
    }

    fun find(
        expoId: UUID,
        requestId: String?,
        command: String,
    ): PreregisterReservation? =
        jdbc
            .query(
                "SELECT * FROM tb_preregister_reservation WHERE expo_id = ? AND " +
                    if (requestId != null) "request_id = ?" else "command = ? AND state = 'RESERVED'",
                { rs, _ ->
                    PreregisterReservation(
                        expoId,
                        rs.getString("request_id"),
                        rs.getLong("session_id"),
                        rs.getString("command"),
                        mapper.readValue(rs.getString("user_request"), ParticipantRegistration::class.java),
                        rs.getInt("applicants"),
                        rs.getString("state"),
                        rs.getString("result")?.let { mapper.readValue(it, ParticipantRegistrationResult::class.java) },
                        rs.getObject("error_status", Int::class.javaObjectType),
                        rs.getInt("attempts"),
                    )
                },
                expoId,
                requestId ?: command,
            ).singleOrNull()

    fun insert(
        expoId: UUID,
        requestId: String,
        sessionId: Long,
        command: String,
        request: ParticipantRegistration,
        applicants: Int,
    ) {
        jdbc.update(
            "INSERT INTO tb_preregister_reservation (expo_id, request_id, session_id, command, user_request, applicants) VALUES (?, ?, ?, ?, ?, ?)",
            expoId,
            requestId,
            sessionId,
            command,
            mapper.writeValueAsString(request),
            applicants,
        )
    }

    fun samePhoneInUse(
        expoId: UUID,
        phone: String,
    ): Boolean =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM tb_preregister_reservation r WHERE r.expo_id = ? " +
                    "AND regexp_replace(r.user_request::jsonb ->> 'phoneNumber', '[^0-9]', '', 'g') = ? " +
                    "AND (r.state = 'RESERVED' OR (r.state = 'COMPLETED' AND EXISTS(" +
                    "SELECT 1 FROM tb_preregister_application a WHERE a.expo_id = r.expo_id " +
                    "AND a.representative_id = (r.result::jsonb ->> 'participantId')::bigint AND a.status = 'CONFIRMED'))))",
                Boolean::class.java,
                expoId,
                phone,
            ),
        )

    fun result(
        reservation: PreregisterReservation,
        result: ParticipantRegistrationResult,
    ) {
        jdbc.update(
            "UPDATE tb_preregister_reservation SET result = ? WHERE expo_id = ? AND request_id = ?",
            mapper.writeValueAsString(result),
            reservation.expoId,
            reservation.requestId,
        )
    }

    fun finish(
        reservation: PreregisterReservation,
        status: Int? = null,
    ) {
        jdbc.update(
            "UPDATE tb_preregister_reservation SET state = ?, error_status = ? WHERE expo_id = ? AND request_id = ?",
            if (status == null) "COMPLETED" else "REJECTED",
            status,
            reservation.expoId,
            reservation.requestId,
        )
    }

    fun retry(reservation: PreregisterReservation) {
        jdbc.update(
            "UPDATE tb_preregister_reservation SET next_attempt_at = clock_timestamp() + " +
                "(LEAST(300, power(2, LEAST(attempts, 8))) * INTERVAL '1 second'), " +
                "attempts = LEAST(attempts::bigint + 1, 2147483647)::integer WHERE expo_id = ? AND request_id = ?",
            reservation.expoId,
            reservation.requestId,
        )
    }

    fun needsReview(
        reservation: PreregisterReservation,
        status: Int,
    ) {
        jdbc.update(
            "UPDATE tb_preregister_reservation SET error_status = ?, next_attempt_at = 'infinity' " +
                "WHERE expo_id = ? AND request_id = ?",
            status,
            reservation.expoId,
            reservation.requestId,
        )
    }

    fun retryReviewed(reservation: PreregisterReservation) {
        jdbc.update(
            "UPDATE tb_preregister_reservation SET error_status = NULL, result = NULL, next_attempt_at = CURRENT_TIMESTAMP " +
                "WHERE expo_id = ? AND request_id = ? AND state = 'RESERVED'",
            reservation.expoId,
            reservation.requestId,
        )
    }

    fun due(): List<Pair<UUID, String>> =
        jdbc.query(
            "SELECT expo_id, request_id FROM tb_preregister_reservation WHERE state = 'RESERVED' " +
                "AND next_attempt_at <= CURRENT_TIMESTAMP ORDER BY next_attempt_at LIMIT 100",
            { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) },
        )

    fun enqueueCounts(
        expoId: UUID,
        participantIds: List<Long>,
    ) {
        participantIds.forEach {
            jdbc.update(
                "INSERT INTO tb_preregister_count_outbox (expo_id, participant_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                expoId,
                it,
            )
        }
    }

    fun countDelivered(
        expoId: UUID,
        participantId: Long,
    ) {
        jdbc.update("DELETE FROM tb_preregister_count_outbox WHERE expo_id = ? AND participant_id = ?", expoId, participantId)
    }

    fun countRetry(
        expoId: UUID,
        participantId: Long,
    ) {
        jdbc.update(
            "UPDATE tb_preregister_count_outbox SET next_attempt_at = clock_timestamp() + INTERVAL '30 seconds', " +
                "attempts = LEAST(attempts::bigint + 1, 2147483647)::integer WHERE expo_id = ? AND participant_id = ?",
            expoId,
            participantId,
        )
    }
}
