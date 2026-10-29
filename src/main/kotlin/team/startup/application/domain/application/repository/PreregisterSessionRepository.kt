package team.startup.application.domain.application.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import team.startup.application.domain.application.presentation.dto.AdminPreregisterSeatUsage
import team.startup.application.domain.application.presentation.dto.AdminPreregisterSessionSummary
import team.startup.application.domain.application.presentation.dto.AdminPreregisterSummary
import team.startup.application.domain.application.presentation.dto.ApplicationActorType
import team.startup.application.domain.application.presentation.dto.PreregisterApplicationReceipt
import team.startup.application.domain.application.presentation.dto.PreregisterSessionChangeRequest
import team.startup.application.domain.application.presentation.dto.PreregisterSessionDefinition
import team.startup.application.domain.application.service.impl.ParticipantRegistration
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Repository
class PreregisterSessionRepository(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
) {
    private val namedJdbc = NamedParameterJdbcTemplate(jdbc)

    fun adminSummary(expoId: UUID): AdminPreregisterSummary {
        val totals =
            jdbc.queryForMap(
                "SELECT count(DISTINCT participant_id) FILTER (WHERE status = 'CONFIRMED') AS confirmed, " +
                    "count(DISTINCT participant_id) FILTER (WHERE status = 'CANCELLED') AS cancelled " +
                    "FROM tb_preregister_application WHERE expo_id = ?",
                expoId,
            )
        val reservations =
            jdbc.queryForMap(
                "SELECT count(*) FILTER (WHERE state = 'RESERVED') AS requests, " +
                    "coalesce(sum(applicants) FILTER (WHERE state = 'RESERVED'), 0) AS applicants, " +
                    "coalesce(sum(applicants) FILTER (WHERE state = 'WAITING'), 0) AS waiting " +
                    "FROM tb_preregister_reservation WHERE expo_id = ?",
                expoId,
            )
        val sessions =
            jdbc.query(
                "WITH sessions AS (SELECT session_id FROM tb_preregister_session_state WHERE expo_id = ? " +
                    "UNION SELECT session_id FROM tb_preregister_application WHERE expo_id = ? " +
                    "UNION SELECT session_id FROM tb_preregister_reservation WHERE expo_id = ?) " +
                    "SELECT s.session_id, " +
                    "(SELECT count(DISTINCT participant_id) FROM tb_preregister_application a " +
                    " WHERE a.expo_id = ? AND a.session_id = s.session_id AND a.status = 'CONFIRMED') AS confirmed, " +
                    "(SELECT count(DISTINCT participant_id) FROM tb_preregister_application a " +
                    " WHERE a.expo_id = ? AND a.session_id = s.session_id AND a.status = 'CANCELLED') AS cancelled, " +
                    "(SELECT count(*) FROM tb_preregister_reservation r " +
                    " WHERE r.expo_id = ? AND r.session_id = s.session_id AND r.state = 'RESERVED') AS requests, " +
                    "(SELECT coalesce(sum(applicants), 0) FROM tb_preregister_reservation r " +
                    " WHERE r.expo_id = ? AND r.session_id = s.session_id AND r.state = 'RESERVED') AS applicants, " +
                    "w.waiting, w.parties, w.head, " +
                    "(st.definition::jsonb ->> 'capacity')::bigint AS capacity, " +
                    "(st.definition::jsonb ->> 'waitingCapacity')::int AS waiting_capacity, " +
                    "(st.definition::jsonb ->> 'closed')::boolean AS closed, " +
                    "(st.definition::jsonb ->> 'startedAt')::timestamptz <= CURRENT_TIMESTAMP AS started, " +
                    "(SELECT coalesce(sum(o.seats), 0) FROM tb_promotion_offer o JOIN tb_preregister_reservation h " +
                    " ON h.expo_id = o.expo_id AND h.request_id = o.request_id AND h.state = 'WAITING' " +
                    " WHERE o.expo_id = ? AND o.session_id = s.session_id AND o.state IN ${PromotionOfferRepository.ACTIVE}) AS offered " +
                    "FROM sessions s LEFT JOIN tb_preregister_session_state st ON st.expo_id = ? AND st.session_id = s.session_id " +
                    "CROSS JOIN LATERAL (SELECT coalesce(sum(applicants), 0) AS waiting, count(*) AS parties, " +
                    " (array_agg(applicants ORDER BY queue_seq))[1] AS head FROM tb_preregister_reservation r " +
                    " WHERE r.expo_id = ? AND r.session_id = s.session_id AND r.state = 'WAITING') w " +
                    "ORDER BY s.session_id",
                { rs, _ ->
                    // Seats held by participation offers (#144) are not free for the queue either.
                    val occupied = rs.getLong(2) + rs.getLong(5) + rs.getLong(13)
                    val head = rs.getObject(8, Int::class.javaObjectType)
                    val free = rs.getObject(9, Long::class.javaObjectType)?.let { it - occupied }
                    val blocked =
                        when {
                            head == null -> null
                            rs.getBoolean(11) -> "CLOSED"
                            rs.getBoolean(12) -> "SESSION_STARTED"
                            free != null && head > free -> "HEAD_TOO_LARGE"
                            else -> null
                        }
                    AdminPreregisterSessionSummary(
                        rs.getLong(1),
                        rs.getLong(2),
                        rs.getLong(3),
                        rs.getLong(6),
                        rs.getLong(4),
                        rs.getLong(5),
                        rs.getLong(7),
                        head,
                        free,
                        rs.getObject(10, Int::class.javaObjectType),
                        blocked,
                    )
                },
                expoId,
                expoId,
                expoId,
                expoId,
                expoId,
                expoId,
                expoId,
                expoId,
                expoId,
                expoId,
            )
        val usage = seatUsages(expoId)
        return AdminPreregisterSummary(
            expoId,
            (totals["confirmed"] as Number).toLong(),
            (totals["cancelled"] as Number).toLong(),
            (reservations["waiting"] as Number).toLong(),
            (reservations["requests"] as Number).toLong(),
            (reservations["applicants"] as Number).toLong(),
            sessions.map { it.copy(seatUsage = usage[it.sessionId] ?: AdminPreregisterSeatUsage(0, 0, 0)) },
        )
    }

    fun confirmedSeatCategories(
        expoId: UUID,
        sessionId: Long,
        participantIds: List<Long>,
    ): Set<String> =
        if (participantIds.isEmpty()) {
            emptySet()
        } else {
            namedJdbc
                .queryForList(
                    "SELECT DISTINCT seat_category FROM tb_preregister_application WHERE expo_id = :expoId AND session_id = :sessionId " +
                        "AND participant_id IN (:ids) AND status = 'CONFIRMED' AND seat_category IS NOT NULL",
                    mapOf("expoId" to expoId, "sessionId" to sessionId, "ids" to participantIds),
                    String::class.java,
                ).filterNotNull()
                .toSet()
        }

    fun seatUsage(
        expoId: UUID,
        sessionId: Long,
    ): AdminPreregisterSeatUsage = seatUsages(expoId, sessionId)[sessionId] ?: AdminPreregisterSeatUsage(0, 0, 0)

    // Same seats as occupied(): confirmed rows, RESERVED parties and offers held for WAITING parties (#173).
    private fun seatUsages(
        expoId: UUID,
        sessionId: Long? = null,
    ): Map<Long, AdminPreregisterSeatUsage> =
        jdbc
            .query(
                "SELECT session_id, coalesce(sum(teacher), 0), coalesce(sum(other), 0), coalesce(sum(unknown), 0) FROM (" +
                    "SELECT session_id, count(*) FILTER (WHERE seat_category = 'TEACHER') AS teacher, " +
                    "count(*) FILTER (WHERE seat_category = 'OTHER') AS other, count(*) FILTER (WHERE seat_category IS NULL) AS unknown " +
                    "FROM tb_preregister_application WHERE expo_id = ? AND status = 'CONFIRMED' GROUP BY session_id " +
                    "UNION ALL SELECT r.session_id, coalesce(r.teacher_applicants, 0), coalesce(r.other_applicants, 0), " +
                    "r.applicants - coalesce(r.teacher_applicants, 0) - coalesce(r.other_applicants, 0) " +
                    "FROM tb_preregister_reservation r WHERE r.expo_id = ? AND (r.state = 'RESERVED' OR (r.state = 'WAITING' AND " +
                    "EXISTS (SELECT 1 FROM tb_promotion_offer o WHERE o.expo_id = r.expo_id AND o.request_id = r.request_id " +
                    "AND o.state IN ${PromotionOfferRepository.ACTIVE})))) u " +
                    "WHERE CAST(? AS bigint) IS NULL OR session_id = ? GROUP BY session_id",
                { rs, _ -> rs.getLong(1) to AdminPreregisterSeatUsage(rs.getLong(2), rs.getLong(3), rs.getLong(4)) },
                expoId,
                expoId,
                sessionId,
                sessionId,
            ).toMap()

    fun confirmedParticipantIds(expoId: UUID): List<Long> =
        jdbc.query(
            "SELECT DISTINCT participant_id FROM tb_preregister_application WHERE expo_id = ? AND status = 'CONFIRMED' ORDER BY participant_id",
            { rs, _ -> rs.getLong(1) },
            expoId,
        )

    fun waitingRegistrations(expoId: UUID): List<ParticipantRegistration> =
        jdbc.query(
            "SELECT user_request FROM tb_preregister_reservation WHERE expo_id = ? AND state = 'WAITING'",
            { rs, _ -> mapper.readValue(rs.getString(1), ParticipantRegistration::class.java) },
            expoId,
        )

    fun lock(expoId: UUID) {
        jdbc.execute("SET LOCAL lock_timeout = '3s'")
        jdbc.queryForObject("SELECT CAST(pg_advisory_xact_lock(34, hashtext(?)) AS text)", String::class.java, expoId.toString())
    }

    fun readLock(expoId: UUID) {
        jdbc.queryForObject("SELECT CAST(pg_advisory_xact_lock_shared(34, hashtext(?)) AS text)", String::class.java, expoId.toString())
    }

    fun expoDeleted(expoId: UUID): Boolean =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM tb_preregister_deleted_expo WHERE expo_id = ?)",
                Boolean::class.java,
                expoId,
            ),
        )

    fun definition(
        expoId: UUID,
        sessionId: Long,
    ): PreregisterSessionDefinition? =
        jdbc
            .query(
                "SELECT definition FROM tb_preregister_session_state WHERE expo_id = ? AND session_id = ?",
                { rs, _ -> mapper.readValue(rs.getString(1), PreregisterSessionDefinition::class.java) },
                expoId,
                sessionId,
            ).singleOrNull()

    fun saveDefinition(definition: PreregisterSessionDefinition) {
        jdbc.update(
            "INSERT INTO tb_preregister_session_state (expo_id, session_id, definition) VALUES (?, ?, ?) " +
                "ON CONFLICT (expo_id, session_id) DO UPDATE SET definition = EXCLUDED.definition",
            definition.expoId,
            definition.id,
            mapper.writeValueAsString(definition),
        )
    }

    fun deleted(
        expoId: UUID,
        sessionId: Long,
    ): Boolean =
        jdbc
            .query(
                "SELECT deleted FROM tb_preregister_session_state WHERE expo_id = ? AND session_id = ?",
                { rs, _ -> rs.getBoolean(1) },
                expoId,
                sessionId,
            ).singleOrNull() ?: false

    fun change(
        expoId: UUID,
        sessionId: Long,
        revision: Long,
    ): PreregisterSessionChangeRequest? =
        jdbc
            .query(
                "SELECT command FROM tb_preregister_session_change WHERE expo_id = ? AND session_id = ? AND revision = ?",
                { rs, _ -> mapper.readValue(rs.getString(1), PreregisterSessionChangeRequest::class.java) },
                expoId,
                sessionId,
                revision,
            ).singleOrNull()

    fun pending(
        expoId: UUID,
        sessionId: Long,
    ): Pair<Long, PreregisterSessionChangeRequest>? =
        jdbc
            .query(
                "SELECT revision, command FROM tb_preregister_session_change WHERE expo_id = ? AND session_id = ? AND NOT completed",
                { rs, _ -> rs.getLong(1) to mapper.readValue(rs.getString(2), PreregisterSessionChangeRequest::class.java) },
                expoId,
                sessionId,
            ).let { rows ->
                check(rows.size <= 1) { "Multiple pending preregister session changes" }
                rows.singleOrNull()
            }

    fun prepare(
        expoId: UUID,
        sessionId: Long,
        revision: Long,
        request: PreregisterSessionChangeRequest,
    ) {
        jdbc.update(
            "INSERT INTO tb_preregister_session_change (expo_id, session_id, revision, command) VALUES (?, ?, ?, ?)",
            expoId,
            sessionId,
            revision,
            mapper.writeValueAsString(request),
        )
    }

    fun complete(
        expoId: UUID,
        sessionId: Long,
        revision: Long,
        deleted: Boolean,
    ) {
        jdbc.update(
            "UPDATE tb_preregister_session_change SET completed = TRUE WHERE expo_id = ? AND session_id = ? AND revision = ?",
            expoId,
            sessionId,
            revision,
        )
        jdbc.update("UPDATE tb_preregister_session_state SET deleted = ? WHERE expo_id = ? AND session_id = ?", deleted, expoId, sessionId)
    }

    fun history(
        expoId: UUID,
        sessionId: Long,
    ): Boolean =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM tb_preregister_application WHERE expo_id = ? AND session_id = ?) OR " +
                    "EXISTS(SELECT 1 FROM tb_preregister_reservation WHERE expo_id = ? AND session_id = ? AND state <> 'REJECTED')",
                Boolean::class.java,
                expoId,
                sessionId,
                expoId,
                sessionId,
            ),
        )

    fun applications(
        expoId: UUID,
        sessionId: Long,
    ): List<PreregisterApplicationReceipt> =
        jdbc.query(
            "SELECT id, participant_id, session_id, status FROM tb_preregister_application " +
                "WHERE expo_id = ? AND session_id = ? ORDER BY id",
            { rs, _ -> PreregisterApplicationReceipt(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4)) },
            expoId,
            sessionId,
        )

    data class ApplicationRow(
        val expoId: UUID,
        val sessionId: Long,
        val representativeId: Long,
        val participantId: Long,
        val status: String,
    )

    // #308: 신청 결과 문자가 보내기 직전에 현재 상태를 다시 확인한다.
    fun application(id: Long): ApplicationRow? =
        jdbc
            .query(
                "SELECT expo_id, session_id, representative_id, participant_id, status FROM tb_preregister_application WHERE id = ?",
                { rs, _ ->
                    ApplicationRow(rs.getObject(1, UUID::class.java), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getString(5))
                },
                id,
            ).singleOrNull()

    // 이 대표자 일행에 남은 활성 신청 수. 0이면 취소가 일행 전체의 사전등록을 끝낸 것이다.
    fun activeUnderRepresentative(
        expoId: UUID,
        representativeId: Long,
    ): Int =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT count(*) FROM tb_preregister_application WHERE expo_id = ? AND representative_id = ? AND status <> 'CANCELLED'",
                Int::class.java,
                expoId,
                representativeId,
            ),
        )

    fun active(
        expoId: UUID,
        sessionId: Long,
        participantId: Long,
    ): PreregisterApplicationReceipt? = activeOwned(expoId, sessionId, participantId)?.first

    // #198: a participant holds at most one active application per session, and may hold several sessions.
    fun activeOwned(
        expoId: UUID,
        sessionId: Long,
        participantId: Long,
    ): Pair<PreregisterApplicationReceipt, Long>? =
        jdbc
            .query(
                "SELECT id, participant_id, session_id, status, representative_id FROM tb_preregister_application " +
                    "WHERE expo_id = ? AND session_id = ? AND participant_id = ? AND status <> 'CANCELLED'",
                { rs, _ -> PreregisterApplicationReceipt(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4)) to rs.getLong(5) },
                expoId,
                sessionId,
                participantId,
            ).singleOrNull()

    /** 다른 회차에서 [window] 시간과 일부라도 겹치는 확정 신청을 가진 참가자가 있는지(#198). */
    fun overlapping(
        expoId: UUID,
        sessionId: Long,
        participantIds: Collection<Long>,
        window: PreregisterSessionDefinition,
    ): Boolean =
        participantIds.isNotEmpty() &&
            namedJdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM tb_preregister_application a JOIN tb_preregister_session_state st " +
                    "ON st.expo_id = a.expo_id AND st.session_id = a.session_id WHERE a.expo_id = :expoId " +
                    "AND a.session_id <> :sessionId AND a.participant_id IN (:ids) AND a.status = 'CONFIRMED' " +
                    "AND (st.definition::jsonb ->> 'startedAt')::timestamptz < :endedAt " +
                    "AND :startedAt < (st.definition::jsonb ->> 'endedAt')::timestamptz)",
                mapOf(
                    "expoId" to expoId,
                    "sessionId" to sessionId,
                    "ids" to participantIds,
                    "startedAt" to java.sql.Timestamp.from(window.startedAt),
                    "endedAt" to java.sql.Timestamp.from(window.endedAt),
                ),
                Boolean::class.java,
            ) == true

    fun owned(
        expoId: UUID,
        applicationId: Long,
    ): Pair<PreregisterApplicationReceipt, Long>? =
        jdbc
            .query(
                "SELECT id, participant_id, session_id, status, representative_id FROM tb_preregister_application WHERE expo_id = ? AND id = ?",
                { rs, _ -> PreregisterApplicationReceipt(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4)) to rs.getLong(5) },
                expoId,
                applicationId,
            ).singleOrNull()

    fun insert(
        expoId: UUID,
        sessionId: Long,
        representativeId: Long,
        participantId: Long,
        status: String,
        reservationRequestId: String? = null,
        actorType: ApplicationActorType = ApplicationActorType.UNKNOWN,
        actorParticipantId: Long? = null,
        seatCategory: String? = null,
    ): PreregisterApplicationReceipt {
        val id =
            requireNotNull(
                jdbc.queryForObject(
                    "INSERT INTO tb_preregister_application " +
                        "(expo_id, session_id, representative_id, participant_id, status, reservation_request_id, seat_category) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
                    Long::class.java,
                    expoId,
                    sessionId,
                    representativeId,
                    participantId,
                    status,
                    reservationRequestId,
                    seatCategory,
                ),
            )
        transition(id, actorType, actorParticipantId)
        return PreregisterApplicationReceipt(id, participantId, sessionId, status)
    }

    fun selectedOwned(
        expoId: UUID,
        sessionId: Long,
        ids: List<Long>,
    ): List<Pair<PreregisterApplicationReceipt, Long>> =
        namedJdbc.query(
            "SELECT id, participant_id, session_id, status, representative_id FROM tb_preregister_application " +
                "WHERE expo_id = :expoId AND session_id = :sessionId AND id IN (:ids) ORDER BY id",
            mapOf("expoId" to expoId, "sessionId" to sessionId, "ids" to ids),
            { rs, _ -> PreregisterApplicationReceipt(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4)) to rs.getLong(5) },
        )

    fun occupied(
        expoId: UUID,
        sessionId: Long,
    ): Long =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT (SELECT count(*) FROM tb_preregister_application " +
                    "WHERE expo_id = ? AND session_id = ? AND status = 'CONFIRMED') + " +
                    "(SELECT coalesce(sum(applicants), 0) FROM tb_preregister_reservation " +
                    "WHERE expo_id = ? AND session_id = ? AND state = 'RESERVED') + (${PromotionOfferRepository.SESSION_HELD})",
                Long::class.java,
                expoId,
                sessionId,
                expoId,
                sessionId,
                expoId,
                sessionId,
            ),
        )

    fun status(
        id: Long,
        status: String,
        actorType: ApplicationActorType = ApplicationActorType.UNKNOWN,
        actorParticipantId: Long? = null,
        actorId: Long? = null,
    ) {
        jdbc.update("UPDATE tb_preregister_application SET status = ? WHERE id = ?", status, id)
        // #297: 취소가 확정되면 현장등록 참가자의 입장 QR을 닫는 표시를 같은 트랜잭션에서 남긴다.
        if (status == "CANCELLED") {
            jdbc.update(
                "INSERT INTO tb_field_qr_revoked (expo_id, participant_id) " +
                    "SELECT expo_id, participant_id FROM tb_preregister_application WHERE id = ? ON CONFLICT DO NOTHING",
                id,
            )
        }
        transition(id, actorType, actorParticipantId, actorId)
    }

    // Registration confirmations keep the column default UNKNOWN; cancellations and owner companion additions (#169)
    // carry their executing actor.
    private fun transition(
        id: Long,
        actorType: ApplicationActorType = ApplicationActorType.UNKNOWN,
        actorParticipantId: Long? = null,
        actorId: Long? = null,
    ) {
        jdbc.update(
            "INSERT INTO tb_preregister_transition " +
                "(application_id, expo_id, session_id, participant_id, status, actor_type, actor_participant_id, actor_id) " +
                "SELECT id, expo_id, session_id, participant_id, status, ?, ?, ? FROM tb_preregister_application WHERE id = ?",
            actorType.name,
            actorParticipantId,
            actorId,
            id,
        )
    }

    fun request(
        expoId: UUID,
        requestId: String,
    ): Pair<String, List<PreregisterApplicationReceipt>>? =
        jdbc
            .query(
                "SELECT command, receipt FROM tb_preregister_request WHERE expo_id = ? AND request_id = ?",
                {
                    rs,
                    _,
                    ->
                    rs.getString(1) to mapper.readValue(rs.getString(2), Array<PreregisterApplicationReceipt>::class.java).toList()
                },
                expoId,
                requestId,
            ).singleOrNull()

    fun saveRequest(
        expoId: UUID,
        requestId: String,
        command: String,
        receipt: List<PreregisterApplicationReceipt>,
    ) {
        jdbc.update(
            "INSERT INTO tb_preregister_request (expo_id, request_id, command, receipt) VALUES (?, ?, ?, ?)",
            expoId,
            requestId,
            command,
            mapper.writeValueAsString(receipt),
        )
    }

    fun purge(expoId: UUID) {
        jdbc
            .query(
                "SELECT participant_id FROM tb_preregister_application WHERE expo_id = ? AND status = 'CONFIRMED'",
                { rs, _ -> rs.getLong(1) },
                expoId,
            ).forEach {
                SessionAttendanceOutboxRepository(jdbc).append(expoId, it, null)
            }
        jdbc.update("INSERT INTO tb_preregister_deleted_expo (expo_id) VALUES (?) ON CONFLICT DO NOTHING", expoId)
        jdbc.update("DELETE FROM tb_preregister_count_outbox WHERE expo_id = ?", expoId)
        jdbc.update(
            "UPDATE tb_preregister_reservation SET next_attempt_at = CURRENT_TIMESTAMP WHERE expo_id = ? AND state = 'RESERVED'",
            expoId,
        )
        listOf(
            "tb_preregister_transition",
            "tb_preregister_request",
            "tb_preregister_application",
            "tb_preregister_session_change",
            "tb_preregister_session_state",
            "tb_field_qr_revoked",
        ).forEach {
            jdbc.update("DELETE FROM $it WHERE expo_id = ?", expoId)
        }
        // Retain only uncertain User deliveries for same-requestId recovery and QR invalidation.
        jdbc.update(
            "DELETE FROM tb_preregister_reservation WHERE expo_id = ? AND (state <> 'RESERVED' OR attempts = 0)",
            expoId,
        )
    }
}
