package team.startup.application.domain.application.repository

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.support.SqlArrayValue
import org.springframework.stereotype.Repository
import team.startup.application.domain.application.presentation.dto.AdminApplicationHistory
import team.startup.application.domain.application.presentation.dto.AdminApplicationQuery
import team.startup.application.domain.application.presentation.dto.AdminApplicationRow
import team.startup.application.domain.application.presentation.dto.AdminApplicationSessionSummary
import team.startup.application.domain.application.presentation.dto.AdminApplicationSort
import team.startup.application.domain.application.presentation.dto.AdminApplicationStatus
import team.startup.application.domain.application.presentation.dto.AdminApplicationSummary
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Repository
class AdminApplicationQueryRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val mapper: ObjectMapper,
) {
    fun summary(
        expoId: UUID,
        query: AdminApplicationQuery,
        participantIds: List<Long>? = null,
    ): AdminApplicationSummary {
        val (where, parameters) = predicate(expoId, query, participantIds)
        val aggregates =
            "count(*) AS total, count(DISTINCT a.participant_id) AS participants, " +
                AdminApplicationStatus.entries.joinToString(", ") {
                    "count(*) FILTER (WHERE a.status = '${it.name}') AS ${it.name.lowercase()}"
                }
        val totals =
            jdbc.queryForObject(
                "SELECT $aggregates, count(DISTINCT (a.program_type, a.program_id, a.group_id)) AS sessions " +
                    "FROM tb_program_session_application a WHERE $where",
                parameters,
            ) { rs, _ ->
                AdminApplicationSummary(
                    rs.getLong("total"),
                    rs.getLong("participants"),
                    AdminApplicationStatus.entries.associateWith { rs.getLong(it.name.lowercase()) },
                    rs.getLong("sessions"),
                    emptyList(),
                    query.page,
                    query.size,
                )
            }
        parameters["limit"] = query.size
        parameters["offset"] = query.page.toLong() * query.size
        val direction = if (query.sort.name.endsWith("_ASC")) "ASC" else "DESC"
        val sessions =
            jdbc.query(
                "SELECT a.program_type, a.program_id, a.group_id, $aggregates " +
                    "FROM tb_program_session_application a WHERE $where " +
                    "GROUP BY a.program_type, a.program_id, a.group_id " +
                    "ORDER BY min(a.created_at) $direction, a.program_type $direction, a.program_id $direction, a.group_id $direction " +
                    "LIMIT :limit OFFSET :offset",
                parameters,
            ) { rs, _ ->
                AdminApplicationSessionSummary(
                    ProgramSessionType.valueOf(rs.getString("program_type")),
                    rs.getLong("program_id"),
                    rs.getObject("group_id", UUID::class.java),
                    rs.getLong("total"),
                    rs.getLong("participants"),
                    AdminApplicationStatus.entries.associateWith { rs.getLong(it.name.lowercase()) },
                )
            }
        return totals.copy(sessions = sessions)
    }

    fun query(
        expoId: UUID,
        query: AdminApplicationQuery,
        participantIds: List<Long>? = null,
    ): Pair<List<AdminApplicationRow>, Map<AdminApplicationStatus, Long>> {
        val (where, parameters) = predicate(expoId, query, participantIds)
        val counts =
            jdbc
                .query(
                    "SELECT a.status, count(*) FROM tb_program_session_application a WHERE $where GROUP BY a.status",
                    parameters,
                ) { rs, _ ->
                    AdminApplicationStatus.valueOf(rs.getString(1)) to rs.getLong(2)
                }.toMap()
        val rows = rows(where, parameters, query.sort, query.size, query.page.toLong() * query.size)
        return rows to AdminApplicationStatus.entries.associateWith { counts[it] ?: 0L }
    }

    fun targets(
        expoId: UUID,
        query: AdminApplicationQuery,
        limit: Int,
        applicationIds: List<UUID>? = null,
        participantIds: List<Long>? = null,
    ): List<AdminApplicationRow> {
        val (where, parameters) = predicate(expoId, query, participantIds)
        if (applicationIds?.isEmpty() == true) return emptyList()
        if (applicationIds != null) parameters["ids"] = applicationIds
        return rows(
            where + if (applicationIds == null) "" else " AND a.id IN (:ids)",
            parameters,
            query.sort,
            limit,
            0,
        )
    }

    private fun predicate(
        expoId: UUID,
        query: AdminApplicationQuery,
        participantIds: List<Long>?,
    ): Pair<String, MutableMap<String, Any>> {
        val conditions = mutableListOf("a.expo_id = :expoId")
        val parameters = mutableMapOf<String, Any>("expoId" to expoId)
        if (participantIds != null) {
            if (participantIds.isEmpty()) {
                conditions.add("FALSE")
            } else {
                conditions.add("a.participant_id = ANY(:participantIds)")
                parameters["participantIds"] = SqlArrayValue("bigint", *participantIds.toTypedArray())
            }
        }

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
        filter("status", query.status?.map { it.name }, "a.status IN (:status)")
        filter(
            "search",
            query.search?.trim()?.takeIf { it.isNotEmpty() },
            "strpos(lower(a.definition_snapshot->>'title'), lower(:search)) > 0",
        )
        filter("from", query.from?.let(java.sql.Timestamp::from), "a.created_at >= :from")
        filter("until", query.until?.let(java.sql.Timestamp::from), "a.created_at < :until")
        // Expo stores session times as KST local "yyyy-MM-ddTHH:mm" or "yyyy-MM-dd HH:mm"; the first 10 characters are the date.
        filter("eventDate", query.eventDate?.toString(), "left(a.definition_snapshot->>'startedAt', 10) = :eventDate")
        return conditions.joinToString(" AND ") to parameters
    }

    private fun rows(
        where: String,
        parameters: MutableMap<String, Any>,
        sort: AdminApplicationSort,
        limit: Int,
        offset: Long,
    ): List<AdminApplicationRow> {
        parameters["limit"] = limit
        parameters["offset"] = offset
        return jdbc.query(
            "SELECT a.*, COALESCE((SELECT jsonb_agg(" +
                "jsonb_build_object('id', h.id, 'status', h.status, 'createdAt', h.created_at) " +
                "ORDER BY h.created_at, h.id) FROM tb_program_session_history h " +
                "WHERE h.application_id = a.id), '[]'::jsonb) AS history " +
                "FROM tb_program_session_application a WHERE $where " +
                "ORDER BY ${order(sort)} LIMIT :limit OFFSET :offset",
            parameters,
        ) { rs, _ ->
            AdminApplicationRow(
                rs.getObject("id", UUID::class.java),
                rs.getLong("receipt_number"),
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
                admissionRoute = rs.getString("admission_route"),
            )
        }
    }

    private fun order(sort: AdminApplicationSort): String {
        val direction = if (sort.name.endsWith("_ASC")) "ASC" else "DESC"
        val key =
            when (sort) {
                AdminApplicationSort.RECEIPT_ASC, AdminApplicationSort.RECEIPT_DESC -> {
                    "a.receipt_number $direction, "
                }

                AdminApplicationSort.PROGRAM_ASC, AdminApplicationSort.PROGRAM_DESC -> {
                    "a.definition_snapshot->>'title' $direction NULLS LAST, a.program_type $direction, a.program_id $direction, "
                }

                AdminApplicationSort.SESSION_ASC, AdminApplicationSort.SESSION_DESC -> {
                    "replace(a.definition_snapshot->>'startedAt', 'T', ' ') $direction NULLS LAST, a.program_type $direction, a.program_id $direction, "
                }

                AdminApplicationSort.CREATED_ASC, AdminApplicationSort.CREATED_DESC -> {
                    ""
                }
            }
        return "${key}a.created_at $direction, a.id $direction"
    }
}
