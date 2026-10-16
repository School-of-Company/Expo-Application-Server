package team.startup.application.domain.application.repository

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationQuery
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationRow
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationStatus
import tools.jackson.databind.ObjectMapper
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
                SELECT 'APPLICATION:' || expo_id || ':' || id AS key, expo_id, session_id, status, id AS application_id,
                    NULL::text AS reservation_id, participant_id, representative_id, 1 AS applicants, created_at,
                    NULL::jsonb AS reservation
                FROM tb_preregister_application WHERE expo_id = :expo
                UNION ALL
                SELECT 'RESERVATION:' || expo_id || ':' || request_id, expo_id, session_id,
                    CASE WHEN withdrawn_at IS NOT NULL THEN 'CANCELLED' WHEN state = 'RESERVED' THEN 'PENDING' ELSE 'WAITING' END,
                    NULL::bigint, request_id, NULL::bigint, NULL::bigint, applicants, NULL::timestamptz,
                    jsonb_build_object('name', user_request::jsonb -> 'name', 'phoneNumber', user_request::jsonb -> 'phoneNumber',
                        'occupation', user_request::jsonb -> 'occupation', 'informationJson', user_request::jsonb -> 'informationJson',
                        'questions', user_request::jsonb -> 'questions')
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
                )
            }
        return rows to total
    }
}
