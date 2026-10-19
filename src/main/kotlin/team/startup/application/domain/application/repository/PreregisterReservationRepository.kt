package team.startup.application.domain.application.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import team.startup.application.domain.application.presentation.dto.ApplicationActorType
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
    // #169: 확정된 대표자 일행에 새 동행자만 더하는 예약이면 그 대표자 참가자 ID.
    val companionOf: Long? = null,
    // (teacher, other) seats reserved from the snapshot (#173); null for reservations stored before V31.
    val categories: Pair<Int, Int>? = null,
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
            rs.getObject("companion_representative_id", Long::class.javaObjectType),
            rs.getObject("teacher_applicants", Int::class.javaObjectType)?.let { it to rs.getInt("other_applicants") },
        )

    fun insertCompanions(
        expoId: UUID,
        requestId: String,
        sessionId: Long,
        command: String,
        request: ParticipantRegistration,
        applicants: Int,
        representativeId: Long,
        waiting: Boolean = false,
        // (teacher, other) seats of the added companions (#173).
        categories: Pair<Int, Int>? = null,
    ) {
        // 대기 추가는 일반 대기 일행과 같은 FIFO 순번을 받아 같은 승급·참여 제안 경로를 탄다.
        jdbc.update(
            "INSERT INTO tb_preregister_reservation " +
                "(expo_id, request_id, session_id, command, user_request, applicants, companion_representative_id, " +
                "teacher_applicants, other_applicants, state, queue_seq) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + (if (waiting) "nextval('seq_preregister_waitlist'))" else "NULL)"),
            expoId,
            requestId,
            sessionId,
            command,
            mapper.writeValueAsString(request),
            applicants,
            representativeId,
            categories?.first,
            categories?.second,
            if (waiting) "WAITING" else "RESERVED",
        )
    }

    fun companionAdditionInProgress(
        expoId: UUID,
        representativeId: Long,
    ): Boolean =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM tb_preregister_reservation WHERE expo_id = ? AND companion_representative_id = ? " +
                    "AND state IN ('RESERVED', 'WAITING'))",
                Boolean::class.java,
                expoId,
                representativeId,
            ),
        )

    /** 확정 신청을 만든 일행 예약의 원본 User 요청. 동행자 추가는 같은 대표자 정보로 User에 다시 저장한다. */
    fun registrationOf(
        expoId: UUID,
        applicationId: Long,
    ): ParticipantRegistration? =
        jdbc
            .query(
                "SELECT r.user_request FROM tb_preregister_application a JOIN tb_preregister_reservation r " +
                    "ON r.expo_id = a.expo_id AND r.request_id = a.reservation_request_id WHERE a.expo_id = ? AND a.id = ?",
                { rs, _ -> mapper.readValue(rs.getString(1), ParticipantRegistration::class.java) },
                expoId,
                applicationId,
            ).singleOrNull()

    /**
     * 대표자의 동행자 중 원장에서 신청이 취소된 참가자 ID(#190). 대표자 본인과 다시 활성 신청이 생긴 사람,
     * 대기 중인 사람(취소가 아님)은 뺀다. 같은 박람회 잠금 안에서 부른다.
     */
    fun cancelledCompanions(
        expoId: UUID,
        representativeId: Long,
    ): List<Long> =
        jdbc.query(
            "SELECT DISTINCT a.participant_id FROM tb_preregister_application a WHERE a.expo_id = ? AND a.representative_id = ? " +
                "AND a.participant_id <> a.representative_id AND a.status = 'CANCELLED' " +
                "AND NOT EXISTS(SELECT 1 FROM tb_preregister_application b WHERE b.expo_id = a.expo_id " +
                "AND b.participant_id = a.participant_id AND b.status <> 'CANCELLED') ORDER BY a.participant_id",
            { rs, _ -> rs.getLong(1) },
            expoId,
            representativeId,
        )

    /** 같은 전화번호(숫자만)로 이미 저장된 대표자 일행의 취소된 동행자. 대표자 재신청에서 쓴다. */
    fun cancelledCompanionsOfPhone(
        expoId: UUID,
        phone: String,
    ): List<Long> =
        jdbc.query(
            "SELECT DISTINCT a.participant_id FROM tb_preregister_application a WHERE a.expo_id = ? AND a.status = 'CANCELLED' " +
                "AND a.participant_id <> a.representative_id AND a.representative_id IN (" +
                "SELECT (r.result::jsonb ->> 'participantId')::bigint FROM tb_preregister_reservation r WHERE r.expo_id = a.expo_id " +
                "AND r.state = 'COMPLETED' AND r.companion_representative_id IS NULL " +
                "AND regexp_replace(r.user_request::jsonb ->> 'phoneNumber', '[^0-9]', '', 'g') = ?) " +
                "AND NOT EXISTS(SELECT 1 FROM tb_preregister_application b WHERE b.expo_id = a.expo_id " +
                "AND b.participant_id = a.participant_id AND b.status <> 'CANCELLED') ORDER BY a.participant_id",
            { rs, _ -> rs.getLong(1) },
            expoId,
            phone,
        )

    /** User로 처음 보내기 전에 계산한 취소 동행자 ID를 원본 요청에 고정한다. 재시도는 같은 본문을 보내야 User 멱등 검사를 통과한다. */
    fun saveReleased(
        reservation: PreregisterReservation,
        ids: List<Long>,
    ) {
        jdbc.update(
            "UPDATE tb_preregister_reservation SET user_request = " +
                "(user_request::jsonb || jsonb_build_object('releasedParticipantIds', ?::jsonb))::text " +
                "WHERE expo_id = ? AND request_id = ?",
            mapper.writeValueAsString(ids),
            reservation.expoId,
            reservation.requestId,
        )
    }

    fun insertWaiting(
        expoId: UUID,
        requestId: String,
        sessionId: Long,
        command: String,
        request: ParticipantRegistration,
        applicants: Int,
        categories: Pair<Int, Int>? = null,
    ) {
        jdbc.update(
            "INSERT INTO tb_preregister_reservation " +
                "(expo_id, request_id, session_id, command, user_request, applicants, teacher_applicants, other_applicants, " +
                "state, queue_seq) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'WAITING', nextval('seq_preregister_waitlist'))",
            expoId,
            requestId,
            sessionId,
            command,
            mapper.writeValueAsString(request),
            applicants,
            categories?.first,
            categories?.second,
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
                "SELECT * FROM tb_preregister_reservation r WHERE expo_id = ? AND session_id = ? AND state = 'WAITING' " +
                    // A party already holding seats for an offer (#144) is no longer the head waiting for one.
                    "AND NOT EXISTS (SELECT 1 FROM tb_promotion_offer o WHERE o.expo_id = r.expo_id AND o.request_id = r.request_id " +
                    "AND o.state IN ${PromotionOfferRepository.ACTIVE}) ORDER BY queue_seq LIMIT 1",
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
                "AND NOT EXISTS (SELECT 1 FROM tb_promotion_offer o WHERE o.expo_id = r.expo_id AND o.request_id = r.request_id " +
                "AND o.state IN ${PromotionOfferRepository.ACTIVE}) " +
                "AND (st.definition::jsonb ->> 'startedAt')::timestamptz > ? " +
                "GROUP BY r.expo_id, r.session_id, st.definition " +
                "HAVING (array_agg(r.applicants ORDER BY r.queue_seq))[1] <= (st.definition::jsonb ->> 'capacity')::bigint " +
                "- (SELECT count(*) FROM tb_preregister_application a WHERE a.expo_id = r.expo_id AND a.session_id = r.session_id " +
                "AND a.status = 'CONFIRMED') - (SELECT coalesce(sum(x.applicants), 0) FROM tb_preregister_reservation x " +
                "WHERE x.expo_id = r.expo_id AND x.session_id = r.session_id AND x.state = 'RESERVED') " +
                "- (SELECT coalesce(sum(o.seats), 0) FROM tb_promotion_offer o JOIN tb_preregister_reservation h " +
                "ON h.expo_id = o.expo_id AND h.request_id = o.request_id AND h.state = 'WAITING' " +
                "WHERE o.expo_id = r.expo_id AND o.session_id = r.session_id AND o.state IN ${PromotionOfferRepository.ACTIVE}) " +
                "ORDER BY min(r.queue_seq) LIMIT 100",
            { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getLong(2) },
            java.sql.Timestamp.from(now),
        )

    // The phone index from V8 keeps owner lookup cheap. Latest first; a party may wait for several sessions (#198).
    fun waitlistedByPhone(
        expoId: UUID,
        phone: String,
    ): List<PreregisterReservation> =
        jdbc.query(
            "SELECT * FROM tb_preregister_reservation WHERE expo_id = ? " +
                "AND regexp_replace(user_request::jsonb ->> 'phoneNumber', '[^0-9]', '', 'g') = ? " +
                "AND queue_seq IS NOT NULL AND state IN ('WAITING', 'RESERVED') ORDER BY queue_seq DESC",
            { rs, _ -> reservation(rs) },
            expoId,
            phone,
        )

    fun promote(reservation: PreregisterReservation): Boolean =
        jdbc.update(
            "UPDATE tb_preregister_reservation SET state = 'RESERVED', attempts = 0, next_attempt_at = CURRENT_TIMESTAMP " +
                "WHERE expo_id = ? AND request_id = ? AND state = 'WAITING'",
            reservation.expoId,
            reservation.requestId,
        ) == 1

    /** 대표자 신청이 취소되면 그 대표자에게 붙이려고 기다리던 동행자 추가도 끝낸다(#169). */
    fun withdrawCompanionWaits(
        expoId: UUID,
        sessionId: Long,
        representativeId: Long,
        actorType: ApplicationActorType,
        actorId: Long? = null,
    ): Int =
        jdbc.update(
            "UPDATE tb_preregister_reservation SET state = 'REJECTED', error_status = 409, withdrawn_at = CURRENT_TIMESTAMP, " +
                "withdrawn_actor_type = ?, withdrawn_actor_id = ? " +
                "WHERE expo_id = ? AND session_id = ? AND companion_representative_id = ? AND state = 'WAITING'",
            actorType.name,
            actorId,
            expoId,
            sessionId,
            representativeId,
        )

    // USER is the reservation's own representative after the session phone ownership check; the row is the reference.
    fun withdraw(
        reservation: PreregisterReservation,
        actorType: ApplicationActorType,
        // The admin ID for ADMIN withdrawals (#182); null otherwise.
        actorId: Long? = null,
    ): Boolean =
        jdbc.update(
            "UPDATE tb_preregister_reservation SET state = 'REJECTED', error_status = 409, withdrawn_at = CURRENT_TIMESTAMP, " +
                "withdrawn_actor_type = ?, withdrawn_actor_id = ? WHERE expo_id = ? AND request_id = ? AND state = 'WAITING'",
            actorType.name,
            actorId,
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
        // (teacher, other) seats from the snapshot; the remainder and null are unknown (#173).
        categories: Pair<Int, Int>? = null,
    ) {
        jdbc.update(
            "INSERT INTO tb_preregister_reservation " +
                "(expo_id, request_id, session_id, command, user_request, applicants, teacher_applicants, other_applicants) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            expoId,
            requestId,
            sessionId,
            command,
            mapper.writeValueAsString(request),
            applicants,
            categories?.first,
            categories?.second,
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
