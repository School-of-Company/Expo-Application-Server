package team.startup.application.domain.application.presentation.dto

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class FieldVisitRegistration(
    val id: UUID,
    val expoId: UUID,
    val participantId: Long,
    val visitDate: LocalDate,
    val status: String,
    val generation: Long,
    val registeredAt: Instant,
    val cancelledAt: Instant?,
    val cancelledBy: Long?,
    val cancellationPending: Boolean,
    val deliveryPending: Boolean = false,
)
