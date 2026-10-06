package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.TrainingApplicationForReportResponse
import team.startup.application.domain.application.presentation.dto.TrainingApplicationsByTraineesRequest

interface ReportTrainingApplicationService {
    fun findAllByTrainees(request: TrainingApplicationsByTraineesRequest): List<TrainingApplicationForReportResponse>
}
