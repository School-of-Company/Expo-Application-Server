package team.startup.application.domain.application.presentation.dto

import java.util.UUID

data class GetOwnerCancellationStatusCommand(
    val expoId: UUID,
    val sessionId: String,
    val participantId: Long,
) {
    override fun toString() = "GetOwnerCancellationStatusCommand(expoId=$expoId, participantId=$participantId)"
}

// A point-in-time User read, not a cancellation permit.
data class OwnerCancellationStatus(
    val participantId: Long,
    val representative: Boolean,
    val entered: Boolean,
    val traineeLinked: Boolean,
    val linkedTraineeId: Long?,
)
