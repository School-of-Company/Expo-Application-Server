package team.startup.application.domain.application.presentation.dto

import jakarta.validation.constraints.Size

data class TrainingApplicationsByTraineesRequest(
    @field:Size(max = 500) val traineeIds: List<Long>,
)

data class TrainingApplicationForReportResponse(
    val applicationId: Long,
    val traineeId: Long,
    val trainingProgramId: Long,
)
