package team.startup.application.domain.application.repository

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import team.startup.application.domain.application.presentation.dto.ApplicationActorType
import team.startup.application.domain.application.presentation.dto.ReportPreregistrationReservationKind
import team.startup.application.domain.application.presentation.dto.ReportPreregistrationSession
import team.startup.application.domain.application.presentation.dto.ReportPreregistrationTransition
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

/** #284 사전등록 보고 행의 원장 부가 정보. 접수대장 조회와 같은 읽기 트랜잭션 안에서 부른다. */
@Repository
class ReportPreregistrationRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val mapper: ObjectMapper,
) {
    data class ApplicationExtra(
        val seatCategory: String?,
        val promotedFromWaiting: Boolean?,
        val snapshot: JsonNode?,
        val history: List<ReportPreregistrationTransition>,
    )

    data class ReservationExtra(
        val teacher: Int?,
        val other: Int?,
        val kind: ReportPreregistrationReservationKind,
        val companionRepresentativeId: Long?,
        val promotedFromWaiting: Boolean,
    )

    fun sessions(
        expoId: UUID,
        ids: Collection<Long>,
    ): Map<Long, ReportPreregistrationSession> {
        if (ids.isEmpty()) return emptyMap()
        return jdbc
            .query(
                "SELECT session_id, definition, deleted FROM tb_preregister_session_state WHERE expo_id = :expo AND session_id IN (:ids)",
                mapOf("expo" to expoId, "ids" to ids),
            ) { rs, _ ->
                val id = rs.getLong("session_id")
                val definition = mapper.readTree(rs.getString("definition"))

                fun text(name: String) = definition[name]?.takeIf { it.isString }?.asString()

                fun number(name: String) = definition[name]?.takeIf { it.isIntegralNumber }?.asInt()
                id to
                    ReportPreregistrationSession(
                        id,
                        text("title"),
                        text("startedAt")?.let(Instant::parse),
                        text("endedAt")?.let(Instant::parse),
                        text("place"),
                        number("capacity"),
                        number("teacherCapacity"),
                        number("nonTeacherCapacity"),
                        rs.getBoolean("deleted"),
                    )
            }.toMap()
    }

    fun applications(
        expoId: UUID,
        ids: Collection<Long>,
    ): Map<Long, ApplicationExtra> {
        if (ids.isEmpty()) return emptyMap()
        val parameters = mapOf("expo" to expoId, "ids" to ids)
        val history =
            jdbc
                .query(
                    "SELECT application_id, status, created_at, actor_type, actor_participant_id, actor_id " +
                        "FROM tb_preregister_transition " +
                        "WHERE expo_id = :expo AND application_id IN (:ids) ORDER BY id",
                    parameters,
                ) { rs, _ ->
                    rs.getLong("application_id") to
                        ReportPreregistrationTransition(
                            rs.getString("status"),
                            rs.getTimestamp("created_at")?.toInstant(),
                            ApplicationActorType.valueOf(rs.getString("actor_type")),
                            rs.getObject("actor_participant_id", Long::class.javaObjectType),
                            rs.getObject("actor_id", Long::class.javaObjectType),
                        )
                }.groupBy({ it.first }, { it.second })
        return jdbc
            .query(
                "SELECT a.id, a.seat_category, r.request_id IS NOT NULL AS linked, r.queue_seq IS NOT NULL AS promoted, " +
                    "$SNAPSHOT AS snapshot FROM tb_preregister_application a " +
                    "LEFT JOIN tb_preregister_reservation r ON r.expo_id = a.expo_id AND r.request_id = a.reservation_request_id " +
                    "WHERE a.expo_id = :expo AND a.id IN (:ids)",
                parameters,
            ) { rs, _ ->
                val id = rs.getLong("id")
                val linked = rs.getBoolean("linked")
                id to
                    ApplicationExtra(
                        rs.getString("seat_category"),
                        if (linked) rs.getBoolean("promoted") else null,
                        if (linked) rs.getString("snapshot")?.let(mapper::readTree) else null,
                        history[id].orEmpty(),
                    )
            }.toMap()
    }

    fun reservations(
        expoId: UUID,
        ids: Collection<String>,
    ): Map<String, ReservationExtra> {
        if (ids.isEmpty()) return emptyMap()
        return jdbc
            .query(
                "SELECT request_id, teacher_applicants, other_applicants, companion_representative_id, session_addition, " +
                    "queue_seq IS NOT NULL AS queued FROM tb_preregister_reservation WHERE expo_id = :expo AND request_id IN (:ids)",
                mapOf("expo" to expoId, "ids" to ids),
            ) { rs, _ ->
                val companion = rs.getObject("companion_representative_id", Long::class.javaObjectType)
                rs.getString("request_id") to
                    ReservationExtra(
                        rs.getObject("teacher_applicants", Int::class.javaObjectType),
                        rs.getObject("other_applicants", Int::class.javaObjectType),
                        when {
                            companion != null -> ReportPreregistrationReservationKind.COMPANION_ADDITION
                            rs.getBoolean("session_addition") -> ReportPreregistrationReservationKind.SESSION_ADDITION
                            else -> ReportPreregistrationReservationKind.PARTY
                        },
                        companion,
                        rs.getBoolean("queued"),
                    )
            }.toMap()
    }

    private companion object {
        // Same submitted fields the admin ledger exposes for reservations; consents and recovery commands stay private.
        const val SNAPSHOT =
            "jsonb_build_object('name', r.user_request::jsonb -> 'name', 'phoneNumber', r.user_request::jsonb -> 'phoneNumber', " +
                "'occupation', r.user_request::jsonb -> 'occupation', 'informationJson', r.user_request::jsonb -> 'informationJson', " +
                "'questions', r.user_request::jsonb -> 'questions')"
    }
}
