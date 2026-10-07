package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.ApplyTrainingProgramsCommand

interface ReplaceTrainingProgramsService {
    fun execute(command: ApplyTrainingProgramsCommand)
}
