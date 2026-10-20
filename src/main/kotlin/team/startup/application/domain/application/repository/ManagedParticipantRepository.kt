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
    ): Boolean =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM tb_preregister_application WHERE expo_id = ? AND participant_id = ? AND status = 'CONFIRMED')",
                Boolean::class.java,
                expoId,
                participantId,
            ),
        )

    fun confirmedRepresentatives(
        expoId: UUID,
        participantId: Long,
    ): List<Long> =
        jdbc.query(
            "SELECT DISTINCT representative_id FROM tb_preregister_application a WHERE expo_id = ? AND participant_id = ? " +
                "AND status = 'CONFIRMED' AND NOT EXISTS(" +
                "SELECT 1 FROM tb_owner_cancellation_delivery d WHERE d.expo_id = a.expo_id " +
                "AND d.application_type = 'PREREGISTER' AND d.application_id = a.id::text AND d.action <> 'ABORT')",
            { rs, _ -> rs.getLong(1) },
            expoId,
            participantId,
        )

    // fieldRegistered(User가 FIELD로 저장한 참가자)는 사전등록 이력이 하나도 없을 때만 확정 사전등록 없이 허용한다(#250).
    // 사전등록 취소 뒤 현장 재등록한 참가자는 #249에서 따로 정한다
    fun qrAvailable(
        expoId: UUID,
        participantId: Long,
        representativeId: Long,
        fieldRegistered: Boolean = false,
    ): Boolean =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM tb_preregister_application a WHERE expo_id = ? AND participant_id = ? " +
                    "AND representative_id = ? AND status = 'CONFIRMED' AND NOT EXISTS(" +
                    "SELECT 1 FROM tb_owner_cancellation_delivery d WHERE d.expo_id = a.expo_id " +
                    "AND d.application_type = 'PREREGISTER' AND d.application_id = a.id::text AND d.action <> 'ABORT')) " +
                    "OR (? AND NOT EXISTS(SELECT 1 FROM tb_preregister_application WHERE expo_id = ? AND participant_id = ?))",
                Boolean::class.java,
                expoId,
                participantId,
                representativeId,
                fieldRegistered,
                expoId,
                participantId,
            ),
        )
}
