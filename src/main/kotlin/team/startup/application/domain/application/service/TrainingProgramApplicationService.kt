package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.ApplyTrainingProgramsCommand
import team.startup.application.domain.application.presentation.dto.TrainingApplicationResponse

interface TrainingProgramApplicationService {
    fun execute(command: ApplyTrainingProgramsCommand)

    fun replace(command: ApplyTrainingProgramsCommand)

    fun findAllByProgram(programId: Long): List<TrainingApplicationResponse>

    fun deleteAllByProgram(programId: Long)
}
