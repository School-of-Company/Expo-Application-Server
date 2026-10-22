package team.startup.application.domain.application.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import team.startup.application.domain.application.presentation.dto.CancelOwnerApplicationCommand
import team.startup.application.domain.application.presentation.dto.OwnerApplicationType
import team.startup.application.domain.application.service.impl.FieldCancellationTarget
import java.time.LocalDate
import java.util.UUID

@Repository
class FieldCancellationDeliveryRepository(
    private val jdbc: JdbcTemplate,
    private val locks: OwnerCancellationDeliveryRepository,
) {
    data class Delivery(
        val target: FieldCancellationTarget,
        val operationId: UUID,
        val actorParticipantId: Long,
        val action: String,
    )

    fun lock(
        expoId: UUID,
        id: UUID,
    ): AutoCloseable = locks.lock(CancelOwnerApplicationCommand(expoId, "", OwnerApplicationType.FIELD, id.toString()))

    fun pending(): List<Delivery> =
        jdbc.query(
            "SELECT * FROM tb_field_cancellation_delivery WHERE next_attempt_at <= now() ORDER BY next_attempt_at LIMIT 100",
        ) { rs, _ ->
            Delivery(
                FieldCancellationTarget(
                    rs.getObject("registration_id", UUID::class.java),
                    rs.getObject("expo_id", UUID::class.java),
                    rs.getLong("participant_id"),
                    rs.getObject("visit_date", LocalDate::class.java),
                    rs.getLong("generation"),
                ),
                rs.getObject("operation_id", UUID::class.java),
                rs.getLong("actor_participant_id"),
                rs.getString("action"),
            )
        }

    fun find(id: UUID): Delivery? =
        jdbc
            .query("SELECT * FROM tb_field_cancellation_delivery WHERE registration_id = ?", { rs, _ ->
                Delivery(
                    FieldCancellationTarget(
                        id,
                        rs.getObject("expo_id", UUID::class.java),
                        rs.getLong("participant_id"),
                        rs.getObject("visit_date", LocalDate::class.java),
                        rs.getLong("generation"),
                    ),
                    rs.getObject("operation_id", UUID::class.java),
                    rs.getLong("actor_participant_id"),
                    rs.getString("action"),
                )
            }, id)
            .singleOrNull()

    fun insert(delivery: Delivery) {
        val t = delivery.target
        jdbc.update(
            "INSERT INTO tb_field_cancellation_delivery " +
                "(registration_id,expo_id,participant_id,visit_date,generation,operation_id,actor_participant_id,action) VALUES (?,?,?,?,?,?,?, 'ACQUIRING')",
            t.registrationId,
            t.expoId,
            t.participantId,
            t.visitDate,
            t.generation,
            delivery.operationId,
            delivery.actorParticipantId,
        )
    }

    fun commit(operationId: UUID) {
        check(
            jdbc.update(
                "UPDATE tb_field_cancellation_delivery SET action = 'COMMIT' WHERE operation_id = ? AND action = 'ACQUIRING'",
                operationId,
            ) ==
                1,
        )
    }

    fun abort(operationId: UUID) {
        jdbc.update(
            "UPDATE tb_field_cancellation_delivery SET action = 'ABORT' WHERE operation_id = ? AND action = 'ACQUIRING'",
            operationId,
        )
    }

    fun delete(operationId: UUID) {
        jdbc.update("DELETE FROM tb_field_cancellation_delivery WHERE operation_id = ?", operationId)
    }

    fun defer(operationId: UUID) {
        jdbc.update(
            "UPDATE tb_field_cancellation_delivery SET next_attempt_at = now() + interval '1 minute' WHERE operation_id = ?",
            operationId,
        )
    }
}
