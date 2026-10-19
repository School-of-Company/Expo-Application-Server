package team.startup.application.domain.application.repository

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationAction
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationActivity
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationActivityPage
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationActivityQuery
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationActivityReservation
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationStatus
import team.startup.application.domain.application.presentation.dto.ApplicationActorNameSource
import team.startup.application.domain.application.presentation.dto.ApplicationActorRole
import team.startup.application.domain.application.presentation.dto.ApplicationActorType
import team.startup.application.domain.application.presentation.dto.ApplicationUserActor
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

// 새 로그 테이블 없이 기존 원장(tb_preregister_transition, tb_preregister_reservation)에서 읽는다(#189).
@Repository
class AdminPreregistrationActivityRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun find(
        expoId: UUID,
        query: AdminPreregistrationActivityQuery,
        participantIds: List<Long>?,
    ): AdminPreregistrationActivityPage {
        val parameters =
            mutableMapOf<String, Any>(
                "expo" to expoId,
                "limit" to query.size,
                "offset" to query.page.toLong() * query.size,
            )
        val filters = mutableListOf<String>()
        query.action?.let {
            parameters["action"] = it.name
            filters += "action = :action"
        }
        query.sessionId?.let {
            parameters["session"] = it
            filters += "session_id = :session"
        }
        // 시각이 NULL인 과거 행은 비교가 거짓이라 기간 조건에서 빠지고, 추정하지 않는다.
        query.from?.let {
            parameters["from"] = Timestamp.from(it)
            filters += "created_at >= :from"
        }
        query.until?.let {
            parameters["until"] = Timestamp.from(it)
            filters += "created_at < :until"
        }
        query.q?.let {
            val confirmed = if (participantIds.isNullOrEmpty()) "FALSE" else "participant_id IN (:participants)"
            parameters["participants"] = participantIds.orEmpty()
            parameters["q"] = it.trim()
            parameters["phone"] = it.filter(Char::isDigit)
            filters +=
                "($confirmed OR (reservation_name IS NOT NULL AND (" +
                "position(lower(:q) in lower(reservation_name)) > 0 OR " +
                "(:phone <> '' AND position(:phone in regexp_replace(coalesce(reservation_phone, ''), '[^0-9]', '', 'g')) > 0))))"
        }
        val where = if (filters.isEmpty()) "" else "WHERE ${filters.joinToString(" AND ")}"
        val source =
            """
            WITH e AS (
                SELECT 'TRANSITION:' || t.id AS key, 'APPLICATION:' || t.expo_id || ':' || t.application_id AS ledger_key,
                    t.status AS action, t.session_id, t.participant_id, NULL::text AS reservation_request_id, 1 AS applicants,
                    t.previous_status, t.status, t.created_at, t.actor_type, t.actor_id, t.actor_participant_id,
                    NULL::text AS actor_reservation, NULL::text AS reservation_name, NULL::text AS reservation_phone
                FROM (SELECT x.*, lag(x.status) OVER (PARTITION BY x.application_id ORDER BY x.id) AS previous_status
                    FROM tb_preregister_transition x WHERE x.expo_id = :expo) t
                WHERE t.status IN ('CONFIRMED', 'CANCELLED')
                UNION ALL
                SELECT 'RESERVATION:' || r.expo_id || ':' || r.request_id, 'RESERVATION:' || r.expo_id || ':' || r.request_id,
                    'WAITING_WITHDRAWN', r.session_id, NULL::bigint, r.request_id, r.applicants,
                    'WAITING', 'CANCELLED', r.withdrawn_at, coalesce(r.withdrawn_actor_type, 'UNKNOWN'), r.withdrawn_actor_id,
                    NULL::bigint, CASE WHEN r.withdrawn_actor_type = 'USER' THEN r.request_id END,
                    r.user_request::jsonb ->> 'name', r.user_request::jsonb ->> 'phoneNumber'
                FROM tb_preregister_reservation r
                WHERE r.expo_id = :expo AND r.withdrawn_at IS NOT NULL
            )
            SELECT * FROM e $where
            """.trimIndent()
        val total = requireNotNull(jdbc.queryForObject("SELECT count(*) FROM ($source) matched", parameters, Long::class.java))
        val items =
            jdbc.query("$source ORDER BY created_at DESC NULLS LAST, key DESC LIMIT :limit OFFSET :offset", parameters) { rs, _ ->
                item(rs)
            }
        return AdminPreregistrationActivityPage(items, total, query.page, query.size)
    }

    private fun item(rs: ResultSet): AdminPreregistrationActivity {
        val action = AdminPreregistrationAction.valueOf(rs.getString("action"))
        val requestId = rs.getString("reservation_request_id")
        return AdminPreregistrationActivity(
            key = rs.getString("key"),
            ledgerKey = rs.getString("ledger_key"),
            action = action,
            sessionId = rs.getLong("session_id"),
            participantId = rs.getObject("participant_id", Long::class.javaObjectType),
            reservationRequestId = requestId,
            applicants = rs.getInt("applicants"),
            previousStatus = rs.getString("previous_status")?.let(AdminPreregistrationStatus::valueOf),
            status = AdminPreregistrationStatus.valueOf(rs.getString("status")),
            createdAt = rs.getTimestamp("created_at")?.toInstant(),
            actorType = ApplicationActorType.valueOf(rs.getString("actor_type")),
            actorId = rs.getObject("actor_id", Long::class.javaObjectType),
            actorUser = actor(rs),
            reservation =
                requestId?.let {
                    AdminPreregistrationActivityReservation(rs.getString("reservation_name"), rs.getString("reservation_phone"))
                },
        )
    }

    private fun actor(rs: ResultSet): ApplicationUserActor? {
        rs.getObject("actor_participant_id", Long::class.javaObjectType)?.let {
            return ApplicationUserActor(it, null, ApplicationActorRole.REPRESENTATIVE, null, ApplicationActorNameSource.CURRENT_USER)
        }
        return rs.getString("actor_reservation")?.let {
            ApplicationUserActor(
                null,
                it,
                ApplicationActorRole.REPRESENTATIVE,
                rs.getString("reservation_name"),
                ApplicationActorNameSource.REGISTRATION_SNAPSHOT,
            )
        }
    }
}
