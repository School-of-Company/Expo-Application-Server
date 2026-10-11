package team.startup.application.domain.application.presentation.dto

import io.swagger.v3.oas.annotations.media.Schema
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

@Schema(description = "연수 프로그램 신청 결과", example = """{"applicationId":123,"traineeId":456}""")
data class TrainingApplicationResponse(
    val applicationId: Long,
    val traineeId: Long,
)
