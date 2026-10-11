package team.startup.application.domain.application.presentation.dto

import team.startup.application.domain.application.entity.TrainingProgramCategory
import java.util.UUID

data class TraineeReference(
    val id: Long,
    val expoId: String,
)

data class TrainingProgramReference(
    val id: Long,
    val expoId: String,
    val category: TrainingProgramCategory,
)

data class ApplyTrainingProgramsCommand(
    val trainee: TraineeReference,
    val programs: List<TrainingProgramReference>,
    val operationId: UUID? = null,
    val expectedVersion: Long? = null,
)

data class TrainingApplicationResponse(
    val applicationId: Long,
    val traineeId: Long,
    val status: Boolean,
    val entryTime: String?,
    val leaveTime: String?,
)
