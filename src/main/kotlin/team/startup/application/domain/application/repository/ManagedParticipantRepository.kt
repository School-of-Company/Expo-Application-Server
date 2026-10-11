package team.startup.application.domain.application.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ManagedParticipantRepository(
    private val jdbc: JdbcTemplate,
) {
    fun confirmed(
        expoId: UUID,
        participantId: Long,
        representativeId: Long,
    ): Boolean =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM tb_preregister_application a WHERE expo_id = ? AND participant_id = ? " +
                    "AND representative_id = ? AND status = 'CONFIRMED' AND NOT EXISTS(" +
                    "SELECT 1 FROM tb_owner_cancellation_delivery d WHERE d.expo_id = a.expo_id " +
                    "AND d.application_type = 'PREREGISTER' AND d.application_id = a.id::text))",
                Boolean::class.java,
                expoId,
                participantId,
                representativeId,
            ),
        )
}
