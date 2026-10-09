package team.startup.application.domain.application.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import team.startup.application.domain.application.presentation.dto.PreregisterApplicationReceipt
import team.startup.application.domain.application.presentation.dto.PreregisterSessionChangeRequest
import team.startup.application.domain.application.presentation.dto.PreregisterSessionDefinition
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Repository
class PreregisterSessionRepository(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
) {
    private val namedJdbc = NamedParameterJdbcTemplate(jdbc)

    fun lock(expoId: UUID) {
        jdbc.queryForObject("SELECT CAST(pg_advisory_xact_lock(34, hashtext(?)) AS text)", String::class.java, expoId.toString())
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
                "SELECT EXISTS(SELECT 1 FROM tb_preregister_application WHERE expo_id = ? AND session_id = ?)",
                Boolean::class.java,
                expoId,
                sessionId,
            ),
        )

    fun applications(
        expoId: UUID,
        sessionId: Long,
    ): List<PreregisterApplicationReceipt> =
        jdbc.query(
            "SELECT id, participant_id, session_id, status FROM tb_preregister_application WHERE expo_id = ? AND session_id = ? ORDER BY id",
            { rs, _ -> PreregisterApplicationReceipt(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4)) },
            expoId,
            sessionId,
        )

    fun active(
        expoId: UUID,
        participantId: Long,
    ): PreregisterApplicationReceipt? = activeOwned(expoId, participantId)?.first

    fun activeOwned(
        expoId: UUID,
        participantId: Long,
    ): Pair<PreregisterApplicationReceipt, Long>? =
        jdbc
            .query(
                "SELECT id, participant_id, session_id, status, representative_id FROM tb_preregister_application " +
                    "WHERE expo_id = ? AND participant_id = ? AND status <> 'CANCELLED'",
                { rs, _ -> PreregisterApplicationReceipt(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4)) to rs.getLong(5) },
                expoId,
                participantId,
            ).singleOrNull()

    fun insert(
        expoId: UUID,
        sessionId: Long,
        representativeId: Long,
        participantId: Long,
        status: String,
    ): PreregisterApplicationReceipt {
        val id =
            requireNotNull(
                jdbc.queryForObject(
                    "INSERT INTO tb_preregister_application (expo_id, session_id, representative_id, participant_id, status) " +
                        "VALUES (?, ?, ?, ?, ?) RETURNING id",
                    Long::class.java,
                    expoId,
                    sessionId,
                    representativeId,
                    participantId,
                    status,
                ),
            )
        transition(id)
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

    fun counts(
        expoId: UUID,
        sessionId: Long,
    ): Pair<Long, Long> =
        jdbc
            .query(
                "SELECT count(*) FILTER (WHERE status = 'CONFIRMED'), count(*) FILTER (WHERE status = 'WAITING') " +
                    "FROM tb_preregister_application WHERE expo_id = ? AND session_id = ? AND status <> 'CANCELLED'",
                { rs, _ -> rs.getLong(1) to rs.getLong(2) },
                expoId,
                sessionId,
            ).single()

    fun waiting(
        expoId: UUID,
        sessionId: Long,
        limit: Int,
    ): List<PreregisterApplicationReceipt> =
        jdbc.query(
            "SELECT id, participant_id, session_id, status FROM tb_preregister_application " +
                "WHERE expo_id = ? AND session_id = ? AND status = 'WAITING' ORDER BY id LIMIT ?",
            { rs, _ -> PreregisterApplicationReceipt(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4)) },
            expoId,
            sessionId,
            limit,
        )

    fun status(
        id: Long,
        status: String,
    ) {
        jdbc.update("UPDATE tb_preregister_application SET status = ? WHERE id = ?", status, id)
        transition(id)
    }

    private fun transition(id: Long) {
        jdbc.update(
            "INSERT INTO tb_preregister_transition (application_id, expo_id, session_id, participant_id, status) " +
                "SELECT id, expo_id, session_id, participant_id, status FROM tb_preregister_application WHERE id = ?",
            id,
        )
    }

    fun setAutoPromote(
        expoId: UUID,
        sessionId: Long,
        enabled: Boolean,
    ) {
        jdbc.update(
            "UPDATE tb_preregister_session_state SET auto_promote = ? WHERE expo_id = ? AND session_id = ?",
            enabled,
            expoId,
            sessionId,
        )
    }

    fun autoPromote(
        expoId: UUID,
        sessionId: Long,
    ): Boolean =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT auto_promote FROM tb_preregister_session_state WHERE expo_id = ? AND session_id = ?",
                Boolean::class.java,
                expoId,
                sessionId,
            ),
        )

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
        jdbc.update("INSERT INTO tb_preregister_deleted_expo (expo_id) VALUES (?) ON CONFLICT DO NOTHING", expoId)
        listOf(
            "tb_preregister_transition",
            "tb_preregister_request",
            "tb_preregister_application",
            "tb_preregister_session_change",
            "tb_preregister_session_state",
        ).forEach {
            jdbc.update("DELETE FROM $it WHERE expo_id = ?", expoId)
        }
    }
}
