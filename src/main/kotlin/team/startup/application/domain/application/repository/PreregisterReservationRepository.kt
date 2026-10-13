package team.startup.application.domain.application.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import team.startup.application.domain.application.presentation.dto.AdminPreregisterWaitlistItem
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
    // Non-null once the party entered the waitlist, including after promotion.
    val queueSeq: Long? = null,
    val withdrawn: Boolean = false,
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
                    if (requestId != null) "request_id = ?" else "command = ? AND state IN ('RESERVED', 'WAITING')",
                { rs, _ -> reservation(rs) },
                expoId,
                requestId ?: command,
            ).singleOrNull()

    private fun reservation(rs: java.sql.ResultSet) =
        PreregisterReservation(
            rs.getObject("expo_id", UUID::class.java),
            rs.getString("request_id"),
            rs.getLong("session_id"),
            rs.getString("command"),
            mapper.readValue(rs.getString("user_request"), ParticipantRegistration::class.java),
            rs.getInt("applicants"),
            rs.getString("state"),
            rs.getString("result")?.let { mapper.readValue(it, ParticipantRegistrationResult::class.java) },
            rs.getObject("error_status", Int::class.javaObjectType),
            rs.getInt("attempts"),
            rs.getObject("queue_seq", Long::class.javaObjectType),
            rs.getObject("withdrawn_at") != null,
        )

    fun insertWaiting(
        expoId: UUID,
        requestId: String,
        sessionId: Long,
        command: String,
        request: ParticipantRegistration,
        applicants: Int,
    ) {
        jdbc.update(
            "INSERT INTO tb_preregister_reservation " +
                "(expo_id, request_id, session_id, command, user_request, applicants, state, queue_seq) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'WAITING', nextval('seq_preregister_waitlist'))",
            expoId,
            requestId,
            sessionId,
            command,
            mapper.writeValueAsString(request),
            applicants,
        )
    }

    fun waitingApplicants(
        expoId: UUID,
        sessionId: Long,
    ): Long =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT coalesce(sum(applicants), 0) FROM tb_preregister_reservation WHERE expo_id = ? AND session_id = ? AND state = 'WAITING'",
                Long::class.java,
                expoId,
                sessionId,
            ),
        )

    fun waitingHead(
        expoId: UUID,
        sessionId: Long,
    ): PreregisterReservation? =
        jdbc
            .query(
                "SELECT * FROM tb_preregister_reservation WHERE expo_id = ? AND session_id = ? AND state = 'WAITING' " +
                    "ORDER BY queue_seq LIMIT 1",
                { rs, _ -> reservation(rs) },
                expoId,
                sessionId,
            ).singleOrNull()

    fun position(reservation: PreregisterReservation): Int =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT count(*) FROM tb_preregister_reservation WHERE expo_id = ? AND session_id = ? AND state = 'WAITING' AND queue_seq <= ?",
                Int::class.java,
                reservation.expoId,
                reservation.sessionId,
                reservation.queueSeq,
            ),
        )

    // Only open, not yet started sessions whose queue head fits the stored free seats can promote; the rest never need remote calls.
    // ponytail: sessions after the PRE form end still match until they start; they cost one Form call per sweep.
    fun promotableSessions(now: java.time.Instant): List<Pair<UUID, Long>> =
        jdbc.query(
            "SELECT r.expo_id, r.session_id FROM tb_preregister_reservation r JOIN tb_preregister_session_state st " +
                "ON st.expo_id = r.expo_id AND st.session_id = r.session_id " +
                "WHERE r.state = 'WAITING' AND NOT st.deleted AND NOT (st.definition::jsonb ->> 'closed')::boolean " +
                "AND (st.definition::jsonb ->> 'startedAt')::timestamptz > ? " +
                "GROUP BY r.expo_id, r.session_id, st.definition " +
                "HAVING (array_agg(r.applicants ORDER BY r.queue_seq))[1] <= (st.definition::jsonb ->> 'capacity')::bigint " +
                "- (SELECT count(*) FROM tb_preregister_application a WHERE a.expo_id = r.expo_id AND a.session_id = r.session_id " +
                "AND a.status = 'CONFIRMED') - (SELECT coalesce(sum(x.applicants), 0) FROM tb_preregister_reservation x " +
                "WHERE x.expo_id = r.expo_id AND x.session_id = r.session_id AND x.state = 'RESERVED') " +
                "ORDER BY min(r.queue_seq) LIMIT 100",
            { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getLong(2) },
            java.sql.Timestamp.from(now),
        )

    // The phone index from V8 keeps owner lookup cheap; samePhoneInUse allows at most one such reservation.
    fun waitlistedByPhone(
        expoId: UUID,
        phone: String,
    ): PreregisterReservation? =
        jdbc
            .query(
                "SELECT * FROM tb_preregister_reservation WHERE expo_id = ? " +
                    "AND regexp_replace(user_request::jsonb ->> 'phoneNumber', '[^0-9]', '', 'g') = ? " +
                    "AND queue_seq IS NOT NULL AND state IN ('WAITING', 'RESERVED') ORDER BY queue_seq DESC LIMIT 1",
                { rs, _ -> reservation(rs) },
                expoId,
                phone,
            ).singleOrNull()

    // Promoted parties keep queue_seq, so RESERVED rows with one are still in User delivery after leaving the queue.
    fun adminWaitlist(
        expoId: UUID,
        sessionId: Long,
    ): List<AdminPreregisterWaitlistItem> {
        var position = 0
        return jdbc.query(
            "SELECT request_id, applicants, state, queue_seq, user_request::jsonb ->> 'name', user_request::jsonb ->> 'phoneNumber' " +
                "FROM tb_preregister_reservation WHERE expo_id = ? AND session_id = ? AND queue_seq IS NOT NULL " +
                "AND state IN ('WAITING', 'RESERVED') ORDER BY queue_seq",
            { rs, _ ->
                val waiting = rs.getString(3) == "WAITING"
                AdminPreregisterWaitlistItem(
                    rs.getString(1),
                    sessionId,
                    if (waiting) ++position else null,
                    rs.getInt(2),
                    if (waiting) "WAITING" else "PROCESSING",
                    rs.getLong(4),
                    rs.getString(5),
                    rs.getString(6),
                )
            },
            expoId,
            sessionId,
        )
    }

    fun promote(reservation: PreregisterReservation): Boolean =
        jdbc.update(
            "UPDATE tb_preregister_reservation SET state = 'RESERVED', attempts = 0, next_attempt_at = CURRENT_TIMESTAMP " +
                "WHERE expo_id = ? AND request_id = ? AND state = 'WAITING'",
            reservation.expoId,
            reservation.requestId,
        ) == 1

    fun withdraw(reservation: PreregisterReservation): Boolean =
        jdbc.update(
            "UPDATE tb_preregister_reservation SET state = 'REJECTED', error_status = 409, withdrawn_at = CURRENT_TIMESTAMP " +
                "WHERE expo_id = ? AND request_id = ? AND state = 'WAITING'",
            reservation.expoId,
            reservation.requestId,
        ) == 1

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

    // A waiting party stops holding the phone once its session started and it can never be promoted.
    fun samePhoneInUse(
        expoId: UUID,
        phone: String,
        now: java.time.Instant,
    ): Boolean =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM tb_preregister_reservation r WHERE r.expo_id = ? " +
                    "AND regexp_replace(r.user_request::jsonb ->> 'phoneNumber', '[^0-9]', '', 'g') = ? " +
                    "AND (r.state = 'RESERVED' OR (r.state = 'WAITING' AND EXISTS(SELECT 1 FROM tb_preregister_session_state st " +
                    "WHERE st.expo_id = r.expo_id AND st.session_id = r.session_id " +
                    "AND (st.definition::jsonb ->> 'startedAt')::timestamptz > ?)) " +
                    "OR (r.state = 'COMPLETED' AND EXISTS(" +
                    "SELECT 1 FROM tb_preregister_application a WHERE a.expo_id = r.expo_id " +
                    "AND a.representative_id = (r.result::jsonb ->> 'participantId')::bigint AND a.status = 'CONFIRMED'))))",
                Boolean::class.java,
                expoId,
                phone,
                java.sql.Timestamp.from(now),
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
