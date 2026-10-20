package team.startup.application.domain.application.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import team.startup.application.domain.application.presentation.dto.ProgramSessionDefinition
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class PromotionOffer(
    val id: UUID,
    val target: String,
    val expoId: UUID,
    val programType: ProgramSessionType?,
    val programId: Long?,
    val channel: String?,
    val applicationId: UUID?,
    val batchId: UUID?,
    val sessionId: Long?,
    val requestId: String?,
    val seats: Int,
    val state: String,
    val hardDeadline: Instant,
    val sendAfter: Instant,
    val eventId: UUID,
    val attemptId: UUID?,
    val providerAcceptedAt: Instant?,
    val expiresAt: Instant?,
    val respondedAt: Instant?,
    val voidReason: String?,
) {
    val active: Boolean get() = state in PromotionOfferRepository.ACTIVE_STATES

    // A response is possible only before the derived deadline: provider acceptance, or a failure report when none came (#226).
    fun respondable(now: Instant): Boolean = (state == "SENT" || state == "DELIVERY_FAILED") && expiresAt != null && now.isBefore(expiresAt)
}

@Repository
class PromotionOfferRepository(
    private val jdbc: JdbcTemplate,
) {
    companion object {
        val ACTIVE_STATES = listOf("SCHEDULED", "PUBLISHED", "SENT", "UNKNOWN", "DELIVERY_FAILED")
        const val ACTIVE = "('SCHEDULED', 'PUBLISHED', 'SENT', 'UNKNOWN', 'DELIVERY_FAILED')"

        // Seats held for program offers whose target is still waiting; a cancelled target releases its hold immediately.
        const val PROGRAM_HELD =
            "SELECT coalesce(sum(o.seats), 0) FROM tb_promotion_offer o " +
                "JOIN tb_program_session_application h ON h.id = o.application_id AND h.status = 'WAITING' " +
                "WHERE o.expo_id = ? AND o.program_type = ? AND o.program_id = ? AND o.state IN $ACTIVE " +
                "AND (CAST(? AS text) IS NULL OR o.channel = ?) AND (CAST(? AS uuid) IS NULL OR o.id <> ?)"

        // Same for preregistration parties; the caller binds expo_id and session_id.
        const val SESSION_HELD =
            "SELECT coalesce(sum(o.seats), 0) FROM tb_promotion_offer o " +
                "JOIN tb_preregister_reservation h ON h.expo_id = o.expo_id AND h.request_id = o.request_id AND h.state = 'WAITING' " +
                "WHERE o.expo_id = ? AND o.session_id = ? AND o.state IN $ACTIVE"
    }

    private val mapper =
        RowMapper { rs, _ ->
            PromotionOffer(
                rs.getObject("id", UUID::class.java),
                rs.getString("target"),
                rs.getObject("expo_id", UUID::class.java),
                rs.getString("program_type")?.let(ProgramSessionType::valueOf),
                rs.getObject("program_id", Long::class.javaObjectType),
                rs.getString("channel"),
                rs.getObject("application_id", UUID::class.java),
                rs.getObject("batch_id", UUID::class.java),
                rs.getObject("session_id", Long::class.javaObjectType),
                rs.getString("request_id"),
                rs.getInt("seats"),
                rs.getString("state"),
                rs.getTimestamp("hard_deadline").toInstant(),
                rs.getTimestamp("send_after").toInstant(),
                rs.getObject("event_id", UUID::class.java),
                rs.getObject("attempt_id", UUID::class.java),
                rs.getTimestamp("provider_accepted_at")?.toInstant(),
                rs.getTimestamp("expires_at")?.toInstant(),
                rs.getTimestamp("responded_at")?.toInstant(),
                rs.getString("void_reason"),
            )
        }

    fun programHeld(
        definition: ProgramSessionDefinition,
        channel: String? = null,
        excluding: UUID? = null,
    ): Long =
        requireNotNull(
            jdbc.queryForObject(
                PROGRAM_HELD,
                Long::class.java,
                UUID.fromString(definition.expoId),
                definition.programType.name,
                definition.id,
                channel,
                channel,
                excluding,
                excluding,
            ),
        )

    // An earlier cutoff observed later caps active offers (never extends them); one left without an interval is void.
    fun capProgram(
        definition: ProgramSessionDefinition,
        hardDeadline: Instant,
    ) = cap(
        "expo_id = ? AND program_type = ? AND program_id = ?",
        hardDeadline,
        UUID.fromString(definition.expoId),
        definition.programType.name,
        definition.id,
    )

    fun capSession(
        expoId: UUID,
        sessionId: Long,
        hardDeadline: Instant,
    ) = cap("expo_id = ? AND session_id = ?", hardDeadline, expoId, sessionId)

    private fun cap(
        target: String,
        hardDeadline: Instant,
        vararg keys: Any,
    ) {
        val deadline = Timestamp.from(hardDeadline)
        jdbc.update(
            "UPDATE tb_promotion_offer SET state = 'VOIDED', void_reason = 'CUTOFF_CHANGED' WHERE $target AND state IN $ACTIVE " +
                "AND (send_after >= ? OR provider_accepted_at >= ?)",
            *keys,
            deadline,
            deadline,
        )
        jdbc.update(
            // PostgreSQL LEAST ignores NULL, so an offer not yet accepted by the provider keeps no deadline.
            "UPDATE tb_promotion_offer SET hard_deadline = ?, " +
                "expires_at = CASE WHEN expires_at IS NULL THEN NULL ELSE LEAST(expires_at, ?) END " +
                "WHERE $target AND state IN $ACTIVE AND hard_deadline > ?",
            deadline,
            deadline,
            *keys,
            deadline,
        )
    }

    fun createProgram(
        definition: ProgramSessionDefinition,
        applicationId: UUID,
        batchId: UUID?,
        channel: String?,
        seats: Int,
        sendAfter: Instant,
        hardDeadline: Instant,
    ): UUID =
        UUID.randomUUID().also {
            jdbc.update(
                "INSERT INTO tb_promotion_offer (id, target, expo_id, program_type, program_id, channel, application_id, batch_id, " +
                    "seats, " +
                    "hard_deadline, send_after, event_id, next_attempt_at) VALUES (?, 'PROGRAM', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                it,
                UUID.fromString(definition.expoId),
                definition.programType.name,
                definition.id,
                channel,
                applicationId,
                batchId,
                seats,
                Timestamp.from(hardDeadline),
                Timestamp.from(sendAfter),
                UUID.randomUUID(),
                Timestamp.from(sendAfter),
            )
        }

    fun createPreregister(
        reservation: PreregisterReservation,
        sendAfter: Instant,
        hardDeadline: Instant,
    ): UUID =
        UUID.randomUUID().also {
            jdbc.update(
                "INSERT INTO tb_promotion_offer (id, target, expo_id, session_id, request_id, seats, hard_deadline, send_after, " +
                    "event_id, " +
                    "next_attempt_at) VALUES (?, 'PREREGISTER', ?, ?, ?, ?, ?, ?, ?, ?)",
                it,
                reservation.expoId,
                reservation.sessionId,
                reservation.requestId,
                reservation.applicants,
                Timestamp.from(hardDeadline),
                Timestamp.from(sendAfter),
                UUID.randomUUID(),
                Timestamp.from(sendAfter),
            )
        }

    fun find(id: UUID): PromotionOffer? = jdbc.query("SELECT * FROM tb_promotion_offer WHERE id = ?", mapper, id).singleOrNull()

    // Row lock orders responses, delivery reports and sweeps on one offer after the caller's domain locks.
    fun lock(id: UUID): PromotionOffer? = jdbc.query("SELECT * FROM tb_promotion_offer WHERE id = ? FOR UPDATE", mapper, id).singleOrNull()

    fun activeForApplication(applicationId: UUID): PromotionOffer? =
        jdbc.query("SELECT * FROM tb_promotion_offer WHERE application_id = ? AND state IN $ACTIVE", mapper, applicationId).singleOrNull()

    fun activeForReservation(
        expoId: UUID,
        requestId: String,
    ): PromotionOffer? =
        jdbc
            .query(
                "SELECT * FROM tb_promotion_offer WHERE expo_id = ? AND request_id = ? AND state IN $ACTIVE",
                mapper,
                expoId,
                requestId,
            ).singleOrNull()

    // Latest offer per program waiting row the representative's managed party owns; terminal rows stay visible.
    fun forParty(
        expoId: UUID,
        participantIds: List<Long>,
        representativeId: Long?,
    ): List<PromotionOffer> =
        if (participantIds.isEmpty()) {
            emptyList()
        } else {
            NamedParameterJdbcTemplate(jdbc).query(
                "SELECT DISTINCT ON (o.application_id) o.* FROM tb_promotion_offer o " +
                    "JOIN tb_program_session_application a ON a.id = o.application_id " +
                    "LEFT JOIN tb_program_guardian_batch b ON b.id = o.batch_id " +
                    "WHERE o.expo_id = :expoId AND o.target = 'PROGRAM' " +
                    "AND (a.participant_id IN (:ids) OR b.representative_id = :representativeId) " +
                    "ORDER BY o.application_id, o.created_at DESC, o.id",
                mapOf("expoId" to expoId, "ids" to participantIds, "representativeId" to representativeId),
                mapper,
            )
        }

    // Latest preregistration offer per waiting reservation of the party whose representative phone the session verified (#198).
    fun forPhone(
        expoId: UUID,
        phone: String,
    ): List<PromotionOffer> =
        jdbc.query(
            "SELECT DISTINCT ON (o.request_id) o.* FROM tb_promotion_offer o JOIN tb_preregister_reservation r " +
                "ON r.expo_id = o.expo_id AND r.request_id = o.request_id WHERE o.expo_id = ? AND o.target = 'PREREGISTER' " +
                "AND regexp_replace(r.user_request::jsonb ->> 'phoneNumber', '[^0-9]', '', 'g') = ? " +
                "ORDER BY o.request_id, o.created_at DESC, o.id",
            mapper,
            expoId,
            phone,
        )

    fun due(now: Instant): List<UUID> =
        jdbc.query(
            "SELECT id FROM tb_promotion_offer WHERE state = 'SCHEDULED' AND next_attempt_at <= ? ORDER BY next_attempt_at, id LIMIT 100",
            { rs, _ -> rs.getObject(1, UUID::class.java) },
            Timestamp.from(now),
        )

    // Expired responses, offers past their hard cutoff, and holds whose target already left the queue.
    fun sweepable(now: Instant): List<UUID> =
        jdbc.query(
            "SELECT o.id FROM tb_promotion_offer o " +
                "LEFT JOIN tb_program_session_application a ON a.id = o.application_id " +
                "LEFT JOIN tb_preregister_reservation r ON r.expo_id = o.expo_id AND r.request_id = o.request_id " +
                "WHERE o.state IN $ACTIVE AND ((o.state IN ('SENT', 'DELIVERY_FAILED') AND o.expires_at <= ?) OR o.hard_deadline <= ? " +
                "OR coalesce(a.status, r.state) IS DISTINCT FROM 'WAITING') ORDER BY o.created_at, o.id LIMIT 100",
            { rs, _ -> rs.getObject(1, UUID::class.java) },
            Timestamp.from(now),
            Timestamp.from(now),
        )

    fun published(
        id: UUID,
        eventId: UUID,
    ): Boolean =
        jdbc.update(
            "UPDATE tb_promotion_offer SET state = 'PUBLISHED', published_at = clock_timestamp() " +
                "WHERE id = ? AND event_id = ? AND state = 'SCHEDULED'",
            id,
            eventId,
        ) == 1

    // Session lock without a transaction: the Kafka send never holds a database transaction or domain lock. A crash after
    // the ACK republishes the same event ID, which Notification deduplicates.
    fun publish(
        id: UUID,
        send: (UUID) -> Unit,
    ) {
        jdbc.withDeliveryLock(48, id.toString()) { scoped ->
            val eventId =
                scoped
                    .query(
                        "SELECT event_id FROM tb_promotion_offer WHERE id = ? AND state = 'SCHEDULED' AND next_attempt_at <= clock_timestamp()",
                        { rs, _ -> rs.getObject(1, UUID::class.java) },
                        id,
                    ).singleOrNull() ?: return@withDeliveryLock
            try {
                send(eventId)
            } catch (exception: Exception) {
                PromotionOfferRepository(scoped).publishFailed(id)
                throw exception
            }
            PromotionOfferRepository(scoped).published(id, eventId)
        }
    }

    fun publishFailed(id: UUID) {
        jdbc.update(
            "UPDATE tb_promotion_offer SET attempts = LEAST(attempts + 1, 30), " +
                "next_attempt_at = clock_timestamp() + LEAST(300, power(2, LEAST(attempts, 8))) * interval '1 second' " +
                "WHERE id = ? AND state = 'SCHEDULED'",
            id,
        )
    }

    // Skipped before any provider request, so a fresh event for the next window can never duplicate a sent message.
    fun reschedule(
        id: UUID,
        sendAfter: Instant,
    ): Boolean =
        jdbc.update(
            "UPDATE tb_promotion_offer SET state = 'SCHEDULED', send_after = ?, next_attempt_at = ?, event_id = ?, attempts = 0, " +
                "published_at = NULL WHERE id = ? AND state IN ('SCHEDULED', 'PUBLISHED') AND ? < hard_deadline",
            Timestamp.from(sendAfter),
            Timestamp.from(sendAfter),
            UUID.randomUUID(),
            id,
            Timestamp.from(sendAfter),
        ) == 1

    fun accepted(
        id: UUID,
        attemptId: UUID,
        attemptedAt: Instant?,
        providerAcceptedAt: Instant,
        expiresAt: Instant,
    ): Boolean =
        jdbc.update(
            "UPDATE tb_promotion_offer SET state = 'SENT', attempt_id = ?, attempted_at = ?, provider_accepted_at = ?, expires_at = ?, " +
                "delivery_status = 'ACCEPTED', delivery_reason = NULL WHERE id = ? AND state IN ('SCHEDULED', 'PUBLISHED', 'UNKNOWN')",
            attemptId,
            attemptedAt?.let(Timestamp::from),
            Timestamp.from(providerAcceptedAt),
            Timestamp.from(expiresAt),
            id,
        ) == 1

    fun delivery(
        id: UUID,
        state: String,
        attemptId: UUID?,
        attemptedAt: Instant?,
        status: String,
        reason: String?,
        expiresAt: Instant? = null,
    ) {
        jdbc.update(
            "UPDATE tb_promotion_offer SET state = ?, attempt_id = coalesce(?, attempt_id), attempted_at = coalesce(?, attempted_at), " +
                "delivery_status = ?, delivery_reason = ?, expires_at = coalesce(expires_at, ?) WHERE id = ?",
            state,
            attemptId,
            attemptedAt?.let(Timestamp::from),
            status,
            reason,
            expiresAt?.let(Timestamp::from),
            id,
        )
    }

    fun close(
        id: UUID,
        state: String,
        responderType: String?,
        responderParticipantId: Long? = null,
        voidReason: String? = null,
    ): Boolean =
        jdbc.update(
            "UPDATE tb_promotion_offer SET state = ?, " +
                "responded_at = CASE WHEN CAST(? AS text) IS NULL THEN NULL ELSE clock_timestamp() END, " +
                "responder_type = ?, responder_participant_id = ?, void_reason = ? WHERE id = ? AND state IN $ACTIVE",
            state,
            responderType,
            responderType,
            responderParticipantId,
            voidReason,
            id,
        ) == 1
}
