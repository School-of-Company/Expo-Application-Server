package team.startup.application.domain.application.presentation.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.util.UUID

data class LegacyTraineeAttributionRequest(
    @field:Positive val traineeId: Long,
    @field:Positive val participantId: Long,
    @field:NotBlank @field:Size(max = 500) val reason: String,
)

data class LegacyTraineeAttributionResult(
    val traineeId: Long,
    val participantId: Long?,
    val changed: Boolean,
)

data class ChangeLegacyTraineeAttributionCommand(
    val expoId: UUID,
    val operationId: UUID,
    val authorization: String?,
    val request: LegacyTraineeAttributionRequest,
)

data class GetLegacyTraineeAttributionOperationCommand(
    val expoId: UUID,
    val operationId: UUID,
    val authorization: String?,
)

data class LegacyTraineeAttributionOperationStatus(
    val operationId: UUID,
    val state: String,
    val errorStatus: Int?,
    val result: LegacyTraineeAttributionResult?,
)
