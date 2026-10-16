package team.startup.application.domain.application.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.support.SqlArrayValue
import org.springframework.stereotype.Repository
import team.startup.application.domain.application.presentation.dto.AdminApplicationAction
import team.startup.application.domain.application.presentation.dto.AdminApplicationActivity
import team.startup.application.domain.application.presentation.dto.AdminApplicationActivityPage
import team.startup.application.domain.application.presentation.dto.AdminApplicationActivityQuery
import team.startup.application.domain.application.presentation.dto.AdminApplicationStatus
import team.startup.application.domain.application.presentation.dto.ApplicationActorType
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.util.UUID

@Repository
class AdminApplicationActivityRepository(
    jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
) {
    private val named = NamedParameterJdbcTemplate(jdbc)

    fun find(
        expoId: UUID,
        query: AdminApplicationActivityQuery,
        participantIds: List<Long>? = null,
    ): AdminApplicationActivityPage {
        val parameters =
            mutableMapOf<String, Any>(
                "expoId" to expoId,
                "limit" to query.size,
                "offset" to query.page.toLong() * query.size,
            )
        val filters = mutableListOf<String>()
        if (participantIds != null) {
            if (participantIds.isEmpty()) {
                filters += "FALSE"
            } else {
                parameters["participantIds"] = SqlArrayValue("bigint", *participantIds.toTypedArray())
                filters += "e.participant_id = ANY(:participantIds)"
            }
        }
        query.programType?.let {
            parameters["programType"] = it.name
            filters += "e.program_type = :programType"
        }
        query.programId?.let {
            parameters["programId"] = it
            filters += "e.program_id = :programId"
        }
        query.status?.let {
            parameters["status"] = it.name
            filters += "e.status = :status"
        }
        query.action?.let {
            parameters["action"] = it.name
            filters += "e.action = :action"
        }
        query.applicationId?.let {
            parameters["applicationId"] = it
            filters += "e.application_id = :applicationId"
        }
        query.from?.let {
            parameters["from"] = Timestamp.from(it)
            filters += "e.created_at >= :from"
        }
        query.until?.let {
            parameters["until"] = Timestamp.from(it)
            filters += "e.created_at < :until"
        }
        val where = if (filters.isEmpty()) "" else "WHERE ${filters.joinToString(" AND ")}"
        val events =
            "WITH e AS (SELECT h.id, h.application_id, a.participant_id, a.program_type, a.program_id, " +
                "a.receipt_number, a.definition_snapshot, h.status, h.created_at, h.actor_id, " +
                "h.actor_type, h.previous_status, h.previous_status_recorded, " +
                "CASE WHEN h.status = 'CANCELLED' THEN 'CANCELLED' " +
                "WHEN (CASE WHEN h.previous_status_recorded THEN h.previous_status " +
                "ELSE lag(h.status) OVER (PARTITION BY h.application_id ORDER BY h.created_at, h.id) END) = 'WAITING' " +
                "AND h.status = 'CONFIRMED' THEN 'PROMOTED' ELSE 'APPLIED' END AS action " +
                "FROM tb_program_session_history h JOIN tb_program_session_application a ON a.id = h.application_id " +
                "WHERE a.expo_id = :expoId) "
        val count = requireNotNull(named.queryForObject("${events}SELECT count(*) FROM e $where", parameters, Long::class.java))
        val items =
            named.query(
                "${events}SELECT e.* FROM e $where ORDER BY e.created_at DESC, e.id DESC LIMIT :limit OFFSET :offset",
                parameters,
            ) { rs, _ ->
                AdminApplicationActivity(
                    rs.getLong("id"),
                    rs.getObject("application_id", UUID::class.java),
                    rs.getLong("participant_id"),
                    ProgramSessionType.valueOf(rs.getString("program_type")),
                    rs.getLong("program_id"),
                    AdminApplicationAction.valueOf(rs.getString("action")),
                    AdminApplicationStatus.valueOf(rs.getString("status")),
                    rs.getObject("actor_id", Long::class.javaObjectType),
                    rs.getTimestamp("created_at").toInstant(),
                    ApplicationActorType.valueOf(rs.getString("actor_type")),
                    rs.getString("previous_status")?.let(AdminApplicationStatus::valueOf),
                    rs.getBoolean("previous_status_recorded"),
                    rs.getLong("receipt_number"),
                    mapper.readTree(rs.getString("definition_snapshot")),
                )
            }
        return AdminApplicationActivityPage(items, count, query.page, query.size)
    }
}
