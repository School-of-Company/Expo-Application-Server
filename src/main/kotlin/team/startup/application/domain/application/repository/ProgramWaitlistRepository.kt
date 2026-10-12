package team.startup.application.domain.application.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import team.startup.application.domain.application.exception.ProgramApplicationConflictException
import team.startup.application.domain.application.presentation.dto.ProgramSessionDefinition
import team.startup.application.domain.application.presentation.dto.ProgramSessionKey
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class ProgramWaitlistRepository(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
) {
    data class Entry(
        val id: UUID,
        val participantId: Long,
        val traineeId: Long?,
        val status: String,
        val key: ProgramSessionKey,
        val questions: JsonNode,
        val answers: JsonNode,
    )

    private val entryMapper =
        RowMapper { rs, _ ->
            Entry(
                rs.getObject("id", UUID::class.java),
                rs.getLong("participant_id"),
                rs.getLong("trainee_id").let { if (rs.wasNull()) null else it },
                rs.getString("status"),
                ProgramSessionKey(
                    rs.getObject("expo_id", UUID::class.java),
                    ProgramSessionType.valueOf(rs.getString("program_type")),
                    rs.getLong("program_id"),
                ),
                mapper.readTree(rs.getString("questions_snapshot")),
                mapper.readTree(rs.getString("answers_snapshot")),
            )
        }

    fun entry(id: UUID): Entry? =
        jdbc
            .query(
                "SELECT id, participant_id, trainee_id, status, expo_id, program_type, program_id, questions_snapshot, answers_snapshot " +
                    "FROM tb_program_session_application WHERE id = ?",
                entryMapper,
                id,
            ).singleOrNull()

    fun waiting(
        definition: ProgramSessionDefinition,
        limit: Int,
    ): List<Entry> =
        jdbc.query(
            "SELECT id, participant_id, trainee_id, status, expo_id, program_type, program_id, questions_snapshot, answers_snapshot " +
                "FROM tb_program_session_application " +
                "WHERE expo_id = ? AND program_type = ? AND program_id = ? AND status = 'WAITING' ORDER BY created_at, id LIMIT ?",
            entryMapper,
            UUID.fromString(definition.expoId),
            definition.programType.name,
            definition.id,
            limit,
        )

    fun waitingCount(definition: ProgramSessionDefinition): Int =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT count(*) FROM tb_program_session_application WHERE expo_id = ? AND program_type = ? AND program_id = ? AND status = 'WAITING'",
                Int::class.java,
                UUID.fromString(definition.expoId),
                definition.programType.name,
                definition.id,
            ),
        )

    fun observe(definition: ProgramSessionDefinition) {
        jdbc.update(
            "INSERT INTO tb_program_session_state (expo_id, program_type, program_id, definition) VALUES (?, ?, ?, CAST(? AS jsonb)) ON CONFLICT DO NOTHING",
            UUID.fromString(definition.expoId),
            definition.programType.name,
            definition.id,
            mapper.writeValueAsString(definition),
        )
        val state =
            jdbc
                .query(
                    "SELECT definition, pending_definition, deleted FROM tb_program_session_state WHERE expo_id = ? AND program_type = ? AND program_id = ?",
                    {
                        rs,
                        _,
                        ->
                        Triple(
                            mapper.readValue(rs.getString(1), ProgramSessionDefinition::class.java),
                            rs.getString(2)?.let {
                                mapper.readValue(it, ProgramSessionDefinition::class.java)
                            },
                            rs.getBoolean(3),
                        )
                    },
                    UUID.fromString(definition.expoId),
                    definition.programType.name,
                    definition.id,
                ).single()
        if (state.third) conflict("삭제된 프로그램입니다.")
        if (definition.pendingRevision != null) conflict("프로그램 정책 변경 중입니다.")
        if (state.second == null && state.first.policy == null && definition.policy == null) {
            if (definition.revision < state.first.revision) conflict("프로그램 정의가 변경되었습니다.")
            if (definition != state.first) {
                jdbc.update(
                    "UPDATE tb_program_session_state SET definition = CAST(? AS jsonb) WHERE expo_id = ? AND program_type = ? AND program_id = ?",
                    mapper.writeValueAsString(definition),
                    UUID.fromString(definition.expoId),
                    definition.programType.name,
                    definition.id,
                )
            }
            return
        }
        val expected = state.second ?: state.first
        if (expected != definition) conflict("프로그램 정책 변경 중이거나 승인되지 않은 정의입니다.")
        if (state.second !=
            null
        ) {
            jdbc.update(
                "UPDATE tb_program_session_state SET definition = pending_definition, pending_definition = NULL, pending_change_id = NULL WHERE expo_id = ? AND program_type = ? AND program_id = ?",
                UUID.fromString(definition.expoId),
                definition.programType.name,
                definition.id,
            )
        }
    }

    fun stage(
        previous: ProgramSessionDefinition,
        desired: ProgramSessionDefinition?,
        changeId: UUID,
    ) {
        observe(previous)
        jdbc.update(
            "UPDATE tb_program_session_state SET pending_definition = CAST(? AS jsonb), pending_change_id = ?, deleted = ? WHERE expo_id = ? AND program_type = ? AND program_id = ?",
            desired?.let(mapper::writeValueAsString),
            changeId,
            desired == null,
            UUID.fromString(previous.expoId),
            previous.programType.name,
            previous.id,
        )
    }

    fun changeReplay(
        expoId: UUID,
        changeId: UUID,
        payload: String,
    ): Boolean {
        jdbc
            .query(
                "SELECT payload = CAST(? AS jsonb) FROM tb_program_change_receipt WHERE expo_id = ? AND change_id = ?",
                { rs, _ -> rs.getBoolean(1) },
                payload,
                expoId,
                changeId,
            ).singleOrNull()
            ?.let {
                if (!it) conflict("같은 변경 ID에 다른 정의가 있습니다.")
                return true
            }
        return false
    }

    fun lockChange(expoId: UUID) {
        jdbc.queryForObject("SELECT CAST(pg_advisory_xact_lock(45, hashtext(?)) AS text)", String::class.java, expoId.toString())
    }

    fun saveChange(
        expoId: UUID,
        changeId: UUID,
        payload: String,
    ) {
        jdbc.update("INSERT INTO tb_program_change_receipt VALUES (?, ?, CAST(? AS jsonb))", expoId, changeId, payload)
    }

    fun transition(
        id: UUID,
        status: String,
        traineeId: Long? = null,
        actorId: Long? = null,
    ) {
        jdbc.update(
            "UPDATE tb_program_session_application SET status = ?, trainee_id = COALESCE(?, trainee_id) WHERE id = ?",
            status,
            traineeId,
            id,
        )
        jdbc.update("INSERT INTO tb_program_session_history (application_id, status, actor_id) VALUES (?, ?, ?)", id, status, actorId)
        event(id, status)
    }

    fun event(
        id: UUID,
        status: String,
    ) {
        jdbc.update("INSERT INTO tb_program_session_event (application_id, status) VALUES (?, ?) ON CONFLICT DO NOTHING", id, status)
    }

    fun activeCount(definition: ProgramSessionDefinition): Int =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT count(*) FROM tb_program_session_application WHERE expo_id = ? AND program_type = ? AND program_id = ? AND status <> 'CANCELLED'",
                Int::class.java,
                UUID.fromString(definition.expoId),
                definition.programType.name,
                definition.id,
            ),
        )

    fun keys(now: Instant): List<ProgramSessionKey> =
        jdbc.query(
            "SELECT s.expo_id, s.program_type, s.program_id FROM tb_program_session_state s WHERE NOT s.deleted " +
                "AND (s.pending_change_id IS NOT NULL OR ((s.definition #>> '{policy,applicationEndedAt}')::timestamptz > ? " +
                "AND (s.definition #>> '{policy,promotionEndedAt}')::timestamptz > ? " +
                "AND EXISTS (SELECT 1 FROM tb_program_session_application a " +
                "WHERE a.expo_id = s.expo_id AND a.program_type = s.program_type AND a.program_id = s.program_id AND a.status = 'WAITING')))",
            { rs, _ -> ProgramSessionKey(rs.getObject(1, UUID::class.java), ProgramSessionType.valueOf(rs.getString(2)), rs.getLong(3)) },
            Timestamp.from(now),
            Timestamp.from(now),
        )

    private fun conflict(message: String): Nothing = throw ProgramApplicationConflictException(message)
}
