package team.startup.application.domain.application.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ManagedParticipantRepository(
    private val jdbc: JdbcTemplate,
    @org.springframework.beans.factory.annotation.Value("\${application.field-visits.enabled:false}") private val fieldVisitsEnabled:
        Boolean = false,
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

    // fieldRegistered(User가 FIELD로 저장한 참가자)는 확정 사전등록 없이 허용한다(#250). 사전등록 이력이 있어도 현장 재등록을 이 서비스가
    // 허용해야 User가 FIELD로 바꾸므로(#261) 이력은 보지 않는다. 다만 사전등록을 취소한 참가자는 취소 표시(#297)가 있는 동안 QR을 닫고,
    // 현장 재등록 요청이 처리되면 표시를 지워 다시 연다. 표시는 fieldRegistered일 때만 본다. 입장 가능 여부는 Attendance가 다시 판정한다.
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
                    "OR CASE WHEN ? THEN EXISTS(SELECT 1 FROM tb_field_visit_registration " +
                    "WHERE expo_id=? AND participant_id=? AND visit_date=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')::date " +
                    "AND status='ACTIVE' AND NOT cancellation_pending AND NOT delivery_pending) " +
                    "ELSE ? AND NOT EXISTS(SELECT 1 FROM tb_field_qr_revoked r WHERE r.expo_id=? AND r.participant_id=?) END",
                Boolean::class.java,
                expoId,
                participantId,
                representativeId,
                fieldVisitsEnabled,
                expoId,
                participantId,
                fieldRegistered,
                expoId,
                participantId,
            ),
        )

    // #297: 사전등록 취소가 확정되는 트랜잭션에서 남긴다. fieldRegistered가 아닌 참가자에게는 판정에 쓰이지 않는다.
    fun revokeFieldQr(
        expoId: UUID,
        participantId: Long,
    ) {
        jdbc.update(
            "INSERT INTO tb_field_qr_revoked (expo_id, participant_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
            expoId,
            participantId,
        )
    }

    // #297: 현장 재등록 요청이 처리된 참가자의 취소 표시를 지운다.
    fun restoreFieldQr(
        expoId: UUID,
        participantIds: List<Long>,
    ) {
        participantIds.forEach {
            jdbc.update("DELETE FROM tb_field_qr_revoked WHERE expo_id = ? AND participant_id = ?", expoId, it)
        }
    }
}
