package team.startup.application.domain.application.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import team.startup.application.domain.application.presentation.dto.FieldVisitEvent
import team.startup.application.domain.application.presentation.dto.FieldVisitRegistration
import team.startup.application.domain.application.presentation.dto.FieldVisitSourcePage
import team.startup.application.domain.application.presentation.dto.FieldVisitSourceQuery
import java.time.LocalDate
import java.util.UUID

@Repository
class FieldVisitRegistrationRepository(
    private val jdbc: JdbcTemplate,
) {
    fun activeParticipantIds(
        expoId: UUID,
        participantIds: Collection<Long>,
    ): Set<Long> {
        if (participantIds.isEmpty()) return emptySet()
        return NamedParameterJdbcTemplate(jdbc)
            .query(
                "SELECT DISTINCT participant_id FROM (" +
                    "SELECT participant_id FROM tb_field_visit_registration WHERE expo_id=:expo " +
                    "AND (status='ACTIVE' OR cancellation_pending) " +
                    "UNION SELECT participant_id FROM tb_preregister_application WHERE expo_id=:expo AND status='CONFIRMED' " +
                    "UNION SELECT participant_id FROM tb_program_session_application WHERE expo_id=:expo AND status<>'CANCELLED' " +
                    "UNION SELECT participant_id FROM tb_standard_program_application) active WHERE participant_id IN (:ids)",
                mapOf("expo" to expoId, "ids" to participantIds),
                { rs, _ -> rs.getLong(1) },
            ).toSet()
    }

    fun source(
        expoId: UUID,
        query: FieldVisitSourceQuery,
    ): FieldVisitSourcePage {
        val all =
            jdbc.query(
                "SELECT * FROM tb_field_visit_registration WHERE expo_id=? AND (?::date IS NULL OR visit_date=?::date) " +
                    "AND (?::uuid IS NULL OR id>?::uuid) ORDER BY id LIMIT ?",
                row,
                expoId,
                query.visitDate,
                query.visitDate,
                query.cursor,
                query.cursor,
                query.size + 1,
            )
        val items = all.take(query.size)
        val events =
            if (items.isEmpty()) {
                emptyList()
            } else {
                NamedParameterJdbcTemplate(jdbc).query(
                    "SELECT * FROM tb_field_visit_event WHERE registration_id IN (:ids) ORDER BY registration_id,generation,event_type",
                    mapOf("ids" to items.map { it.id }),
                    { rs, _ ->
                        FieldVisitEvent(
                            rs.getObject("registration_id", UUID::class.java),
                            rs.getLong("generation"),
                            rs.getString("event_type"),
                            rs.getTimestamp("occurred_at").toInstant(),
                            rs.getObject("actor_participant_id", Long::class.javaObjectType),
                            rs.getObject("operation_id", UUID::class.java),
                        )
                    },
                )
            }
        return FieldVisitSourcePage(
            items,
            events,
            if (all.size >
                query.size
            ) {
                items.last().id
            } else {
                null
            },
        )
    }

    private val row =
        RowMapper { rs, _ ->
            FieldVisitRegistration(
                rs.getObject("id", UUID::class.java),
                rs.getObject("expo_id", UUID::class.java),
                rs.getLong("participant_id"),
                rs.getObject("visit_date", LocalDate::class.java),
                rs.getString("status"),
                rs.getLong("generation"),
                rs.getTimestamp("registered_at").toInstant(),
                rs.getTimestamp("cancelled_at")?.toInstant(),
                rs.getObject("cancelled_by", Long::class.javaObjectType),
                rs.getBoolean("cancellation_pending"),
                rs.getBoolean("delivery_pending"),
            )
        }

    fun findById(id: UUID): FieldVisitRegistration? =
        jdbc.query("SELECT * FROM tb_field_visit_registration WHERE id = ?", row, id).singleOrNull()

    fun lock(id: UUID): FieldVisitRegistration? =
        jdbc.query("SELECT * FROM tb_field_visit_registration WHERE id = ? FOR UPDATE", row, id).singleOrNull()

    fun find(
        expoId: UUID,
        participantId: Long,
        visitDate: LocalDate,
    ): FieldVisitRegistration? =
        jdbc
            .query(
                "SELECT * FROM tb_field_visit_registration WHERE expo_id = ? AND participant_id = ? AND visit_date = ?",
                row,
                expoId,
                participantId,
                visitDate,
            ).singleOrNull()

    fun findToday(
        expoId: UUID,
        participantIds: Collection<Long>,
        visitDate: LocalDate,
    ): Map<Long, FieldVisitRegistration> {
        if (participantIds.isEmpty()) return emptyMap()
        return jdbc
            .query(
                "SELECT * FROM tb_field_visit_registration WHERE expo_id=? AND participant_id=ANY(?) AND visit_date=?",
                org.springframework.jdbc.core.PreparedStatementSetter { ps ->
                    ps.setObject(1, expoId)
                    ps.setArray(2, ps.connection.createArrayOf("bigint", participantIds.toTypedArray()))
                    ps.setObject(3, visitDate)
                },
                row,
            ).associateBy { it.participantId }
    }

    fun findByParticipants(
        expoId: UUID,
        participantIds: Collection<Long>,
    ): List<FieldVisitRegistration> =
        participantIds.distinct().sorted().flatMap { participantId ->
            jdbc.query(
                "SELECT * FROM tb_field_visit_registration WHERE expo_id = ? AND participant_id = ? ORDER BY visit_date, id",
                row,
                expoId,
                participantId,
            )
        }

    fun preregistered(
        expoId: UUID,
        participantId: Long,
        visitDate: LocalDate,
    ): Boolean =
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM tb_preregister_application a JOIN tb_preregister_session_state s " +
                "ON s.expo_id = a.expo_id AND s.session_id = a.session_id " +
                "WHERE a.expo_id = ? AND a.participant_id = ? AND a.status = 'CONFIRMED' " +
                "AND (CASE WHEN s.definition::jsonb ->> 'startedAt' ~ '(Z|[+-][0-9]{2}:[0-9]{2})$' " +
                "THEN ((s.definition::jsonb ->> 'startedAt')::timestamptz AT TIME ZONE 'Asia/Seoul')::date " +
                "ELSE (s.definition::jsonb ->> 'startedAt')::timestamp::date END) = ?)",
            Boolean::class.java,
            expoId,
            participantId,
            visitDate,
        ) == true

    fun activate(
        expoId: UUID,
        participantId: Long,
        visitDate: LocalDate,
        operationId: UUID,
    ): FieldVisitRegistration {
        jdbc.queryForObject(
            "SELECT CAST(pg_advisory_xact_lock(74, hashtext(?)) AS text)",
            String::class.java,
            "$expoId:$participantId:$visitDate",
        )
        val existing = find(expoId, participantId, visitDate)?.let { lock(it.id) }
        check(existing?.cancellationPending != true) { "FIELD_CANCELLATION_PENDING" }
        if (existing?.status == "ACTIVE") return existing
        val id = existing?.id ?: UUID.randomUUID()
        val generation = (existing?.generation ?: 0) + 1
        jdbc.update(
            "INSERT INTO tb_field_visit_registration(id,expo_id,participant_id,visit_date,status,generation,registered_at) " +
                "VALUES (?,?,?,?,'ACTIVE',?,CURRENT_TIMESTAMP) ON CONFLICT (expo_id,participant_id,visit_date) DO UPDATE " +
                "SET status='ACTIVE', generation=EXCLUDED.generation, registered_at=CURRENT_TIMESTAMP, " +
                "cancelled_at=NULL,cancelled_by=NULL,delivery_pending=true",
            id,
            expoId,
            participantId,
            visitDate,
            generation,
        )
        jdbc.update(
            "INSERT INTO tb_field_visit_event(operation_id,registration_id,generation,event_type) VALUES (?,?,?,'REGISTERED')",
            operationId,
            id,
            generation,
        )
        return requireNotNull(findById(id))
    }

    fun delivered(
        id: UUID,
        generation: Long,
    ) {
        jdbc.update(
            "UPDATE tb_field_visit_registration SET delivery_pending=false WHERE id=? AND generation=? AND status='ACTIVE'",
            id,
            generation,
        )
    }

    fun beginCancellation(
        id: UUID,
        generation: Long,
        operationId: UUID,
    ): Boolean =
        jdbc.update(
            "UPDATE tb_field_visit_registration SET cancellation_pending = true, cancellation_operation_id = ? " +
                "WHERE id = ? AND generation = ? AND status = 'ACTIVE' AND NOT delivery_pending " +
                "AND (NOT cancellation_pending OR cancellation_operation_id = ?)",
            operationId,
            id,
            generation,
            operationId,
        ) == 1

    fun clearCancellation(
        id: UUID,
        generation: Long,
        operationId: UUID,
    ): Boolean =
        jdbc.update(
            "UPDATE tb_field_visit_registration SET cancellation_pending = false, cancellation_operation_id = NULL " +
                "WHERE id = ? AND generation = ? AND cancellation_operation_id = ?",
            id,
            generation,
            operationId,
        ) == 1

    fun cancel(
        id: UUID,
        generation: Long,
        actorParticipantId: Long,
        operationId: UUID,
    ): Boolean {
        val changed =
            jdbc.update(
                "UPDATE tb_field_visit_registration SET status = 'CANCELLED', cancelled_at = CURRENT_TIMESTAMP, cancelled_by = ? " +
                    "WHERE id = ? AND generation = ? AND status = 'ACTIVE' AND cancellation_pending AND cancellation_operation_id = ?",
                actorParticipantId,
                id,
                generation,
                operationId,
            ) == 1
        if (changed) {
            jdbc.update(
                "INSERT INTO tb_field_visit_event(operation_id, registration_id, generation, event_type, actor_participant_id) " +
                    "VALUES (?, ?, ?, 'CANCELLED', ?)",
                operationId,
                id,
                generation,
                actorParticipantId,
            )
        }
        return changed
    }
}
