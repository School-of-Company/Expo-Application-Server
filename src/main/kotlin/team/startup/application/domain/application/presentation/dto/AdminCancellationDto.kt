package team.startup.application.domain.application.presentation.dto

import java.util.UUID

data class CancelAdminApplicationCommand(
    val expoId: UUID,
    val applicationId: UUID,
    val authorization: String?,
)

data class AdminCancellationResult(
    val applicationId: UUID,
    val status: String = "CANCELLED",
)

data class AdminCancellationParticipantStatus(
    val participantId: Long,
    val entered: Boolean,
    val traineeLinked: Boolean,
    val linkedTraineeId: Long?,
)
