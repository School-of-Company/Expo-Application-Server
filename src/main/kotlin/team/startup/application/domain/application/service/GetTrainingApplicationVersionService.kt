package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.TrainingApplicationVersionResponse

interface GetTrainingApplicationVersionService {
    fun execute(traineeId: Long): TrainingApplicationVersionResponse
}
