package team.startup.application.domain.application.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "tb_field_cancellation_delivery")
class FieldCancellationDelivery(
    @field:Id
    @field:Column(name = "registration_id", nullable = false)
    val registrationId: UUID,
    @field:Column(name = "expo_id", nullable = false)
    val expoId: UUID,
    @field:Column(name = "participant_id", nullable = false)
    val participantId: Long,
    @field:Column(name = "visit_date", nullable = false)
    val visitDate: LocalDate,
    @field:Column(nullable = false)
    val generation: Long,
    @field:Column(name = "operation_id", nullable = false, unique = true)
    val operationId: UUID,
    @field:Column(name = "actor_participant_id", nullable = false)
    val actorParticipantId: Long,
    @field:Column(nullable = false, length = 16)
    val action: String,
    @field:Column(name = "next_attempt_at", nullable = false)
    val nextAttemptAt: Instant,
)
