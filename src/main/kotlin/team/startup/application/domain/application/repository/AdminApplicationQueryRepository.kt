package team.startup.application.domain.application.repository

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import team.startup.application.domain.application.presentation.dto.AdminApplicationHistory
import team.startup.application.domain.application.presentation.dto.AdminApplicationQuery
import team.startup.application.domain.application.presentation.dto.AdminApplicationRow
import team.startup.application.domain.application.presentation.dto.AdminApplicationSort
import team.startup.application.domain.application.presentation.dto.AdminApplicationStatus
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Repository
class AdminApplicationQueryRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val mapper: ObjectMapper,
) {
    fun query(
        expoId: UUID,
        query: AdminApplicationQuery,
    ): Pair<List<AdminApplicationRow>, Map<AdminApplicationStatus, Long>> {
        val conditions = mutableListOf("a.expo_id = :expoId")
        val parameters = mutableMapOf<String, Any>("expoId" to expoId)

        fun filter(
            name: String,
            value: Any?,
            condition: String,
        ) {
            if (value != null) {
                conditions.add(condition)
                parameters[name] = value
            }
        }
        filter("type", query.programType?.name, "a.program_type = :type")
        filter("program", query.programId, "a.program_id = :program")
        filter("group", query.groupId, "a.group_id = :group")
        filter("id", query.applicationId, "a.id = :id")
        filter("status", query.status?.name, "a.status = :status")
        filter(
            "search",
            query.search?.trim()?.takeIf { it.isNotEmpty() },
            "strpos(lower(a.definition_snapshot->>'title'), lower(:search)) > 0",
        )
        filter("from", query.from?.let(java.sql.Timestamp::from), "a.created_at >= :from")
        filter("until", query.until?.let(java.sql.Timestamp::from), "a.created_at < :until")
        val where = conditions.joinToString(" AND ")
        val counts =
            jdbc
                .query(
                    "SELECT a.status, count(*) FROM tb_program_session_application a WHERE $where GROUP BY a.status",
                    parameters,
                ) { rs, _ ->
                    AdminApplicationStatus.valueOf(rs.getString(1)) to rs.getLong(2)
                }.toMap()
        parameters["limit"] = query.size
        parameters["offset"] = query.page.toLong() * query.size
        val direction = if (query.sort == AdminApplicationSort.CREATED_ASC) "ASC" else "DESC"
        val rows =
            jdbc.query(
                "SELECT a.*, COALESCE((SELECT jsonb_agg(" +
                    "jsonb_build_object('id', h.id, 'status', h.status, 'createdAt', h.created_at) " +
                    "ORDER BY h.created_at, h.id) FROM tb_program_session_history h " +
                    "WHERE h.application_id = a.id), '[]'::jsonb) AS history " +
                    "FROM tb_program_session_application a WHERE $where " +
                    "ORDER BY a.created_at $direction, a.id $direction LIMIT :limit OFFSET :offset",
                parameters,
            ) { rs, _ ->
                AdminApplicationRow(
                    rs.getObject("id", UUID::class.java),
                    rs.getLong("participant_id"),
                    rs.getObject("trainee_id", Long::class.javaObjectType),
                    ProgramSessionType.valueOf(rs.getString("program_type")),
                    rs.getLong("program_id"),
                    rs.getObject("group_id", UUID::class.java),
                    AdminApplicationStatus.valueOf(rs.getString("status")),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getObject("form_id", UUID::class.java),
                    rs.getLong("form_version"),
                    mapper.readTree(rs.getString("definition_snapshot")),
                    mapper.readTree(rs.getString("questions_snapshot")),
                    mapper.readTree(rs.getString("answers_snapshot")),
                    mapper.readValue(
                        rs.getString("history"),
                        mapper.typeFactory.constructCollectionType(List::class.java, AdminApplicationHistory::class.java),
                    ),
                )
            }
        return rows to AdminApplicationStatus.entries.associateWith { counts[it] ?: 0L }
    }
}
