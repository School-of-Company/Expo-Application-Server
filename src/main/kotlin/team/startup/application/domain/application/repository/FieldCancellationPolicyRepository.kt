package team.startup.application.domain.application.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import team.startup.application.domain.application.presentation.dto.FieldVisitRegistration
import team.startup.application.domain.application.presentation.dto.OwnerApplicationParty
import team.startup.application.domain.application.time.ExpoProgramTime
import java.time.LocalDate
import java.time.ZoneId

@Repository
class FieldCancellationPolicyRepository(
    private val jdbc: JdbcTemplate,
) {
    fun blockedReason(
        visit: FieldVisitRegistration,
        party: OwnerApplicationParty,
        traineeId: Long?,
    ): String? {
        val programs =
            jdbc.query(
                "SELECT definition_snapshot->>'startedAt' FROM tb_program_session_application " +
                    "WHERE expo_id = ? AND participant_id = ? AND status IN ('CONFIRMED', 'WAITING')",
                { rs, _ -> rs.getString(1) },
                visit.expoId,
                visit.participantId,
            )
        if (programs.any { onDateOrUnknown(it, visit.visitDate) }) return "ACTIVE_PROGRAMS"
        val legacy =
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM tb_standard_program_application WHERE participant_id = ?) OR " +
                    "EXISTS(SELECT 1 FROM tb_training_program_application WHERE trainee_id = ?)",
                Boolean::class.java,
                visit.participantId,
                traineeId,
            ) == true
        if (legacy) return "ACTIVE_PROGRAMS"
        if (visit.participantId != party.representativeId) return null
        val companions =
            party.participants
                .filter { it.participantId != visit.participantId }
                .map { it.participantId }
                .toSet()
        if (companions.isEmpty()) return null
        val fields =
            jdbc.query(
                "SELECT participant_id FROM tb_field_visit_registration WHERE expo_id = ? AND visit_date = ? AND status = 'ACTIVE'",
                { rs, _ -> rs.getLong(1) },
                visit.expoId,
                visit.visitDate,
            )
        if (fields.any { it in companions }) return "COMPANIONS_ACTIVE"
        val preregistrations =
            jdbc.query(
                "SELECT a.participant_id, s.definition::jsonb->>'startedAt' FROM tb_preregister_application a " +
                    "JOIN tb_preregister_session_state s ON s.expo_id = a.expo_id AND s.session_id = a.session_id " +
                    "WHERE a.expo_id = ? AND a.representative_id = ? AND a.participant_id <> ? AND a.status = 'CONFIRMED'",
                { rs, _ -> rs.getLong(1) to rs.getString(2) },
                visit.expoId,
                visit.participantId,
                visit.participantId,
            )
        if (preregistrations.any { it.first in companions && onDateOrUnknown(it.second, visit.visitDate) }) return "COMPANIONS_ACTIVE"
        return null
    }

    private fun onDateOrUnknown(
        value: String?,
        date: LocalDate,
    ): Boolean =
        value == null ||
            runCatching { ExpoProgramTime.parse(value).atZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDate() == date }.getOrDefault(true)
}
