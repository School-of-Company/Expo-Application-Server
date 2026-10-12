package team.startup.application.domain.application.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import team.startup.application.domain.application.presentation.dto.AdminApplicationAction
import team.startup.application.domain.application.presentation.dto.AdminApplicationActivity
import team.startup.application.domain.application.presentation.dto.AdminApplicationActivityPage
import team.startup.application.domain.application.presentation.dto.AdminApplicationActivityQuery
import team.startup.application.domain.application.presentation.dto.AdminApplicationStatus
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import java.sql.Timestamp
import java.util.UUID

@Repository
class AdminApplicationActivityRepository(
    jdbc: JdbcTemplate,
) {
    private val named = NamedParameterJdbcTemplate(jdbc)

    fun find(
        expoId: UUID,
        query: AdminApplicationActivityQuery,
    ): AdminApplicationActivityPage {
        val parameters =
            mutableMapOf<String, Any>(
                "expoId" to expoId,
                "limit" to query.size,
                "offset" to query.page.toLong() * query.size,
            )
        val filters = mutableListOf<String>()
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
                "h.status, h.created_at, h.actor_id, " +
                "CASE WHEN h.status = 'CANCELLED' THEN 'CANCELLED' " +
                "WHEN lag(h.status) OVER (PARTITION BY h.application_id ORDER BY h.created_at, h.id) = 'WAITING' " +
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
                )
            }
        return AdminApplicationActivityPage(items, count, query.page, query.size)
    }
}
