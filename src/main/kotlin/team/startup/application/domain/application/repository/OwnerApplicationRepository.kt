package team.startup.application.domain.application.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import team.startup.application.domain.application.presentation.dto.CancelOwnerApplicationCommand
import team.startup.application.domain.application.presentation.dto.OwnerApplicationHistory
import team.startup.application.domain.application.presentation.dto.OwnerApplicationItem
import team.startup.application.domain.application.presentation.dto.OwnerApplicationParty
import team.startup.application.domain.application.presentation.dto.OwnerApplicationType
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.util.UUID

@Repository
class OwnerApplicationRepository(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
) {
    private val named = NamedParameterJdbcTemplate(jdbc)

    data class CancellationTarget(
        val applicationId: String,
        val participantId: Long,
        val sessionOrProgramId: Long,
        val status: String,
        val traineeId: Long? = null,
    )

    fun cancellationTarget(
        command: CancelOwnerApplicationCommand,
        party: OwnerApplicationParty,
    ): CancellationTarget? {
        if (party.participants.isEmpty()) return null
        val isPreregister = command.type == OwnerApplicationType.PREREGISTER
        val id: Any =
            if (isPreregister) {
                command.applicationId.toLongOrNull()?.takeIf { it > 0 } ?: return null
            } else {
                try {
                    UUID.fromString(command.applicationId)
                } catch (_: IllegalArgumentException) {
                    return null
                }
            }
        val sql =
            if (isPreregister) {
                "SELECT id, participant_id, session_id AS program_id, status, NULL::bigint AS trainee_id FROM tb_preregister_application " +
                    "WHERE id = :id AND expo_id = :expo AND representative_id = :representative AND participant_id IN (:participants)"
            } else {
                "SELECT id, participant_id, program_id, status, trainee_id FROM tb_program_session_application " +
                    "WHERE id = :id AND expo_id = :expo AND program_type = :type AND participant_id IN (:participants)"
            }
        return named
            .query(
                sql,
                mapOf(
                    "id" to id,
                    "expo" to command.expoId,
                    "type" to command.type.name,
                    "representative" to party.representativeId,
                    "participants" to party.participants.map { it.participantId },
                ),
                {
                    rs,
                    _,
                    ->
                    CancellationTarget(
                        rs.getString("id"),
                        rs.getLong("participant_id"),
                        rs.getLong("program_id"),
                        rs.getString("status"),
                        rs.getObject("trainee_id", Long::class.javaObjectType),
                    )
                },
            ).singleOrNull()
    }

    fun applications(
        expoId: UUID,
        party: OwnerApplicationParty,
    ): List<OwnerApplicationItem> {
        if (party.participants.isEmpty()) return emptyList()
        val parameters =
            mapOf(
                "expo" to expoId,
                "representative" to party.representativeId,
                "participants" to party.participants.map { it.participantId },
            )
        val preregister =
            named.query(
                "SELECT a.id::text, a.participant_id, a.session_id AS program_id, a.status, NULL::text AS snapshot_place, " +
                    "r.user_request::jsonb -> 'questions' AS questions, r.user_request::jsonb ->> 'informationJson' AS answers, " +
                    "(SELECT jsonb_agg(t.status ORDER BY t.id) FROM tb_preregister_transition t " +
                    "WHERE t.application_id = a.id) AS history " +
                    "FROM tb_preregister_application a LEFT JOIN tb_preregister_reservation r " +
                    "ON r.expo_id = a.expo_id AND r.request_id = a.reservation_request_id " +
                    "WHERE a.expo_id = :expo AND a.representative_id = :representative " +
                    "AND a.participant_id IN (:participants) ORDER BY a.id",
                parameters,
                { rs, _ -> item(rs, OwnerApplicationType.PREREGISTER) },
            )
        val programs =
            named.query(
                "SELECT a.id::text, a.participant_id, a.program_id, a.program_type, a.status, " +
                    "a.definition_snapshot->>'place' AS snapshot_place, " +
                    "a.questions_snapshot AS questions, a.answers_snapshot AS answers, " +
                    "(SELECT jsonb_agg(h.status ORDER BY h.id) FROM tb_program_session_history h " +
                    "WHERE h.application_id = a.id) AS history " +
                    "FROM tb_program_session_application a WHERE a.expo_id = :expo " +
                    "AND a.participant_id IN (:participants) ORDER BY a.created_at, a.id",
                parameters,
                { rs, _ -> item(rs, OwnerApplicationType.valueOf(rs.getString("program_type"))) },
            )
        return preregister + programs
    }

    private fun item(
        rs: ResultSet,
        type: OwnerApplicationType,
    ): OwnerApplicationItem {
        val status = rs.getString("status")
        return OwnerApplicationItem(
            rs.getString("id"),
            type,
            rs.getLong("participant_id"),
            rs.getLong("program_id"),
            status,
            status == "CONFIRMED" || status == "WAITING",
            rs.getString("questions")?.let(mapper::readTree),
            rs.getString("answers")?.let(mapper::readTree),
            rs.getString("history")?.let { json ->
                val nodes = mapper.readTree(json)
                (0 until nodes.size()).map { OwnerApplicationHistory(nodes.get(it).stringValue()) }
            } ?: emptyList(),
            cancellationBlockedReason = if (status == "CANCELLED") "CANCELLED" else "EXTERNAL_CONTRACT_REQUIRED",
            snapshotPlace = rs.getString("snapshot_place")?.takeIf { it.isNotBlank() },
        )
    }

    fun activePrograms(
        expoId: UUID,
        participantId: Long,
        traineeId: Long?,
    ): Boolean =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM tb_program_session_application WHERE expo_id = ? AND participant_id = ? " +
                    "AND status IN ('CONFIRMED', 'WAITING')) OR " +
                    "EXISTS(SELECT 1 FROM tb_standard_program_application WHERE participant_id = ?) OR " +
                    "EXISTS(SELECT 1 FROM tb_training_program_application WHERE trainee_id = ?)",
                Boolean::class.java,
                expoId,
                participantId,
                participantId,
                traineeId,
            ),
        )
}
