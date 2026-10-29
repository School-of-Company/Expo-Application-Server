package team.startup.application.domain.application.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import team.startup.application.domain.application.presentation.dto.PreregisterSessionDefinition
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** QR 문자에 싣는 참가자별 확정 회차와 프로그램 신청(Notification #136). 읽기만 한다. */
@Repository
class ParticipantNoticeRepository(
    jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
) {
    private val named = NamedParameterJdbcTemplate(jdbc)

    data class SessionRow(
        val participantId: Long,
        val definition: PreregisterSessionDefinition,
    )

    data class ProgramRow(
        val participantId: Long,
        val programType: ProgramSessionType,
        val programId: Long,
        val snapshot: JsonNode,
    )

    fun confirmedSessions(
        expoId: UUID,
        participantIds: Collection<Long>,
    ): List<SessionRow> =
        named.query(
            "SELECT a.participant_id, s.definition FROM tb_preregister_application a " +
                "JOIN tb_preregister_session_state s ON s.expo_id = a.expo_id AND s.session_id = a.session_id " +
                "WHERE a.expo_id = :expo AND a.participant_id IN (:participants) AND a.status = 'CONFIRMED' " +
                "ORDER BY a.participant_id, a.id",
            mapOf("expo" to expoId, "participants" to participantIds),
            { rs, _ ->
                SessionRow(rs.getLong(1), mapper.readValue(rs.getString(2), PreregisterSessionDefinition::class.java))
            },
        )

    fun confirmedPrograms(
        expoId: UUID,
        participantIds: Collection<Long>,
    ): List<ProgramRow> =
        named.query(
            "SELECT participant_id, program_type, program_id, definition_snapshot FROM tb_program_session_application " +
                "WHERE expo_id = :expo AND participant_id IN (:participants) AND status = 'CONFIRMED' " +
                "ORDER BY participant_id, created_at, id",
            mapOf("expo" to expoId, "participants" to participantIds),
            { rs, _ ->
                ProgramRow(
                    rs.getLong(1),
                    ProgramSessionType.valueOf(rs.getString(2)),
                    rs.getLong(3),
                    mapper.readTree(rs.getString(4)),
                )
            },
        )
}
