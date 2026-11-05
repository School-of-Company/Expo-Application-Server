package team.startup.application.domain.application.repository

import org.springframework.stereotype.Repository
import team.startup.application.domain.application.entity.FieldCancellationDelivery
import team.startup.application.domain.application.presentation.dto.CancelOwnerApplicationCommand
import team.startup.application.domain.application.presentation.dto.OwnerApplicationType
import team.startup.application.domain.application.service.impl.FieldCancellationTarget
import java.util.UUID

@Repository
class FieldCancellationDeliveryRepository(
    private val rows: FieldCancellationDeliveryJpaRepository,
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

    fun pending(): List<Delivery> = rows.pending().map { it.delivery() }

    fun find(id: UUID): Delivery? = rows.findById(id).orElse(null)?.delivery()

    fun insert(delivery: Delivery) {
        rows.insert(delivery.target, delivery.operationId, delivery.actorParticipantId)
    }

    fun commit(operationId: UUID) {
        check(rows.decide(operationId, "COMMIT") == 1)
    }

    fun abort(operationId: UUID) {
        rows.decide(operationId, "ABORT")
    }

    fun delete(operationId: UUID) {
        rows.deleteOperation(operationId)
    }

    fun defer(operationId: UUID) {
        rows.defer(operationId)
    }

    private fun FieldCancellationDelivery.delivery() =
        Delivery(
            FieldCancellationTarget(registrationId, expoId, participantId, visitDate, generation),
            operationId,
            actorParticipantId,
            action,
        )
}
