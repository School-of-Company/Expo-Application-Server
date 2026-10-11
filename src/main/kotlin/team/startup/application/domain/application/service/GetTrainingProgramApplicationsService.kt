package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.TrainingApplicationResponse

interface GetTrainingProgramApplicationsService {
    fun execute(programId: Long): List<TrainingApplicationResponse>
}
