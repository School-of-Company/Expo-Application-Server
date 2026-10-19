package team.startup.application.domain.application.repository

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationQuery
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationRow
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationStatus
import team.startup.application.domain.application.presentation.dto.ApplicationActorNameSource
import team.startup.application.domain.application.presentation.dto.ApplicationActorRole
import team.startup.application.domain.application.presentation.dto.ApplicationActorType
import team.startup.application.domain.application.presentation.dto.ApplicationUserActor
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.util.UUID

@Repository
class AdminPreregistrationRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val mapper: ObjectMapper,
) {
    fun query(
        expoId: UUID,
        query: AdminPreregistrationQuery,
        participantIds: List<Long>?,
        limit: Int,
        offset: Long = 0,
        keys: List<String>? = null,
    ): Pair<List<AdminPreregistrationRow>, Long> {
        val parameters = mutableMapOf<String, Any>("expo" to expoId, "limit" to limit, "offset" to offset)
        val conditions = mutableListOf("TRUE")
        query.sessionId?.let {
            conditions.add("session_id = :session")
            parameters["session"] = it
        }
        query.status?.let {
            conditions.add(if (it.isEmpty()) "FALSE" else "status IN (:statuses)")
            parameters["statuses"] = it.map { status -> status.name }
        }
        keys?.let {
            conditions.add(if (it.isEmpty()) "FALSE" else "key IN (:keys)")
            parameters["keys"] = it
        }
        query.q?.let {
            val confirmed = if (participantIds.isNullOrEmpty()) "FALSE" else "participant_id IN (:participants)"
            parameters["participants"] = participantIds.orEmpty()
            parameters["q"] = it.trim()
            parameters["phone"] = it.filter(Char::isDigit)
            conditions.add(
                "($confirmed OR (reservation IS NOT NULL AND (" +
                    "position(lower(:q) in lower(reservation ->> 'name')) > 0 OR " +
                    "(:phone <> '' AND position(:phone in regexp_replace(reservation ->> 'phoneNumber', '[^0-9]', '', 'g')) > 0))))",
            )
        }
        val source =
            """
            WITH ledger AS (
                SELECT 'APPLICATION:' || a.expo_id || ':' || a.id AS key, a.expo_id, a.session_id, a.status, a.id AS application_id,
                    NULL::text AS reservation_id, a.participant_id, a.representative_id, 1 AS applicants, a.created_at,
                    NULL::jsonb AS reservation, c.created_at AS cancelled_at,
                    CASE WHEN a.status = 'CANCELLED' THEN coalesce(c.actor_type, 'UNKNOWN') END AS cancellation_actor_type,
                    c.actor_participant_id AS cancellation_actor_participant_id, NULL::text AS cancellation_actor_reservation,
                    NULL::text AS cancellation_actor_name, c.actor_id AS cancellation_actor_id
                FROM tb_preregister_application a
                LEFT JOIN LATERAL (SELECT t.created_at, t.actor_type, t.actor_participant_id, t.actor_id FROM tb_preregister_transition t
                    WHERE t.application_id = a.id AND t.status = 'CANCELLED' ORDER BY t.id DESC LIMIT 1) c ON a.status = 'CANCELLED'
                WHERE a.expo_id = :expo
                UNION ALL
                SELECT 'RESERVATION:' || expo_id || ':' || request_id, expo_id, session_id,
                    CASE WHEN withdrawn_at IS NOT NULL THEN 'CANCELLED' WHEN state = 'RESERVED' THEN 'PENDING' ELSE 'WAITING' END,
                    NULL::bigint, request_id, NULL::bigint, NULL::bigint, applicants, NULL::timestamptz,
                    jsonb_build_object('name', user_request::jsonb -> 'name', 'phoneNumber', user_request::jsonb -> 'phoneNumber',
                        'occupation', user_request::jsonb -> 'occupation', 'informationJson', user_request::jsonb -> 'informationJson',
                        'questions', user_request::jsonb -> 'questions'),
                    withdrawn_at, CASE WHEN withdrawn_at IS NOT NULL THEN coalesce(withdrawn_actor_type, 'UNKNOWN') END, NULL::bigint,
                    CASE WHEN withdrawn_actor_type = 'USER' THEN request_id END,
                    CASE WHEN withdrawn_actor_type = 'USER' THEN user_request::jsonb ->> 'name' END, withdrawn_actor_id
                FROM tb_preregister_reservation
                WHERE expo_id = :expo AND (state IN ('WAITING', 'RESERVED') OR withdrawn_at IS NOT NULL)
            )
            SELECT * FROM ledger WHERE ${conditions.joinToString(" AND ")}
            """.trimIndent()
        val total = requireNotNull(jdbc.queryForObject("SELECT count(*) FROM ($source) matched", parameters, Long::class.java))
        val rows =
            jdbc.query("$source ORDER BY session_id, key LIMIT :limit OFFSET :offset", parameters) { rs, _ ->
                AdminPreregistrationRow(
                    rs.getString("key"),
                    expoId,
                    rs.getLong("session_id"),
                    AdminPreregistrationStatus.valueOf(rs.getString("status")),
                    rs.getObject("application_id", Long::class.javaObjectType),
                    rs.getString("reservation_id"),
                    rs.getObject("participant_id", Long::class.javaObjectType),
                    rs.getObject("representative_id", Long::class.javaObjectType),
                    rs.getInt("applicants"),
                    rs.getTimestamp("created_at")?.toInstant(),
                    reservation = rs.getString("reservation")?.let(mapper::readTree),
                    cancelledAt = rs.getTimestamp("cancelled_at")?.toInstant(),
                    cancellationActorType = rs.getString("cancellation_actor_type")?.let(ApplicationActorType::valueOf),
                    cancellationActorUser = cancellationActor(rs),
                    cancellationActorId = rs.getObject("cancellation_actor_id", Long::class.javaObjectType),
                )
            }
        return rows to total
    }

    private fun cancellationActor(rs: ResultSet): ApplicationUserActor? {
        rs.getObject("cancellation_actor_participant_id", Long::class.javaObjectType)?.let {
            return ApplicationUserActor(it, null, ApplicationActorRole.REPRESENTATIVE, null, ApplicationActorNameSource.CURRENT_USER)
        }
        return rs.getString("cancellation_actor_reservation")?.let {
            ApplicationUserActor(
                null,
                it,
                ApplicationActorRole.REPRESENTATIVE,
                rs.getString("cancellation_actor_name"),
                ApplicationActorNameSource.REGISTRATION_SNAPSHOT,
            )
        }
    }
}
