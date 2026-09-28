package team.startup.application.domain.application.presentation.dto

import team.startup.application.domain.application.entity.TrainingProgramCategory

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
)
